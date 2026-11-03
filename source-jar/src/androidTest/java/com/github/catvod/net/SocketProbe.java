package com.github.catvod.net;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

/** Disposable transports and deterministic callback/deadline interleavings. */
public final class SocketProbe {
    private static void check(boolean condition, String label, Consumer<String> checks) {
        if (!condition) throw new AssertionError(label);
        checks.accept(label);
    }

    public static void run(Consumer<String> checks) throws Exception {
        futures(checks);
        descendantsAndReconnect(checks);
        callbackFault(checks);
        queuedCallbackCancellation(checks);
        refreshedDeadline(checks);
    }

    private static void futures(Consumer<String> checks) throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            CompletableFuture<Net.Result> pending = net.socketAsync(server.url("/pending").toString(), NetOptions.from("{\"timeout\":5000}"));
            if (server.takeRequest(2, TimeUnit.SECONDS) == null) throw new AssertionError("Handshake did not start");
            check(pending.cancel(true) && pending.isCancelled(), "websocket_future_cancellation_aborts_pending_handshake", checks);
            server.enqueue(new MockResponse().setBody("healthy"));
            check(net.get(server.url("/healthy").toString()).equals("healthy"), "websocket_cancel_keeps_parent_http_usable", checks);
            server.takeRequest(2, TimeUnit.SECONDS);
            AtomicReference<WebSocket> peer = new AtomicReference<>();
            server.enqueue(passive(peer));
            Net.Result timeout = net.socketAsync(server.url("/silent-private-fixture").toString(), NetOptions.from("{\"timeout\":200}"))
                    .get(3, TimeUnit.SECONDS);
            check(timeout.error != null && !timeout.error.contains("silent-private-fixture"), "websocket_first_message_deadline_is_bounded_and_redacted", checks);
            server.takeRequest(2, TimeUnit.SECONDS);
            if (peer.get() != null) peer.get().close(1000, "fixture-cleanup");
            CountDownLatch callback = new CountDownLatch(1);
            AtomicReference<Net.Result> value = new AtomicReference<>();
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) { socket.send("callback-影视"); }
            }));
            net.webSocket(server.url("/callback").toString(), NetOptions.from("{\"timeout\":1000}"), result -> {
                value.set(result); callback.countDown();
            });
            check(callback.await(3, TimeUnit.SECONDS) && value.get().code.equals(101) && value.get().text().equals("callback-影视"),
                    "websocket_first_message_callback_preserves_handshake_and_text", checks);
        }
    }

    private static MockResponse passive(AtomicReference<WebSocket> peer) {
        return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) { peer.set(socket); }
            @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
        });
    }

    private static void descendantsAndReconnect(Consumer<String> checks) throws Exception {
        try (MockWebServer server = new MockWebServer(); Net root = new Net()) {
            server.start();
            AtomicReference<WebSocket> peer = new AtomicReference<>();
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) { socket.close(1001, "retry-fixture"); }
                @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
            }));
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) { peer.set(socket); socket.send("recovered"); }
            }));
            AtomicInteger opened = new AtomicInteger();
            AtomicInteger messages = new AtomicInteger();
            CountDownLatch recovered = new CountDownLatch(1);
            Net child = root.session();
            NetSocket socket = child.connect(server.url("/reconnect").toString(), "{\"reconnect\":true,\"timeout\":1000}", (owner, event) -> {
                if (event.type.equals("open")) opened.incrementAndGet();
                if (event.type.equals("message")) { messages.incrementAndGet(); recovered.countDown(); }
            });
            try {
                check(recovered.await(4, TimeUnit.SECONDS) && opened.get() == 2 && messages.get() == 1,
                        "websocket_reconnect_delivers_new_connection_messages", checks);
                server.takeRequest(2, TimeUnit.SECONDS); server.takeRequest(2, TimeUnit.SECONDS);
                root.close();
                check(!socket.send("late") && !socket.send(new byte[] {1}), "source_close_rejects_descendant_websocket_sends", checks);
                if (peer.get() != null) peer.get().send("after-close");
                check(server.takeRequest(700, TimeUnit.MILLISECONDS) == null && messages.get() == 1,
                        "source_close_suppresses_late_messages_and_reconnect", checks);
                check(child.socketAsync(server.url("/closed").toString(), new NetOptions()).get(2, TimeUnit.SECONDS).error != null,
                        "closed_source_websocket_future_finishes_with_failure", checks);
            } finally {
                socket.cancel(); if (peer.get() != null) peer.get().close(1000, "fixture-cleanup");
            }
        }
    }

    private static void callbackFault(Consumer<String> checks) throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            CountDownLatch ended = new CountDownLatch(1);
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onFailure(WebSocket socket, Throwable failure, Response response) { ended.countDown(); }
                @Override public void onClosed(WebSocket socket, int code, String reason) { ended.countDown(); }
            }));
            NetSocket socket = net.connect(server.url("/fault").toString(), "{}", (owner, event) -> { throw new AssertionError("fixture-only"); });
            try { check(ended.await(3, TimeUnit.SECONDS) && !socket.send("late"), "websocket_callback_assertion_releases_connection", checks); }
            finally { socket.cancel(); }
        }
    }

    private static void queuedCallbackCancellation(Consumer<String> checks) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) { socket.send("hold"); }
            }));
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            NetSocket socket = net.connect(server.url("/queued").toString(), "{}", (owner, event) -> {
                if (event.type.equals("message")) {
                    received.add((String) event.content);
                    if (event.content.equals("hold")) {
                        holding.countDown();
                        try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                }
            });
            Thread queued = null;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try {
                if (!holding.await(3, TimeUnit.SECONDS)) throw new AssertionError("First callback did not enter");
                Field generation = NetSocket.class.getDeclaredField("generation"); generation.setAccessible(true);
                long current = generation.getLong(socket);
                Method message = NetSocket.class.getDeclaredMethod("message", long.class, Object.class, boolean.class); message.setAccessible(true);
                // Inject a concurrent transport callback while the real network callback holds serialization.
                queued = new Thread(() -> {
                    try { message.invoke(socket, current, "queued", false); } catch (Throwable error) { failure.set(error); }
                }, "queued-socket-callback-fixture");
                queued.start();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (queued.getState() != Thread.State.BLOCKED && System.nanoTime() < until) Thread.sleep(5);
                if (queued.getState() != Thread.State.BLOCKED) throw new AssertionError("Second callback did not queue");
                net.close(); release.countDown(); queued.join(2000);
                check(!queued.isAlive() && failure.get() == null && received.equals(Collections.singletonList("hold")),
                        "cancelled_websocket_drops_callback_waiting_for_serialization", checks);
            } finally {
                socket.cancel(); release.countDown();
                if (queued != null) { queued.interrupt(); queued.join(2000); }
            }
        }
    }

    private static void refreshedDeadline(Consumer<String> checks) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            AtomicReference<WebSocket> peer = new AtomicReference<>();
            server.enqueue(passive(peer));
            ManualScheduler timer = new ManualScheduler();
            CountDownLatch opened = new CountDownLatch(1);
            CountDownLatch refreshed = new CountDownLatch(1);
            CountDownLatch alive = new CountDownLatch(1);
            CountDownLatch expired = new CountDownLatch(1);
            NetSocket socket = new NetSocket(new OkHttpClient(), new Request.Builder().url(server.url("/deadline")).build(),
                    NetOptions.from("{\"timeout\":1000,\"idle\":1000}"), (owner, event) -> {
                        if (event.type.equals("open")) opened.countDown();
                        if (event.type.equals("message") && event.content.equals("refresh")) refreshed.countDown();
                        if (event.type.equals("message") && event.content.equals("still-open")) alive.countDown();
                        if (event.type.equals("error")) expired.countDown();
                    }, ignored -> { }, ignored -> { }, () -> true, timer);
            CountDownLatch runOld = new CountDownLatch(1);
            CountDownLatch oldEntered = new CountDownLatch(1);
            Thread old = null;
            try {
                socket.start();
                if (!opened.await(3, TimeUnit.SECONDS)) throw new AssertionError("Deadline connection did not open");
                Runnable stale = timer.latest().action;
                old = new Thread(() -> {
                    oldEntered.countDown();
                    try { runOld.await(); stale.run(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }, "running-old-deadline-fixture");
                old.start(); oldEntered.await(1, TimeUnit.SECONDS);
                peer.get().send("refresh");
                if (!refreshed.await(3, TimeUnit.SECONDS)) throw new AssertionError("Idle deadline did not refresh");
                // Model an old task already running when cancel(false) invalidated its future.
                runOld.countDown(); old.join(2000);
                peer.get().send("still-open");
                check(alive.await(2, TimeUnit.SECONDS) && expired.getCount() == 1,
                        "stale_running_idle_timer_cannot_cancel_refreshed_websocket", checks);
                timer.latest().action.run();
                check(expired.await(2, TimeUnit.SECONDS), "current_idle_timer_still_cancels_expired_websocket", checks);
            } finally {
                socket.cancel(); runOld.countDown(); if (old != null) { old.interrupt(); old.join(2000); }
                if (peer.get() != null) peer.get().close(1000, "fixture-cleanup");
            }
        }
    }

    private static final class ManualScheduler implements NetSocket.Scheduler {
        private Task latest;
        @Override public synchronized ScheduledFuture<?> schedule(Runnable action, long delay) { latest = new Task(action); return latest; }
        synchronized Task latest() { return latest; }
    }
    private static final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
        final Runnable action;
        Task(Runnable action) { super(action, null); this.action = action; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
