package com.sogud.bananatv.source.jar;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import com.github.catvod.crawler.SpiderApi;
import com.google.gson.JsonArray;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** End-to-end DEX checks in a disposable APK with no media-core dependency. */
public final class JarProbe extends Instrumentation {
    private static final String ENTRY = "fixture.source.CompatibilitySource";
    private int checks;
    private boolean pinned;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args); pinned = args != null && "qist".equals(args.getString("reference")); start();
    }

    private void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
        Bundle status = new Bundle(); status.putString("check", label); sendStatus(0, status);
    }

    private JarSession open(byte[] bytes, String digest, String entry, AtomicReference<Activity> activity,
            SpiderApi callbacks, String extension) throws Exception {
        return JarSession.open(getContext(), new ByteArrayInputStream(bytes), digest, entry,
                activity::get, callbacks, "fixture-owner", extension);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity screen = null;
        try {
            if (pinned) {
                com.github.catvod.Runtime.set(getContext());
                org.json.JSONObject report = PinnedJarProbe.run(this);
                org.json.JSONArray sources = report.getJSONArray("sources");
                boolean passed = sources.length() > 0;
                for (int index = 0; index < sources.length(); index++) {
                    org.json.JSONObject source = sources.getJSONObject(index);
                    passed &= source.optString("stage").equals("complete") && source.optInt("categories") > 0
                            && source.optInt("items") > 0 && source.optJSONObject("playback") != null
                            && source.getJSONObject("playback").optBoolean("decoded");
                    if (source.has("searchItems")) passed &= source.getInt("searchItems") > 0;
                }
                result.putString("report", report.toString()); result.putString("result", passed ? "PASS" : "PARTIAL");
                finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result); return;
            }
            byte[] bytes;
            try (InputStream input = getContext().getAssets().open("source-fixture.jar");
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                bytes = output.toByteArray();
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", value & 255));
            String digest = hash.toString();
            screen = startActivitySync(new Intent().setClassName(getContext(), FixtureActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AtomicReference<Activity> activity = new AtomicReference<>(screen);
            AtomicInteger destroyed = new AtomicInteger();
            AtomicInteger callbacksCalled = new AtomicInteger();
            SpiderApi callbacks = new SpiderApi() {
                @Override public String getAddress(boolean local) { return local ? "loopback-fixture" : "remote-fixture"; }
                @Override public String getPort() { return "0"; }
                @Override public void log(String message) {
                    if (message.equals("fixture-destroy")) destroyed.incrementAndGet();
                }
                @Override public String multiReq(JsonArray requests) { throw new UnsupportedOperationException(); }
                @Override public String webParse(String url, String headers) {
                    if (!url.equals("fixture-url") || !headers.equals("fixture-headers")) throw new AssertionError();
                    callbacksCalled.incrementAndGet();
                    return "same-java-callback";
                }
            };
            com.github.catvod.Runtime.set(getContext());
            com.github.catvod.Runtime.setActivity(activity::get);
            try (JarSession session = open(bytes, digest, ENTRY, activity, callbacks, "fixture-extension")) {
                String homeText = session.call(source -> source.homeContent(true));
                JSONObject home = new JSONObject(homeText);
                require(home.getBoolean("initialized") && home.getString("extension").equals("fixture-extension")
                        && home.getString("source").equals("fixture-owner"), "dex_source_initialization_and_owner");
                require(home.getInt("activity") == System.identityHashCode(screen), "dex_source_receives_real_activity_identity");
                require(home.getString("callback").equals("same-java-callback") && callbacksCalled.get() == 1,
                        "dex_source_calls_same_process_java_callback");
                require(session.call(source -> source.getClass().getClassLoader()) != getClass().getClassLoader(),
                        "source_entry_loaded_from_separate_dex");
                HashMap<String, String> options = new HashMap<>(); options.put("sort", "latest");
                String categoryText = session.call(source -> source.categoryContent("movies", "2", true, options));
                JSONObject category = new JSONObject(categoryText);
                require(category.getString("id").equals("movies") && category.getString("page").equals("2")
                        && category.getBoolean("filter") && category.getString("sort").equals("latest"), "dex_category_preserves_arguments");
                require(session.call(source -> source.detailContent(Collections.singletonList("film-1"))).equals("film-1")
                        && session.call(source -> source.searchContent("影视", false, "3")).equals("影视")
                        && session.call(source -> source.playerContent("main", "episode-2", Collections.emptyList())).equals("main:episode-2"),
                        "dex_detail_search_and_player_dispatch");
                Object[] proxy = session.call(source -> source.proxy(Collections.singletonMap("id", "clip")));
                require(proxy[0].equals(200) && proxy[2].equals("clip"), "dex_proxy_objects_remain_in_process");
                activity.set(null);
                String detachedText = session.call(source -> source.homeContent(false));
                require(new JSONObject(detachedText).getInt("activity") == 0,
                        "activity_provider_does_not_retain_previous_screen");
                activity.set(screen);
                AtomicReference<Throwable> error = new AtomicReference<>();
                Thread foreign = new Thread(() -> {
                    try { session.call(source -> source.homeContent(true)); } catch (Throwable failure) { error.set(failure); }
                });
                foreign.start(); foreign.join();
                require(error.get() instanceof IllegalStateException, "foreign_thread_rejected_before_source_execution");
                session.close(); session.close();
                boolean rejected = false;
                try { session.call(source -> source.homeContent(true)); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected && destroyed.get() == 1, "close_once_and_reject_use_after_close");
            }
            try (JarSession session = open(bytes, digest, ENTRY + "$Fallback", activity, callbacks, "")) {
                require(session.call(source -> source.searchContent("query", false, "2")).equals("true:query"),
                        "legacy_init_and_search_overloads_dispatch_to_source_override");
                boolean rejected = false;
                try { session.call(source -> source.homeContent(true)); }
                catch (JarSession.SourceFailure expected) { rejected = expected.getMessage().equals("Source execution failed"); }
                require(rejected, "unsupported_required_operation_fails_explicitly");
            }
            File directory = new File(getContext().getCodeCacheDir(), "source-jars");
            int baseline = directory.list().length;
            boolean rejected = false;
            try { open(bytes, "0000000000000000000000000000000000000000000000000000000000000000", ENTRY, activity, callbacks, ""); }
            catch (JarSession.SourceFailure expected) { rejected = expected.getMessage().equals("Source digest mismatch"); }
            require(rejected && directory.list().length == baseline, "digest_mismatch_rejected_and_private_copy_removed");
            rejected = false;
            try { open(bytes, digest, ENTRY, activity, callbacks, "init-failure"); }
            catch (JarSession.SourceFailure expected) { rejected = expected.getMessage().equals("Source initialization failed"); }
            require(rejected && directory.list().length == baseline && destroyed.get() == 2,
                    "init_failure_redacted_destroyed_and_artifact_removed");
            JarSession failingClose = open(bytes, digest, ENTRY, activity, callbacks, "close-failure");
            rejected = false;
            try { failingClose.close(); }
            catch (JarSession.SourceFailure expected) { rejected = expected.getMessage().equals("Source cleanup failed"); }
            failingClose.close();
            require(rejected && directory.list().length == baseline && destroyed.get() == 3,
                    "destroy_failure_redacted_without_leaking_artifact");
            rejected = false;
            try { open(bytes, digest, ENTRY, activity, callbacks, "init-assertion"); }
            catch (JarSession.SourceFailure expected) { rejected = expected.getMessage().equals("Source initialization failed"); }
            require(rejected, "init_assertion_is_redacted");
            require(directory.list().length == baseline, "init_assertion_private_artifact_removed");
            require(destroyed.get() == 4, "init_assertion_destroy_runs_once_count_" + destroyed.get());
            JarSession assertionClose = open(bytes, digest, ENTRY, activity, callbacks, "close-assertion");
            rejected = false;
            try { assertionClose.close(); }
            catch (JarSession.SourceFailure expected) { rejected = expected.getMessage().equals("Source cleanup failed"); }
            assertionClose.close();
            require(rejected && directory.list().length == baseline && destroyed.get() == 5,
                    "destroy_assertion_redacted_and_artifact_still_removed");
            JarNetworkProbe.run(getContext(), bytes, digest, label -> require(true, label));
            try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
                server.start();
                server.enqueue(new okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(new okhttp3.WebSocketListener() {
                    @Override public void onMessage(okhttp3.WebSocket socket, okio.ByteString data) { socket.send(data); }
                }));
                try (JarSession session = JarSession.open(getContext(), new ByteArrayInputStream(bytes), digest,
                        ENTRY + "$Socket", activity::get, callbacks, "socket-fixture-owner", server.url("/socket").toString())) {
                    String text = session.call(source -> source.homeContent(false));
                    JSONObject value = new JSONObject(text);
                    require(value.getBoolean("binary") && value.getJSONArray("bytes").getInt(2) == -3 && value.isNull("error"),
                            "dex_websocket_send_and_event_fields_match_legacy_abi");
                }
            }
            com.github.catvod.net.SocketProbe.run(label -> require(true, label));
            com.github.catvod.net.AsyncProbe.run(label -> require(true, label));
            com.github.catvod.net.StreamProbe.run(getContext(), label -> require(true, label));
            result.putString("result", "PASS"); result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", failure.toString());
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("fixture_trace", trace.toString());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            com.github.catvod.Runtime.setActivity(null);
            com.github.catvod.Runtime.setToast(null);
            com.github.catvod.Runtime.set(null);
            if (screen != null) {
                Activity closing = screen;
                runOnMainSync(closing::finish);
            }
        }
    }
}
