package com.github.catvod.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

/** Loopback cancellation and bounded scheduling checks, independent of public sources. */
public final class AsyncProbe {
    public static void run(Consumer<String> passed) throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().startsWith("/wait")) return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                    return new MockResponse().setBody("available");
                }
            });
            server.start();
            CompletableFuture<Net.Result> active = net.requestAsync(server.url("/wait").toString(), NetOptions.from("{\"timeout\":2000}"));
            require(server.takeRequest(2, TimeUnit.SECONDS) != null, "async_request_is_actually_waiting", passed);
            require(active.cancel(true) && active.isCancelled(), "async_future_cancellation_stops_its_request", passed);
            require(net.get(server.url("/parent").toString()).equals("available"), "async_cancel_keeps_parent_usable", passed);
            AtomicInteger concurrent = new AtomicInteger(); AtomicInteger maximum = new AtomicInteger();
            CountDownLatch both = new CountDownLatch(2);
            List<String> output = net.map(Arrays.asList("a", "b", "c", "d"), (item, index) -> {
                int count = concurrent.incrementAndGet(); maximum.accumulateAndGet(count, Math::max);
                try {
                    both.countDown(); if (!both.await(2, TimeUnit.SECONDS)) throw new AssertionError("Mapping was serial");
                    return index + ":" + item;
                } finally { concurrent.decrementAndGet(); }
            }, 2);
            require(output.equals(Arrays.asList("0:a", "1:b", "2:c", "3:d")) && maximum.get() == 2,
                    "concurrent_map_retains_input_order_and_requested_concurrency", passed);
        }
        queuedAndFailure(passed);
        blockedNetworkFailure(passed);
        submissionAndClose(passed);
    }

    private static void queuedAndFailure(Consumer<String> passed) throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
            AtomicReference<Throwable> mappingFailure = new AtomicReference<>();
            Thread mapping = new Thread(() -> {
                try {
                    net.map(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7), (value, index) -> {
                        entered.countDown(); release.await(); return value;
                    }, 8);
                } catch (Throwable error) { mappingFailure.set(error); }
            });
            mapping.start();
            try {
                require(entered.await(3, TimeUnit.SECONDS), "all_bounded_workers_are_occupied_before_queue_test", passed);
                CompletableFuture<Net.Result> queued = net.requestAsync(server.url("/never-sent").toString(), NetOptions.from("{}"));
                AtomicInteger callbacks = new AtomicInteger();
                net.enqueue(server.url("/no-callback").toString(), NetOptions.from("{}"), result -> callbacks.incrementAndGet());
                net.close();
                Net.Result cancelled = queued.get(2, TimeUnit.SECONDS);
                mapping.join(2000);
                require(cancelled.error != null && !mapping.isAlive() && mappingFailure.get() != null,
                        "source_close_finishes_queued_http_and_interrupts_mapping", passed);
                require(server.getRequestCount() == 0 && callbacks.get() == 0,
                        "source_close_removes_queued_network_and_suppresses_callback", passed);
                require(net.requestAsync(server.url("/closed").toString(), NetOptions.from("{}")).get(1, TimeUnit.SECONDS).error != null,
                        "closed_source_async_request_finishes_without_network", passed);
            } finally { release.countDown(); net.close(); mapping.join(2000); }
        }
        try (Net net = new Net()) {
            CountDownLatch waiting = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            boolean rejected = false;
            try {
                net.map(Arrays.asList(0, 1), (item, index) -> {
                    if (index == 0) { waiting.countDown(); try { new CountDownLatch(1).await(); }
                        catch (InterruptedException stopped) { interrupted.countDown(); throw stopped; } }
                    else { if (!waiting.await(2, TimeUnit.SECONDS)) throw new AssertionError("Missing sibling"); throw new IllegalArgumentException("fixture"); }
                    return item;
                }, 2);
            } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected && interrupted.await(2, TimeUnit.SECONDS), "map_failure_preserves_exception_and_interrupts_sibling", passed);
            require(net.map(Arrays.asList(1, 2), (item, index) ->
                            net.map(Arrays.asList(item, item + 1), (nested, position) -> nested).get(1), 2)
                            .equals(Arrays.asList(2, 3)), "nested_map_does_not_wait_on_its_own_worker_pool", passed);
            List<Integer> inputs = new ArrayList<>(); for (int value = 0; value < 300; value++) inputs.add(value);
            require(net.map(inputs, (item, index) -> item + 1, 2).get(299) == 300,
                    "large_map_uses_bounded_workers_instead_of_one_queued_task_per_item", passed);
        }
    }

    private static void blockedNetworkFailure(Consumer<String> passed) throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            CountDownLatch received = new CountDownLatch(1);
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().equals("/blocked")) {
                        received.countDown(); return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                    }
                    return new MockResponse().setBody("parent-alive");
                }
            });
            server.start();
            CountDownLatch released = new CountDownLatch(1);
            boolean failed = false;
            try {
                net.map(Arrays.asList(0, 1), (item, index) -> {
                    if (index == 0) {
                        try { return net.get(server.url("/blocked").toString()); }
                        finally { released.countDown(); }
                    }
                    if (!received.await(2, TimeUnit.SECONDS)) throw new AssertionError("No blocked socket");
                    throw new IllegalArgumentException("fixture");
                }, 2);
            } catch (IllegalArgumentException expected) { failed = true; }
            require(failed && released.await(2, TimeUnit.SECONDS),
                    "map_failure_aborts_sibling_real_socket_before_its_ten_second_deadline", passed);
            require(net.get(server.url("/parent").toString()).equals("parent-alive"),
                    "map_socket_cancellation_keeps_parent_network_usable", passed);
        }
    }

    private static void submissionAndClose(Consumer<String> passed) throws Exception {
        CountDownLatch workerHeld = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
        CountDownLatch submitting = new CountDownLatch(1), releaseSubmission = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean pause = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(2)) {
            @Override public void execute(Runnable task) {
                if (pause.get()) {
                    submitting.countDown();
                    try { if (!releaseSubmission.await(3, TimeUnit.SECONDS)) throw new AssertionError("Submission not released"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                }
                super.execute(task);
            }
        };
        executor.execute(() -> { workerHeld.countDown(); try { releaseWorker.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
        NetTasks tasks = new NetTasks(executor);
        AtomicReference<CompletableFuture<Integer>> future = new AtomicReference<>();
        AtomicInteger cleanup = new AtomicInteger();
        Thread submit = new Thread(() -> future.set(tasks.submit(() -> 1, cleanup::incrementAndGet, error -> -1)));
        Thread close = new Thread(tasks::close);
        try {
            if (!workerHeld.await(2, TimeUnit.SECONDS)) throw new AssertionError("Worker not occupied");
            pause.set(true); submit.start();
            if (!submitting.await(2, TimeUnit.SECONDS)) throw new AssertionError("Submission not paused");
            close.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (close.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(10);
            require(close.getState() == Thread.State.BLOCKED, "source_close_serializes_with_executor_submission", passed);
            releaseSubmission.countDown(); submit.join(2000); close.join(2000);
            require(!submit.isAlive() && !close.isAlive() && future.get().get(2, TimeUnit.SECONDS) == -1
                            && executor.getQueue().isEmpty() && cleanup.get() == 1,
                    "cancelled_submission_cannot_reoccupy_shared_queue_after_source_close", passed);
        } finally {
            releaseSubmission.countDown(); releaseWorker.countDown(); tasks.close(); executor.shutdownNow();
            submit.join(2000); close.join(2000);
        }
    }

    private static void require(boolean value, String label, Consumer<String> passed) {
        if (!value) throw new AssertionError(label); passed.accept(label);
    }
}
