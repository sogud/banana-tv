package com.sogud.bananatv.source.jar;

import android.content.Context;
import com.github.catvod.crawler.SpiderApi;
import com.github.catvod.net.Net;
import com.github.catvod.utils.Local;
import com.google.gson.JsonArray;
import java.io.ByteArrayInputStream;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONObject;

/** Real DEX field access and local-only network failures. No owner data is used. */
final class JarNetworkProbe {
    private static final SpiderApi CALLBACKS = new SpiderApi() {
        @Override public String getAddress(boolean local) { return "fixture"; }
        @Override public String getPort() { return "0"; }
        @Override public void log(String value) { }
        @Override public String multiReq(JsonArray requests) { throw new UnsupportedOperationException(); }
        @Override public String webParse(String url, String headers) { throw new UnsupportedOperationException(); }
    };

    private static void check(boolean condition, String label, Consumer<String> checks) {
        if (!condition) throw new AssertionError(label);
        checks.accept(label);
    }

    private static JarSession open(Context context, byte[] bytes, String digest, String owner, String base) throws Exception {
        return JarSession.open(context, new ByteArrayInputStream(bytes), digest,
                "fixture.source.CompatibilitySource$Network", () -> null, CALLBACKS, owner, base);
    }

    static void run(Context context, byte[] bytes, String digest, Consumer<String> checks) throws Exception {
        String owner = "jar-network-fixture-" + UUID.randomUUID();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String base = server.url("/").toString();
            try (JarSession session = open(context, bytes, digest, owner, base)) {
                server.enqueue(new MockResponse().setBody("影视"));
                check(session.call(source -> source.homeContent(false)).equals("影视"), "dex_net_field_executes_bounded_http", checks);
                check(server.takeRequest(2, TimeUnit.SECONDS).getPath().equals("/home"), "dex_network_request_reaches_loopback_fixture", checks);
                server.enqueue(new MockResponse().setBody("posted"));
                check(session.call(source -> source.playerContent("post", "{\"fixture\":true}", Collections.emptyList())).equals("posted"),
                        "dex_json_post_dispatch", checks);
                RecordedRequest post = server.takeRequest(2, TimeUnit.SECONDS);
                check(post.getMethod().equals("POST") && post.getBody().readUtf8().equals("{\"fixture\":true}"),
                        "dex_post_preserves_json_payload", checks);
                String value = "{\"title\":\"影视\",\"position\":1234}";
                check(session.call(source -> source.searchContent(value, false)).equals(value), "dex_local_field_stores_json_value", checks);
            }
            try (JarSession restored = open(context, bytes, digest, owner, base);
                    JarSession other = open(context, bytes, digest, owner + "-other", base)) {
                String value = restored.call(source -> source.detailContent(Collections.singletonList("fixture")));
                check(new JSONObject(value).getInt("position") == 1234, "dex_local_values_survive_session_recreation", checks);
                check(other.call(source -> source.detailContent(Collections.singletonList("fixture"))) == null,
                        "dex_local_values_are_source_owned", checks);
                server.enqueue(new MockResponse().setBody("delayed").setBodyDelay(2, TimeUnit.SECONDS));
                HashMap<String, String> options = new HashMap<>(); options.put("timeout", "150");
                boolean timedOut = false;
                try { restored.call(source -> source.categoryContent("slow", "1", false, options)); }
                catch (JarSession.SourceFailure expected) { timedOut = expected.getMessage().equals("Source execution failed"); }
                check(timedOut, "dex_network_deadline_fails_without_source_details", checks);
                if (server.takeRequest(2, TimeUnit.SECONDS) == null) throw new AssertionError("Deadline fixture request did not start");
            }
            sourceCancellation(context, bytes, digest, owner, base, server, checks);
            familyCancellation(server, checks);
            redirectCookies(checks);
            server.enqueue(new MockResponse().setBody("x").setHeader("Content-Length", 32L * 1024 * 1024 + 1));
            try (Net net = new Net()) {
                JSONObject response = new JSONObject(net.req(base + "oversized-private-fixture", "{}"));
                check(response.getString("code").isEmpty() && response.getString("content").isEmpty()
                        && !response.getString("error").contains("oversized-private-fixture"),
                        "jar_http_rejects_oversized_headers_without_leaking_url", checks);
                server.takeRequest(2, TimeUnit.SECONDS);
            }
        } finally {
            new Local(context, owner).clear(); new Local(context, owner + "-other").clear();
        }
    }

    private static void sourceCancellation(Context context, byte[] bytes, String digest, String owner, String base,
            MockWebServer server, Consumer<String> checks) throws Exception {
        AtomicReference<JarSession> active = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean laterRejected = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(1);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Thread worker = new Thread(() -> {
            try (JarSession session = open(context, bytes, digest, owner, base)) {
                active.set(session); ready.countDown();
                try { session.call(source -> source.homeContent(false)); }
                catch (JarSession.SourceFailure expected) { cancelled.set(true); }
                try { session.call(source -> source.homeContent(false)); }
                catch (JarSession.SourceFailure expected) { laterRejected.set(expected.getMessage().equals("Source session was cancelled")); }
            } catch (Throwable error) { unexpected.set(error); ready.countDown(); }
        }, "source-cancellation-fixture");
        worker.start();
        try {
            if (!ready.await(5, TimeUnit.SECONDS) || active.get() == null) throw new AssertionError("Source fixture did not initialize");
            if (server.takeRequest(3, TimeUnit.SECONDS) == null) throw new AssertionError("Source fixture HTTP did not start");
            active.get().cancel();
            worker.join(3000);
            check(!worker.isAlive() && unexpected.get() == null && cancelled.get() && laterRejected.get(),
                    "source_cancel_stops_active_http_and_rejects_future_calls", checks);
        } finally {
            if (active.get() != null) active.get().cancel();
            worker.interrupt(); worker.join(3000);
        }
    }

    private static void familyCancellation(MockWebServer server, Consumer<String> checks) throws Exception {
        try (Net root = new Net(); Net unrelated = new Net()) {
            Net closedChild = root.session(); closedChild.close();
            Net child = root.session();
            Net grandchild = child.session();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean sleepCancelled = new AtomicBoolean();
            CountDownLatch sleeping = new CountDownLatch(1);
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            Thread request = new Thread(() -> {
                try { grandchild.get(server.url("/descendant").toString()); }
                catch (Throwable error) { failure.set(error); }
            }, "descendant-http-fixture");
            Thread sleeper = new Thread(() -> {
                sleeping.countDown();
                try { child.sleep(60000); }
                catch (InterruptedIOException expected) { sleepCancelled.set(true); }
            }, "descendant-sleep-fixture");
            request.start(); sleeper.start();
            try {
                if (server.takeRequest(3, TimeUnit.SECONDS) == null || !sleeping.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Descendant fixture did not start");
                }
                long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (sleeper.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < waitUntil) Thread.sleep(5);
                check(sleeper.getState() == Thread.State.TIMED_WAITING, "descendant_is_waiting_before_source_cancellation", checks);
                root.close(); request.join(3000); sleeper.join(3000);
                check(!request.isAlive() && failure.get() instanceof IllegalStateException && !sleeper.isAlive() && sleepCancelled.get(),
                        "source_lifetime_cancels_descendant_http_and_sleep", checks);
                check(!new JSONObject(grandchild.req(server.url("/closed").toString(), "{}")).getString("error").isEmpty(),
                        "closed_descendant_cannot_start_new_http", checks);
                boolean forkRejected = false;
                try { child.session(); } catch (IllegalStateException expected) { forkRejected = true; }
                check(forkRejected, "closed_source_cannot_create_new_descendants", checks);
                server.enqueue(new MockResponse().setBody("healthy"));
                check(unrelated.get(server.url("/unrelated").toString()).equals("healthy"), "source_cancel_keeps_unrelated_client_usable", checks);
                server.takeRequest(2, TimeUnit.SECONDS);
            } finally {
                root.close(); request.interrupt(); sleeper.interrupt(); request.join(3000); sleeper.join(3000);
            }
        }
    }

    private static void redirectCookies(Consumer<String> checks) throws Exception {
        try (MockWebServer first = new MockWebServer(); MockWebServer second = new MockWebServer()) {
            first.start(); second.start();
            AtomicReference<String> stored = new AtomicReference<>();
            Net.Cookies cookies = new Net.Cookies() {
                @Override public String getCookie(String url) { return url.startsWith(first.url("/").toString()) ? "first=only" : "second=only"; }
                @Override public boolean setCookie(String url, String value) { stored.set(url + " " + value); return true; }
            };
            try (Net net = new Net(cookies)) {
                first.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", second.url("/target")));
                second.enqueue(new MockResponse().setBody("done").addHeader("Set-Cookie", "second=new; Path=/"));
                JSONObject response = new JSONObject(net.req(first.url("/redirect").toString(), "{\"cookie\":true}"));
                check(response.getString("content").equals("done") && first.takeRequest(2, TimeUnit.SECONDS).getHeader("Cookie").equals("first=only")
                        && second.takeRequest(2, TimeUnit.SECONDS).getHeader("Cookie").equals("second=only")
                        && stored.get().startsWith(second.url("/target").toString()),
                        "redirect_cookies_are_routed_to_each_origin", checks);
                first.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", second.url("/explicit")));
                second.enqueue(new MockResponse().setBody("done"));
                net.req(first.url("/explicit").toString(), "{\"headers\":{\"Cookie\":\"private-fixture-cookie\"}}");
                check(first.takeRequest(2, TimeUnit.SECONDS).getHeader("Cookie").equals("private-fixture-cookie")
                        && second.takeRequest(2, TimeUnit.SECONDS).getHeader("Cookie") == null, "explicit_cookie_is_not_forwarded_to_another_origin", checks);
            }
        }
    }
}
