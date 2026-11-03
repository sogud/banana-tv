package com.github.catvod.net;

import java.io.InterruptedIOException;
import com.sogud.bananatv.source.SourceHttp;
import okhttp3.Call;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Bounded shared workers; each owner can cancel its queued and running work. */
final class NetTasks implements AutoCloseable {
    private static final ThreadLocal<Task<?>> RUNNING = new ThreadLocal<>();
    static SourceHttp.Scope operationScope() {
        Task<?> task = RUNNING.get(); return task == null ? null : task.operation;
    }
    static void publish(SourceHttp.Scope owner, SourceHttp.IoAction action) throws java.io.IOException {
        Task<?> task = RUNNING.get();
        if (task == null) owner.runIfOpen(action);
        else task.publish(owner, action);
    }
    static boolean isWorkerThread() { return RUNNING.get() != null; }

    /** Socket reads must stop when either the source or its mapping operation is cancelled. */
    static SourceHttp.Scope scopeFor(SourceHttp.Scope owner) {
        SourceHttp.Scope operation = operationScope();
        if (operation == null) return owner;
        return new SourceHttp.Scope() {
            @Override public void check() throws InterruptedIOException { owner.check(); operation.check(); }
            @Override public void attach(Call call) throws InterruptedIOException {
                owner.attach(call);
                try { operation.attach(call); }
                catch (InterruptedIOException stopped) { owner.detach(call); throw stopped; }
            }
            @Override public void detach(Call call) { owner.detach(call); operation.detach(call); }
        };
    }

    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> {
                Thread thread = new Thread(task, "source-http"); thread.setDaemon(true); return thread;
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }
    private final ThreadPoolExecutor executor;
    NetTasks() { this(WORKERS); }
    NetTasks(ThreadPoolExecutor executor) { this.executor = executor; }

    private final Set<Task<?>> active = new HashSet<>();
    private boolean closed;

    <T> CompletableFuture<T> submit(Callable<T> action, Runnable cleanup, Function<Throwable, T> failure) {
        return submit(action, cleanup, () -> { }, failure);
    }

    <T> CompletableFuture<T> submit(Callable<T> action, Runnable cleanup, Runnable abort, Function<Throwable, T> failure) {
        Task<T> task = new Task<>(action, cleanup, abort, failure);
        Throwable rejected = null;
        synchronized (this) {
            if (closed) rejected = new InterruptedIOException();
            else {
                active.add(task);
                // The executor only queues work or throws; it never executes on the submitting thread.
                try { executor.execute(task.worker); }
                catch (RuntimeException error) { rejected = error; }
            }
        }
        if (rejected != null) task.finish(null, rejected, true);
        return task.result;
    }

    @Override public void close() {
        ArrayList<Task<?>> pending;
        synchronized (this) {
            if (closed) return;
            closed = true; pending = new ArrayList<>(active); active.clear();
        }
        for (Task<?> task : pending) task.finish(null, new InterruptedIOException(), true);
    }

    private final class Task<T> {
        final SourceHttp.Scope operation = new SourceHttp.Scope();
        final Object publication = new Object();
        boolean committed;
        final AtomicBoolean settled = new AtomicBoolean();
        final Runnable cleanup;
        final Runnable abort;
        final Function<Throwable, T> failure;
        final FutureTask<Void> worker;
        final CompletableFuture<T> result = new CompletableFuture<T>() {
            @Override public boolean cancel(boolean interrupt) {
                synchronized (publication) {
                    if (committed || !settled.compareAndSet(false, true)) return false;
                }
                release(true);
                return super.cancel(interrupt);
            }
        };

        Task(Callable<T> action, Runnable cleanup, Runnable abort, Function<Throwable, T> failure) {
            this.cleanup = cleanup; this.abort = abort; this.failure = failure;
            worker = new FutureTask<>(() -> {
                RUNNING.set(this);
                try { finish(action.call(), null, false); }
                catch (Throwable error) { finish(null, error, false); }
                finally { RUNNING.remove(); }
                return null;
            });
        }

        void publish(SourceHttp.Scope owner, SourceHttp.IoAction action) throws java.io.IOException {
            synchronized (publication) {
                if (settled.get()) throw new InterruptedIOException("Source operation cancelled");
                owner.runIfOpen(() -> { operation.check(); action.run(); committed = true; });
            }
        }

        void release(boolean abort) {
            operation.close();
            if (abort) { this.abort.run(); worker.cancel(true); executor.remove(worker); }
            cleanup.run();
            synchronized (NetTasks.this) { active.remove(this); }
        }

        void finish(T value, Throwable error, boolean abort) {
            synchronized (publication) {
                if ((abort && committed) || !settled.compareAndSet(false, true)) return;
            }
            release(abort);
            // User continuations must not occupy HTTP workers or run inside source.close().
            CompletableFuture.runAsync(() -> {
                if (error == null) result.complete(value);
                else if (failure == null) result.completeExceptionally(error);
                else {
                    try { result.complete(failure.apply(error)); }
                    catch (Throwable failed) { result.completeExceptionally(failed); }
                }
            });
        }
    }
}
