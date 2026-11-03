package com.sogud.bananatv.source.script;

import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.sogud.bananatv.source.script.ScriptSession.Operation.*;

/** Loopback HTTP only; never touches a personal source or account. */
final class HttpProbe {
    private static void check(boolean condition, String label, Consumer<String> report) {
        if (!condition) throw new AssertionError(label);
        report.accept(label);
    }

    static void run(android.content.Context context, Consumer<String> report) throws Exception {
        MockWebServer server = new MockWebServer();
        OkHttpClient client = new OkHttpClient();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.start();
        try {
            String url = server.url("/fixture").toString();
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8")
                    .setHeader("X-Fixture", "ready").setBody("动画"));
            String code = "let base=''; export default {init(value){base=value;},"
                    + "async home(){return await req(base,{headers:{Referer:'https://fixture.invalid/'}});}}";
            ScriptHttp http = new ScriptHttp(client, 1024);
            try (ScriptSession session = new ScriptSession(Collections.singletonMap("source.js", code), "source.js",
                    16 * 1024 * 1024, 8192, null, http)) {
                session.call(INIT, new JSONArray().put(url));
                JSONObject result = new JSONObject(session.call(HOME, new JSONArray()));
                RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
                check(result.getInt("code") == 200 && result.getString("content").equals("动画")
                        && result.getJSONObject("headers").getString("x-fixture").equals("ready")
                        && sent != null && "https://fixture.invalid/".equals(sent.getHeader("Referer")),
                        "script_req_async_headers_and_utf8_response", report);
            }
            int beforeClosed = server.getRequestCount();
            boolean closed = false;
            try { http.request(url, new JSONObject()); } catch (IOException expected) { closed = true; }
            check(closed && beforeClosed == server.getRequestCount(), "session_close_cancels_http_scope", report);
            String recovery = "export default {home(){try{req(" + JSONObject.quote(url)
                    + ");return 'unexpected';}catch(error){return String(error);}}}";
            try (ScriptSession session = new ScriptSession(Collections.singletonMap("source.js", recovery), "source.js",
                    16 * 1024 * 1024, 8192, null, http)) {
                check(session.call(HOME, new JSONArray()).equals("Error: Source host call failed")
                        && beforeClosed == server.getRequestCount(), "script_can_catch_redacted_http_host_failure", report);
            }
            server.enqueue(new MockResponse().setBody("worker-network"));
            try (WorkerProbe worker = new WorkerProbe(context)) {
                String script = "export default {async home(){return await req(" + JSONObject.quote(url) + ");}}";
                JSONObject reply = new JSONObject(worker.run(script, 10000).getJSONArray("results").getString(0));
                check(reply.getString("content").equals("worker-network"), "worker_async_req_reaches_loopback_fixture", report);
                server.takeRequest(2, TimeUnit.SECONDS);
            }

            try (ScriptHttp transport = new ScriptHttp(client, 1024)) {
                server.enqueue(new MockResponse().setBody("posted"));
                transport.request(url, new JSONObject().put("method", "POST").put("data", "{\"name\":\"动画\"}")
                        .put("headers", new JSONObject().put("Content-Type", "application/json; charset=utf-8")));
                RecordedRequest posted = server.takeRequest(2, TimeUnit.SECONDS);
                check(posted != null && posted.getMethod().equals("POST")
                        && posted.getBody().readUtf8().equals("{\"name\":\"动画\"}")
                        && posted.getHeader("Content-Type").startsWith("application/json"), "http_post_body_and_content_type", report);

                byte[] binary = new byte[]{0, (byte)255, 10, 13};
                server.enqueue(new MockResponse().setBody(new Buffer().write(binary)));
                JSONObject encoded = transport.request(url, new JSONObject().put("buffer", 2));
                check(java.util.Arrays.equals(binary, Base64.decode(encoded.getString("content"), Base64.DEFAULT)),
                        "http_binary_base64_has_no_loss", report);
                server.takeRequest(2, TimeUnit.SECONDS);

                server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain; charset=ISO-8859-1")
                        .setBody(new Buffer().write(new byte[]{(byte)233})));
                check(transport.request(url, new JSONObject()).getString("content").equals("é"), "http_declared_charset", report);
                server.takeRequest(2, TimeUnit.SECONDS);

                server.enqueue(new MockResponse().setResponseCode(403).addHeader("Set-Cookie", "a=1")
                        .addHeader("Set-Cookie", "b=2").setBody("challenge"));
                JSONObject denied = transport.request(url, new JSONObject());
                check(denied.getInt("code") == 403 && denied.getString("content").equals("challenge")
                        && denied.getJSONObject("headers").getJSONArray("set-cookie").length() == 2,
                        "http_error_page_and_repeated_headers_preserved", report);
                server.takeRequest(2, TimeUnit.SECONDS);

                server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/target")));
                server.enqueue(new MockResponse().setBody("redirected"));
                check(transport.request(url, new JSONObject()).getString("content").equals("redirected"), "http_redirect_followed", report);
                server.takeRequest(2, TimeUnit.SECONDS); server.takeRequest(2, TimeUnit.SECONDS);
                server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/target")));
                check(transport.request(url, new JSONObject().put("redirect", 0)).getInt("code") == 302,
                        "http_redirect_can_be_disabled", report);
                server.takeRequest(2, TimeUnit.SECONDS);

                server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
                boolean timeout = false;
                try { transport.request(url, new JSONObject().put("timeout", 100)); } catch (IOException expected) { timeout = true; }
                check(timeout, "http_request_deadline", report);
                server.takeRequest(2, TimeUnit.SECONDS);
            }
            try (ScriptHttp limited = new ScriptHttp(client, 4)) {
                server.enqueue(new MockResponse().setChunkedBody("too-large", 1));
                boolean rejected = false;
                try { limited.request(url, new JSONObject()); } catch (IOException expected) { rejected = true; }
                check(rejected, "http_chunked_response_size_limit", report);
                server.takeRequest(2, TimeUnit.SECONDS);
            }
            try (ScriptHttp pending = new ScriptHttp(client, 1024)) {
                server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
                Future<Boolean> request = executor.submit(() -> {
                    try { pending.request(url, new JSONObject()); return false; } catch (IOException expected) { return true; }
                });
                check(server.takeRequest(2, TimeUnit.SECONDS) != null, "http_cancellation_request_started", report);
                pending.close();
                check(request.get(2, TimeUnit.SECONDS), "http_cancel_aborts_active_socket", report);
            }
        } finally {
            executor.shutdownNow();
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
            server.shutdown();
        }
    }
}
