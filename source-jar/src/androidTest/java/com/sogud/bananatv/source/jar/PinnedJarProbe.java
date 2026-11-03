package com.sogud.bananatv.source.jar;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderApi;
import com.github.catvod.utils.Local;
import com.google.gson.JsonArray;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional pinned third-party input; reports structure and missing symbols, never media URLs. */
final class PinnedJarProbe {
    private static byte[] read(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (output.size() + count > 32 * 1024 * 1024) throw new AssertionError("Pinned input exceeds limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static final String SHA256 = "5fd276c06ca4152f3136374b82fc0a2161d7659981841846d2fbb5326d3fef47";
    static JSONObject run(android.app.Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getContext();
        byte[] bytes;
        try (InputStream input = context.getAssets().open("pinned-source.jar")) { bytes = read(input); }
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", value & 255));
        if (!hash.toString().equals(SHA256)) throw new AssertionError("Pinned input digest mismatch");
        ClassLoader parent = Spider.class.getClassLoader();
        Class<?> init = parent.loadClass("com.github.catvod.spider.Init");
        init.getMethod("configure", Context.class, Supplier.class).invoke(null, context, (Supplier<android.app.Activity>) () -> null);
        {
            JSONArray inputs;
            try (InputStream input = context.getAssets().open("pinned-sources.json")) {
                inputs = new JSONArray(new String(read(input), StandardCharsets.UTF_8));
            }
            JSONArray results = new JSONArray();
            for (int index = 0; index < inputs.length(); index++) {
                JSONObject item = inputs.getJSONObject(index), result = new JSONObject();
                results.put(result); String entry = item.getString("entry"); result.put("entry", entry);
                JarSession session = null;
                String sourceId = "pinned-probe-" + UUID.randomUUID(); Local storage = new Local(context, sourceId);
                try {
                    result.put("stage", "init");
                    session = JarSession.open(context, new ByteArrayInputStream(bytes), SHA256, entry, () -> null,
                            new SpiderApi() {
                        @Override public String getAddress(boolean local) { return "http://127.0.0.1:1"; }
                        @Override public String getPort() { return "1"; }
                        @Override public void log(String message) { }
                        @Override public String multiReq(JsonArray requests) { throw new UnsupportedOperationException("Batch host callback not configured"); }
                        @Override public String webParse(String url, String headers) { throw new UnsupportedOperationException("Web parser not configured"); }
                    }, sourceId, item.getString("extension"));
                    JarSession active = session;
                    result.put("stage", "home"); JSONObject home = new JSONObject(active.<String>call(source -> source.homeContent(true)));
                    JSONArray classes = home.optJSONArray("class"); result.put("categories", classes == null ? 0 : classes.length());
                    if (classes == null || classes.length() == 0) continue;
                    result.put("stage", "category");
                    String category = classes.getJSONObject(0).getString("type_id");
                    JSONObject page = new JSONObject(active.<String>call(source -> source.categoryContent(category, "1", true, new HashMap<>())));
                    JSONArray list = page.optJSONArray("list"); result.put("items", list == null ? 0 : list.length());
                    if (list == null || list.length() == 0) continue;
                    JSONObject first = list.getJSONObject(0); String id = first.getString("vod_id");
                    if (item.optBoolean("searchable", true)) {
                        result.put("stage", "search");
                        String word = first.optString("vod_name").replaceAll("<[^>]*>", "").trim();
                        JSONObject search = new JSONObject(active.<String>call(source -> source.searchContent(word, false, "1")));
                        result.put("searchItems", search.optJSONArray("list") == null ? 0 : search.getJSONArray("list").length());
                    }
                    result.put("stage", "detail"); JSONObject detail = new JSONObject(active.<String>call(source -> source.detailContent(Collections.singletonList(id))));
                    JSONObject video = detail.getJSONArray("list").getJSONObject(0);
                    String from = video.optString("vod_play_from").split(java.util.regex.Pattern.quote("$$$"))[0];
                    String episodes = video.optString("vod_play_url").split(java.util.regex.Pattern.quote("$$$"))[0];
                    String episode = episodes.split("#")[0]; int separator = episode.indexOf('$');
                    if (separator >= 0) episode = episode.substring(separator + 1);
                    result.put("episodes", !episode.isEmpty());
                    result.put("stage", "resolve"); String selectedEpisode = episode;
                    JSONObject play = new JSONObject(active.<String>call(source -> source.playerContent(from, selectedEpisode, Collections.emptyList())));
                    result.put("urlPresent", !play.optString("url").isEmpty()); result.put("parse", play.optInt("parse", -1));
                    if (play.optInt("parse", -1) == 0 && !play.optString("url").isEmpty()) {
                        result.put("stage", "playback"); result.put("playback", com.sogud.bananatv.source.fixture.PlaybackProbe.run(instrumentation, play));
                    }
                    result.put("stage", "complete");
                } catch (Throwable error) {
                    Throwable cause = error;
                    while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
                    result.put("errorType", cause.getClass().getSimpleName());
                    JSONArray frames = new JSONArray();
                    for (StackTraceElement frame : cause.getStackTrace()) {
                        if (frames.length() >= 6) break;
                        frames.put(frame.getClassName() + "." + frame.getMethodName());
                    }
                    result.put("frames", frames);
                    if (cause instanceof LinkageError && cause.getMessage() != null) {
                        java.util.regex.Matcher symbols = java.util.regex.Pattern.compile("L[a-zA-Z0-9_/$]+;").matcher(cause.getMessage());
                        JSONArray owners = new JSONArray(); while (symbols.find()) owners.put(symbols.group()); result.put("missingOwners", owners);
                        java.util.regex.Matcher method = java.util.regex.Pattern.compile("(?:method|field) ([a-zA-Z0-9_$]+)").matcher(cause.getMessage());
                        if (method.find()) result.put("missingMember", method.group(1));
                    }
                } finally {
                    try { if (session != null) session.close(); }
                    finally { storage.clear(); }
                }
            }
            return new JSONObject().put("sha256", SHA256).put("sources", results);
        }
    }
}
