package com.sogud.bananatv.source.contract;

import com.github.catvod.net.Net;
import com.github.catvod.net.NetSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original loopback WebSocket contract, compiled against each host separately. */
final class SocketContractFixture {
    static JSONObject firstMessage() throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            server.enqueue(upgrade("first-影视"));
            String text = net.ws(server.url("/first").toString(), "{\"timeout\":1500}");
            text = handshake(text, server.takeRequest(2, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    socket.send(ByteString.of(new byte[] {0, 1, -1})); socket.close(1000, "fixture");
                }
                @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
            }));
            Object[] parts = net.wsParts(server.url("/binary").toString(), "{\"timeout\":1500,\"buffer\":2}");
            parts[0] = handshake((String) parts[0], server.takeRequest(2, TimeUnit.SECONDS));
            server.enqueue(upgrade("async-影视"));
            com.github.catvod.net.NetOptions options = com.github.catvod.net.NetOptions.from("{\"timeout\":1500}");
            Net.Result async = net.socketAsync(server.url("/async").toString(), options).get(3, TimeUnit.SECONDS);
            String future = handshake(async.json(options), server.takeRequest(2, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket socket, String message) { socket.send("ack:" + message); socket.close(1000, "fixture"); }
                @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
            }));
            String rpc = net.ws(server.url("/rpc").toString(), "{\"data\":\"client-影视\",\"timeout\":500}");
            rpc = handshake(rpc, server.takeRequest(2, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setResponseCode(403).setBody("private-fixture"));
            String failure = net.ws(server.url("/denied").toString(), "{\"timeout\":500}");
            server.takeRequest(2, TimeUnit.SECONDS);
            return new JSONObject().put("text", text).put("binaryParts", new JSONArray(parts)).put("async", future)
                    .put("rpc", rpc).put("failure", failure);
        }
    }

    private static String handshake(String wire, RecordedRequest request) throws Exception {
        if (request == null) throw new AssertionError("WebSocket handshake request was not sent");
        JSONObject value = new JSONObject(wire);
        JSONObject headers = value.optJSONObject("headers");
        if (headers != null && headers.has("sec-websocket-accept")) {
            String key = request.getHeader("Sec-WebSocket-Key");
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String expected = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
            if (!headers.getString("sec-websocket-accept").equals(expected)) throw new AssertionError("Invalid handshake accept value");
            headers.put("sec-websocket-accept", "verified-handshake");
        }
        return value.toString();
    }

    private static MockResponse upgrade(String text) {
        return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) { socket.send(text); socket.close(1000, "fixture"); }
            @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
        });
    }

    static JSONObject run() throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            CountDownLatch received = new CountDownLatch(2);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicReference<String> sentText = new AtomicReference<>();
            AtomicReference<byte[]> sentBytes = new AtomicReference<>();
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    socket.send("server-影视"); socket.send(ByteString.of(new byte[] {0, 1, -1}));
                }
                @Override public void onMessage(WebSocket socket, String text) {
                    sentText.set(text); received.countDown();
                    if (received.getCount() == 0) socket.close(1000, "fixture-done");
                }
                @Override public void onMessage(WebSocket socket, ByteString bytes) {
                    sentBytes.set(bytes.toByteArray()); received.countDown();
                    if (received.getCount() == 0) socket.close(1000, "fixture-done");
                }
                @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
            }));
            List<JSONObject> events = new ArrayList<>();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            NetSocket socket = net.connect(server.url("/socket").toString(), "{\"headers\":{\"X-Fixture\":\"socket\"}}", (owner, event) -> {
                try {
                    Object content = event.content;
                    if (content instanceof byte[]) content = new JSONArray(content);
                    JSONObject record = new JSONObject().put("type", event.type).put("binary", event.binary)
                            .put("code", event.code).put("reason", event.reason == null ? JSONObject.NULL : event.reason)
                            .put("error", event.error == null ? JSONObject.NULL : event.error)
                            .put("content", content == null ? JSONObject.NULL : content).put("parts", new JSONArray(event.parts()));
                    synchronized (events) { events.add(record); }
                    if (event.type.equals("close") || event.type.equals("closed") || event.type.equals("error")) closed.countDown();
                } catch (Throwable failure) { callbackFailure.set(failure); closed.countDown(); }
            });
            try {
                boolean textAccepted = socket.send("client-影视");
                boolean binaryAccepted = socket.send(new byte[] {2, 3, -2});
                if (!received.await(4, TimeUnit.SECONDS) || !closed.await(4, TimeUnit.SECONDS)) {
                    synchronized (events) { throw new AssertionError("WebSocket fixture did not finish: " + events); }
                }
                if (callbackFailure.get() != null) throw new AssertionError(callbackFailure.get());
                JSONObject report = new JSONObject().put("textAccepted", textAccepted).put("binaryAccepted", binaryAccepted)
                        .put("receivedText", sentText.get()).put("receivedBytes", new JSONArray(sentBytes.get()))
                        .put("header", server.takeRequest(2, TimeUnit.SECONDS).getHeader("X-Fixture"));
                synchronized (events) { report.put("events", new JSONArray(events)); }
                return report;
            } finally { socket.cancel(); }
        }
    }
}
