package com.sogud.bananatv.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class MediaWorkQueueTest {
    public static void main(String[] args) throws Exception {
        MediaWorkQueue queue = new MediaWorkQueue();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            queue.submit(() -> {
                started.countDown();
                try { release.await(); order.add("running"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            if (!started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Worker did not start");
            Future<?> background = queue.submit(() -> order.add("background"));
            Future<?> cancelled = queue.submitFirst(() -> order.add("cancelled")); cancelled.cancel(false);
            Runnable selection = () -> order.add("selection");
            Future<?> foreground = args.length == 0 ? queue.submitFirst(selection) : queue.submit(selection);
            release.countDown(); foreground.get(2, TimeUnit.SECONDS); background.get(2, TimeUnit.SECONDS);
            if (!order.equals(List.of("running", "selection", "background"))) throw new AssertionError(order);
        } finally { release.countDown(); queue.shutdownNow(); }
        boolean rejected = false;
        try { queue.submitFirst(() -> { }); } catch (RejectedExecutionException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Closed queue accepted a selection");
        System.out.println("PASS: selected work precedes queued catalog requests; cancellation and shutdown retained");
    }
}
