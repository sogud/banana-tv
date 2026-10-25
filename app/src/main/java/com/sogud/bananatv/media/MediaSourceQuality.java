package com.sogud.bananatv.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.SystemClock;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Local, configuration/network-specific observations; no URL or account data is stored. */
final class MediaSourceQuality {
    private static final long FRESH = TimeUnit.MINUTES.toMillis(10);
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().callTimeout(1800, TimeUnit.MILLISECONDS).build();
    private final SharedPreferences preferences;
    private final Context context;
    private final MediaEngine engine;
    MediaSourceQuality(Context context, MediaEngine engine) {
        this.context = context.getApplicationContext(); this.engine = engine;
        preferences = context.getSharedPreferences("media_quality", Context.MODE_PRIVATE);
    }
    boolean automatic() { return preferences.getBoolean("automatic", true); }
    void automatic(boolean value) { preferences.edit().putBoolean("automatic", value).apply(); }
    private String key(MediaModels.Site site, String line) {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = connectivity == null ? null : connectivity.getActiveNetwork();
        String identity = engine.catalogKey(site, "quality", line) + ":" + (network == null ? "offline" : network.getNetworkHandle());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private JSONObject read(MediaModels.Site site, String line) {
        try { return new JSONObject(preferences.getString(key(site, line), "{}")); }
        catch (org.json.JSONException error) { return new JSONObject(); }
    }
    synchronized long score(MediaModels.Site site, String line) {
        JSONObject item = read(site, line); long now = System.currentTimeMillis();
        if (now - item.optLong("checked") > TimeUnit.HOURS.toMillis(24)) return 5000;
        long failure = now - item.optLong("failed") < TimeUnit.MINUTES.toMillis(5) ? 1000000 : 0;
        return failure + item.optLong("probe", 3000) + item.optLong("frame", 1000) * 2 + item.optLong("buffer", 0);
    }
    synchronized void observation(MediaModels.Site site, String line, String field, long millis) {
        try {
            JSONObject item = read(site, line);
            long previous = item.optLong(field, millis);
            item.put(field, field.equals("failed") ? millis : (previous + millis) / 2).put("checked", System.currentTimeMillis());
            if (field.equals("frame")) item.remove("failed");
            preferences.edit().putString(key(site, line), item.toString()).apply();
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    void probe(MediaModels.Site site, String line, JSONObject stream, long budgetMs) throws Exception {
        JSONObject item = read(site, line); long now = System.currentTimeMillis();
        if (now - item.optLong("probed") < FRESH) return;
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.min(2500, budgetMs);
        String url = stream.getString("url"); JSONObject headers = stream.optJSONObject("header");
        // At most two playlists and one media sample, 64 KiB each; never download a whole film.
        for (int depth = 0; depth < 3; depth++) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            Request.Builder request = new Request.Builder().url(url).header("Range", "bytes=0-65535");
            if (headers != null) {
                java.util.Iterator<String> names = headers.keys();
                while (names.hasNext()) { String name = names.next(); if (!name.equalsIgnoreCase("Range")) request.header(name, headers.getString(name)); }
            }
            byte[] bytes;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new java.net.SocketTimeoutException();
            okhttp3.Call call = HTTP.newCall(request.build()); call.timeout().timeout(Math.min(1800, remaining), TimeUnit.MILLISECONDS);
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new java.io.IOException("Probe failed");
                java.io.ByteArrayOutputStream sample = new java.io.ByteArrayOutputStream();
                java.io.InputStream input = response.body().byteStream(); byte[] buffer = new byte[8192];
                while (sample.size() < 65536) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, 65536 - sample.size()));
                    if (count < 0) break;
                    sample.write(buffer, 0, count);
                }
                bytes = sample.toByteArray();
            }
            if (bytes.length == 0) throw new java.io.IOException("Empty media sample");
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("#EXTM3U")) {
                String lower = text.substring(0, Math.min(40, text.length())).toLowerCase(Locale.ROOT);
                if (lower.startsWith("<html") || lower.startsWith("<!doctype")) throw new java.io.IOException("HTML is not media");
                long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
                observation(site, line, "probe", elapsed);
                synchronized (this) {
                    JSONObject saved = read(site, line); saved.put("probed", now);
                    preferences.edit().putString(key(site, line), saved.toString()).apply();
                }
                return;
            }
            String next = "";
            for (String entry : text.split("\n")) if (!entry.trim().isEmpty() && !entry.trim().startsWith("#")) { next = entry.trim(); break; }
            if (next.isEmpty()) throw new java.io.IOException("Empty playlist");
            url = new URI(url).resolve(next).toString();
            if (!url.startsWith("http://") && !url.startsWith("https://")) throw new java.io.IOException("Unsupported media scheme");
        }
        throw new java.io.IOException("Playlist nesting limit");
    }
}
