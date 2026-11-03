package com.sogud.bananatv.source.script;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One binding and generation. Cancellation targets only the bound worker's current lease. */
final class ScriptProcess implements ServiceConnection, AutoCloseable {
    private final Context context;
    private final Class<? extends ScriptWorkerService> component;
    final String generation;
    private final CountDownLatch connected = new CountDownLatch(1), dead = new CountDownLatch(1);
    private final LinkedBlockingQueue<Message> responses = new LinkedBlockingQueue<>(16);
    private final HandlerThread callbacks = new HandlerThread("source-worker-replies");
    private final ExecutorService pipes = Executors.newFixedThreadPool(2);
    private final Object piping = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(), aborted = new AtomicBoolean();
    private final Messenger replies;
    private volatile Messenger remote;
    private volatile IBinder binder;
    private volatile boolean sentWork;
    private boolean bound;
    private ParcelFileDescriptor inputWriter, outputReader;
    private int nextRequest;
    private volatile int workerPid;

    ScriptProcess(Context context, Class<? extends ScriptWorkerService> component, String generation) throws IOException {
        this.context = context.getApplicationContext(); this.component = component; this.generation = generation;
        callbacks.start();
        replies = new Messenger(new Handler(callbacks.getLooper(), message -> {
            if (Objects.equals(generation, message.getData().getString("generation"))) responses.offer(Message.obtain(message));
            return true;
        }));
        try {
            bound = this.context.bindService(new Intent(this.context, component), this, Context.BIND_AUTO_CREATE);
            if (!bound) throw new IOException("Source worker is unavailable");
        } catch (RuntimeException | IOException failure) { close(); throw new IOException("Source worker is unavailable"); }
    }

    @Override public synchronized void onServiceConnected(ComponentName name, IBinder service) {
        if (closed.get() || binder != null) return;
        binder = service; remote = new Messenger(service);
        try { service.linkToDeath(dead::countDown, 0); } catch (android.os.RemoteException failure) { dead.countDown(); }
        connected.countDown();
    }
    @Override public void onServiceDisconnected(ComponentName name) { dead.countDown(); }
    @Override public void onBindingDied(ComponentName name) { dead.countDown(); connected.countDown(); }
    @Override public void onNullBinding(ComponentName name) { dead.countDown(); connected.countDown(); }
    boolean usable() { IBinder current = binder; return !closed.get() && !aborted.get() && current != null && current.isBinderAlive() && dead.getCount() != 0; }
    int pid() { return workerPid; }

    JSONObject exchange(int operation, JSONObject request, long deadline) throws Exception {
        if (!connected.await(remaining(deadline), TimeUnit.MILLISECONDS)) throw new TimeoutException("Source worker connection deadline");
        if (!usable()) throw new IOException("Source worker is unavailable");
        int id = ++nextRequest;
        ParcelFileDescriptor[] input = ParcelFileDescriptor.createPipe(), output = null;
        Future<?> writer = null; Future<JSONObject> result = null;
        try {
            output = ParcelFileDescriptor.createPipe();
            synchronized (piping) {
                if (closed.get() || aborted.get()) throw new IOException("Source worker was cancelled");
                inputWriter = input[1]; outputReader = output[0];
            }
            ParcelFileDescriptor.AutoCloseInputStream reader = new ParcelFileDescriptor.AutoCloseInputStream(output[0]);
            writer = pipes.submit(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(input[1])) {
                    ScriptWire.write(out, request, ScriptWire.REQUEST_LIMIT);
                } catch (Exception failure) { throw new IllegalStateException("Source input pipe failed"); }
            });
            result = pipes.submit(() -> {
                try (ParcelFileDescriptor.AutoCloseInputStream in = reader) {
                    return ScriptWire.read(in, generation == null ? ScriptWire.RESULT_LIMIT : ScriptWire.SESSION_RESULT_LIMIT);
                }
            });
            Message message = Message.obtain(null, operation, id, 0); message.replyTo = replies;
            message.getData().putParcelable("input", input[0]); message.getData().putParcelable("output", output[1]);
            if (generation != null) message.getData().putString("generation", generation);
            synchronized (piping) {
                if (closed.get() || aborted.get()) throw new IOException("Source worker was cancelled");
                sentWork = true; remote.send(message);
            }
            input[0].close(); output[1].close();
            await(ScriptWorkerService.STARTED, id, deadline);
            writer.get(remaining(deadline), TimeUnit.MILLISECONDS);
            JSONObject response = result.get(remaining(deadline), TimeUnit.MILLISECONDS);
            await(ScriptWorkerService.FINISHED, id, deadline);
            if (generation != null && !generation.equals(response.optString("generation"))) throw new IOException("Stale source worker response");
            return response;
        } finally {
            synchronized (piping) { inputWriter = null; outputReader = null; }
            close(input[0]); close(input[1]);
            if (output != null) { close(output[0]); close(output[1]); }
            if (writer != null) writer.cancel(true); if (result != null) result.cancel(true);
        }
    }

    private void await(int kind, int request, long deadline) throws Exception {
        while (true) {
            if (dead.getCount() == 0) throw new IOException("Source worker exited");
            long wait = Math.min(remaining(deadline), 100);
            Message response = responses.poll(wait, TimeUnit.MILLISECONDS);
            if (response == null || response.arg1 != request) continue;
            if (response.what == ScriptWorkerService.BUSY) throw new IOException("Source worker is already owned");
            if (response.what == kind) {
                workerPid = response.arg2;
                if (workerPid == Process.myPid()) throw new IOException("Source worker is not separate");
                return;
            }
        }
    }

    void abort() {
        if (!aborted.compareAndSet(false, true)) return;
        Messenger current = remote;
        if (current != null) {
            Message message = Message.obtain(null, ScriptWorkerService.TERMINATE);
            if (generation != null) message.getData().putString("generation", generation);
            try { current.send(message); } catch (android.os.RemoteException failure) { dead.countDown(); }
        }
        synchronized (piping) { close(inputWriter); close(outputReader); }
    }

    boolean terminateAndWait() {
        abort();
        if (!sentWork) return true;
        boolean interrupted = Thread.interrupted();
        long deadline = SystemClock.elapsedRealtime() + 5000;
        try {
            while (dead.getCount() != 0) {
                long wait = deadline - SystemClock.elapsedRealtime();
                if (wait <= 0) return false;
                try { if (dead.await(wait, TimeUnit.MILLISECONDS)) return true; }
                catch (InterruptedException failure) { interrupted = true; }
            }
            return true;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    static long remaining(long deadline) throws TimeoutException {
        long value = deadline - SystemClock.elapsedRealtime();
        if (value <= 0) throw new TimeoutException("Source worker deadline"); return value;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (piping) { close(inputWriter); close(outputReader); }
        if (bound) { context.unbindService(this); bound = false; }
        pipes.shutdownNow(); callbacks.quitSafely();
    }
    private static void close(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
    }
}
