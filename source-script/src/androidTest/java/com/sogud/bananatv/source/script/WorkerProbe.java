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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Disposable process tests. Every terminate message targets our explicitly bound worker. */
final class WorkerProbe implements AutoCloseable, ServiceConnection {
    private final Context context;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final CountDownLatch dead = new CountDownLatch(1);
    private final LinkedBlockingQueue<Message> responses = new LinkedBlockingQueue<>();
    private final HandlerThread callbacks = new HandlerThread("script-probe-callbacks");
    private final ExecutorService pipes = Executors.newFixedThreadPool(2);
    private final Messenger replies;
    private Messenger remote;
    private int nextRequest;

    WorkerProbe(Context context) throws Exception {
        this.context = context;
        callbacks.start();
        replies = new Messenger(new Handler(callbacks.getLooper(), message -> {
            responses.add(Message.obtain(message));
            return true;
        }));
        if (!context.bindService(new Intent(context, ScriptWorkerService.class), this, Context.BIND_AUTO_CREATE)) {
            callbacks.quitSafely(); pipes.shutdownNow();
            throw new AssertionError("Cannot bind script worker");
        }
        if (!connected.await(10, TimeUnit.SECONDS)) {
            close(); throw new AssertionError("Worker connection timeout");
        }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder service) {
        remote = new Messenger(service);
        try { service.linkToDeath(dead::countDown, 0); } catch (Exception failure) { dead.countDown(); }
        connected.countDown();
    }

    @Override public void onServiceDisconnected(ComponentName name) { dead.countDown(); }

    JSONObject run(String script, long timeoutMillis) throws Exception {
        return run(script, timeoutMillis, false);
    }

    JSONObject run(String script, long timeoutMillis, boolean libraries) throws Exception {
        JSONObject request = new JSONObject().put("entry", "source.js").put("libraries", libraries)
                .put("modules", new JSONObject().put("source.js", script))
                .put("calls", new JSONArray().put(new JSONObject().put("operation", "HOME")
                        .put("arguments", new JSONArray())));
        return run(request, timeoutMillis);
    }

    JSONObject run(JSONObject request, long timeoutMillis) throws Exception {
        int requestId = ++nextRequest;
        ParcelFileDescriptor[] input = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] output = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor.AutoCloseInputStream reader = new ParcelFileDescriptor.AutoCloseInputStream(output[0]);
        Future<?> writer = pipes.submit(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(input[1])) {
                ScriptWire.write(out, request, ScriptWire.REQUEST_LIMIT);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        Future<JSONObject> result = pipes.submit(() -> {
            try (ParcelFileDescriptor.AutoCloseInputStream in = reader) {
                return ScriptWire.read(in, ScriptWire.RESULT_LIMIT);
            }
        });
        Message message = Message.obtain(null, ScriptWorkerService.RUN, requestId, 0);
        message.replyTo = replies;
        message.getData().putParcelable("input", input[0]);
        message.getData().putParcelable("output", output[1]);
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis;
        try {
            remote.send(message);
        } finally {
            input[0].close(); output[1].close();
        }
        try {
            Message started = await(ScriptWorkerService.STARTED, requestId, deadline);
            if (started.arg2 == Process.myPid()) throw new AssertionError("Script executed in instrumentation process");
            writer.get(remaining(deadline), TimeUnit.MILLISECONDS);
            JSONObject value = result.get(remaining(deadline), TimeUnit.MILLISECONDS);
            await(ScriptWorkerService.FINISHED, requestId, deadline);
            return value;
        } catch (TimeoutException timeout) {
            remote.send(Message.obtain(null, ScriptWorkerService.TERMINATE));
            if (!dead.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed-out worker did not die");
            throw timeout;
        } finally {
            reader.close();
            input[1].close();
            writer.cancel(true); result.cancel(true);
        }
    }

    private Message await(int kind, int request, long deadline) throws Exception {
        while (true) {
            Message response = responses.poll(remaining(deadline), TimeUnit.MILLISECONDS);
            if (response == null) throw new TimeoutException("Worker deadline");
            if (response.arg1 != request) continue;
            if (response.what == ScriptWorkerService.BUSY) throw new AssertionError("Unexpected busy worker");
            if (response.what == kind) return response;
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - android.os.SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new TimeoutException("Worker deadline");
        return remaining;
    }

    @Override public void close() {
        context.unbindService(this);
        pipes.shutdownNow(); callbacks.quitSafely();
    }
}
