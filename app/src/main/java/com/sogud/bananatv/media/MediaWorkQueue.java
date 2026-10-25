package com.sogud.bananatv.media;

import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keep script calls serial, while putting a user's selection ahead of queued catalog work. */
final class MediaWorkQueue extends ThreadPoolExecutor {
    private final LinkedBlockingDeque<Runnable> queue;

    MediaWorkQueue() {
        super(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<>());
        queue = (LinkedBlockingDeque<Runnable>) getQueue();
    }

    Future<?> submitFirst(Runnable work) {
        if (isShutdown()) throw new RejectedExecutionException("Media queue is closed");
        RunnableFuture<Void> task = newTaskFor(work, null);
        prestartCoreThread();
        queue.addFirst(task);
        return task;
    }
}
