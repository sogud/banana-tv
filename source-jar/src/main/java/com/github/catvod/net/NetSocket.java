package com.github.catvod.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Original WebSocket host adapter over the pinned OkHttp transport. */
public final class NetSocket implements AutoCloseable {
    private static final int MAX_MESSAGE_BYTES = 32 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    private static final ScheduledThreadPoolExecutor TIMERS = timers();
    private final Object lock = new Object();
    private final Object callbacks = new Object();
    private final OkHttpClient client;
    private final Request request;
    private final NetOptions options;
    private final BooleanSupplier ownerOpen;
    private final Scheduler scheduler;
    private Listener listener;
    private Consumer<NetSocket> released;
    private Consumer<String> terminated;
    private WebSocket socket;
    private ScheduledFuture<?> deadline;
    private ScheduledFuture<?> retry;
    private long generation;
    private long deadlineEpoch;
    private int failures;
    private boolean closing;
    private boolean finished;
    private boolean cancelled;
    private String timeoutError;
    private int responseCode;
    private Map<String, Object> responseHeaders = Collections.emptyMap();

    NetSocket(OkHttpClient client, Request request, NetOptions options, Listener listener,
            Consumer<NetSocket> released, Consumer<String> terminated, BooleanSupplier ownerOpen) {
        this(client, request, options, listener, released, terminated, ownerOpen, NetSocket::schedule);
    }

    NetSocket(OkHttpClient client, Request request, NetOptions options, Listener listener,
            Consumer<NetSocket> released, Consumer<String> terminated, BooleanSupplier ownerOpen, Scheduler scheduler) {
        this.client = client; this.request = request; this.options = options; this.listener = listener;
        this.released = released; this.terminated = terminated;
        this.ownerOpen = ownerOpen; this.scheduler = scheduler;
    }

    interface Scheduler { ScheduledFuture<?> schedule(Runnable operation, long delay); }

    private static ScheduledThreadPoolExecutor timers() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, operation -> {
            Thread thread = new Thread(operation, "source-websocket-timer"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    static ScheduledFuture<?> schedule(Runnable operation, long delay) {
        return TIMERS.schedule(operation, delay, TimeUnit.MILLISECONDS);
    }

    void start() throws IOException {
        synchronized (lock) {
            if (finished || cancelled || closing || !ownerOpen.getAsBoolean()) throw new IOException("Source WebSocket is closed");
            long current = ++generation;
            timeoutError = null;
            socket = client.newWebSocket(request, transport(current));
            armDeadline(current, options.getTimeout());
        }
    }

    private WebSocketListener transport(long current) {
        return new WebSocketListener() {
            @Override public void onOpen(WebSocket opened, Response response) {
                synchronized (lock) {
                    if (!active(current)) { opened.cancel(); return; }
                    responseCode = response.code(); responseHeaders = Net.headers(response.headers()); failures = 0;
                    cancelDeadline();
                    if (options.getIdle() > 0) armDeadline(current, options.getIdle());
                }
                publish(new Event("open", null, false, response.code(), null, null), current);
                String data = options.getBody();
                JsonElement value = options.getData();
                if (data == null && value != null && !value.isJsonNull()) {
                    data = value.isJsonPrimitive() ? value.getAsString() : value.toString();
                }
                if (data != null) send(data);
            }
            @Override public void onMessage(WebSocket source, String text) {
                boolean tooLarge = text.length() > MAX_MESSAGE_BYTES;
                if (!tooLarge && text.length() > MAX_MESSAGE_BYTES / 3) tooLarge = ByteString.encodeUtf8(text).size() > MAX_MESSAGE_BYTES;
                if (tooLarge) {
                    fail(current, new IOException(), 0); source.cancel(); return;
                }
                message(current, text, false);
            }
            @Override public void onMessage(WebSocket source, ByteString bytes) {
                if (bytes.size() > MAX_MESSAGE_BYTES) { fail(current, new IOException(), 0); source.cancel(); return; }
                message(current, bytes.toByteArray(), true);
            }
            @Override public void onClosing(WebSocket source, int code, String reason) { source.close(code, reason); }
            @Override public void onClosed(WebSocket source, int code, String reason) {
                synchronized (lock) { if (!active(current)) return; cancelDeadline(); }
                Event event = new Event("close", null, false, code, reason, null);
                if (!retry(current, event)) finish(event, "WebSocket closed before a message", true);
            }
            @Override public void onFailure(WebSocket source, Throwable failure, Response response) {
                if (response != null) {
                    synchronized (lock) {
                        if (!active(current)) return;
                        responseCode = response.code(); responseHeaders = Net.headers(response.headers());
                    }
                }
                fail(current, failure, response == null ? 0 : response.code());
            }
        };
    }

    private boolean active(long current) { return !finished && !cancelled && current == generation; }

    private void message(long current, Object content, boolean binary) {
        synchronized (lock) {
            if (!active(current)) return;
            if (options.getIdle() > 0 && !closing) armDeadline(current, options.getIdle());
        }
        publish(new Event("message", content, binary, 0, null, null), current);
    }

    private void armDeadline(long current, long millis) {
        cancelDeadline();
        long epoch = deadlineEpoch;
        deadline = scheduler.schedule(() -> {
            WebSocket stopped;
            synchronized (lock) {
                if (!active(current) || epoch != deadlineEpoch) return;
                timeoutError = "SocketTimeoutException: Network request failed";
                stopped = socket;
            }
            // OkHttp delivers onFailure on its reader/call thread, never on the shared timer.
            if (stopped != null) stopped.cancel();
        }, millis);
    }

    private void cancelDeadline() {
        ++deadlineEpoch;
        if (deadline != null) { deadline.cancel(false); deadline = null; }
    }

    private void fail(long current, Throwable failure, int code) {
        String error;
        synchronized (lock) {
            if (!active(current)) return;
            cancelDeadline();
            error = code > 0 ? "WebSocket handshake failed (HTTP " + code + ")"
                    : timeoutError == null ? Net.Result.failure(failure).error : timeoutError;
        }
        Event event = new Event("error", null, false, code, null, error);
        if (!retry(current, event)) finish(event, error, true);
    }

    private boolean retry(long current, Event event) {
        long delay;
        long delivery;
        synchronized (lock) {
            if (!active(current) || closing || !options.isReconnect()) return false;
            delivery = ++generation;
            socket = null;
            delay = Math.min(5000L, 200L << Math.min(failures++, 4));
        }
        publish(event, delivery);
        synchronized (lock) {
            if (!finished && !closing) {
                retry = scheduler.schedule(() -> { try { start(); } catch (IOException ignored) { } }, delay);
            }
        }
        return true;
    }

    private void publish(Event event, long current) {
        synchronized (callbacks) {
            Listener callback;
            synchronized (lock) { callback = active(current) && ownerOpen.getAsBoolean() ? listener : null; }
            deliver(callback, event);
        }
    }

    private void deliver(Listener callback, Event event) {
        if (callback == null) return;
        boolean returned = false;
        try { callback.accept(this, event); returned = true; }
        catch (RuntimeException | LinkageError | AssertionError ignored) { }
        finally { if (!returned) cancel(); }
    }

    public boolean send(String text) {
        synchronized (lock) { return !finished && !cancelled && !closing && ownerOpen.getAsBoolean() && socket != null && socket.send(text); }
    }
    public boolean send(byte[] bytes) {
        synchronized (lock) { return !finished && !cancelled && !closing && ownerOpen.getAsBoolean() && socket != null && socket.send(ByteString.of(bytes)); }
    }

    @Override public void close() {
        WebSocket closingSocket;
        synchronized (lock) {
            if (finished || cancelled || closing) return;
            closing = true;
            if (retry != null) retry.cancel(false);
            closingSocket = socket;
            if (closingSocket != null) armDeadline(generation, 1000);
        }
        if (closingSocket == null) finish(null, "WebSocket closed before a message", false);
        else closingSocket.close(1000, "");
    }

    public void cancel() {
        synchronized (lock) { cancelled = true; ++generation; }
        finish(null, "WebSocket cancelled", false);
    }

    private void finish(Event event, String reason, boolean notify) {
        Listener callback;
        Consumer<NetSocket> owner;
        Consumer<String> end;
        WebSocket stopped;
        long delivery;
        synchronized (lock) {
            if (finished) return;
            finished = true; delivery = ++generation;
            cancelDeadline();
            if (retry != null) { retry.cancel(false); retry = null; }
            stopped = socket; socket = null;
            callback = listener; listener = null;
            owner = released; released = null;
            end = terminated; terminated = null;
        }
        if (stopped != null) stopped.cancel();
        if (owner != null) owner.accept(this);
        try {
            if (notify) {
                synchronized (callbacks) {
                    synchronized (lock) { if (delivery != generation || cancelled || !ownerOpen.getAsBoolean()) callback = null; }
                    deliver(callback, event);
                }
            }
        }
        finally { if (end != null) end.accept(reason); }
    }

    int responseCode() { synchronized (lock) { return responseCode; } }
    Map<String, Object> responseHeaders() { synchronized (lock) { return responseHeaders; } }

    public interface Listener { void accept(NetSocket socket, Event event); }

    public static final class Event {
        public final String type;
        public final Object content;
        public final boolean binary;
        public final int code;
        public final String reason;
        public final String error;

        private Event(String type, Object content, boolean binary, int code, String reason, String error) {
            this.type = type; this.content = content; this.binary = binary; this.code = code; this.reason = reason; this.error = error;
        }
        public Object[] parts() {
            Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("type", type);
            if (type.equals("message")) metadata.put("binary", binary);
            if (code != 0) metadata.put("code", code);
            if (reason != null) metadata.put("reason", reason);
            if (error != null) metadata.put("error", error);
            return new Object[] {JSON.toJson(metadata), content};
        }
    }
}
