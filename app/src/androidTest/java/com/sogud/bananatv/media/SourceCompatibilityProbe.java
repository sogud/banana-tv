package com.sogud.bananatv.media;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import static com.sogud.bananatv.media.MediaModels.*;

/** One provider per disposable emulator process; a crashing adapter cannot hide later results. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class SourceCompatibilityProbe {
    private final Instrumentation probe;
    private final Bundle result = new Bundle();
    private Activity playerActivity;
    private Activity hostActivity;
    private fi.iki.elonen.NanoHTTPD foreignProxy;
    private final java.util.concurrent.atomic.AtomicInteger foreignRequests = new java.util.concurrent.atomic.AtomicInteger();
    private final List<String> foreignEvents = java.util.Collections.synchronizedList(new ArrayList<>());

    private SourceCompatibilityProbe(Instrumentation probe) { this.probe = probe; }

    static void run(Instrumentation probe, Bundle args, String base) {
        new SourceCompatibilityProbe(probe).run(args, base);
    }

    private void stage(String value) {
        result.putString("stage", value);
        Bundle status = new Bundle(); status.putString("stage", value); probe.sendStatus(0, status);
    }

    private void run(Bundle args, String base) {
        try {
            if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish"))
                throw new IllegalStateException("Emulator required");
            String address = args.getString("source", "");
            if (!address.startsWith(base + "/")) throw new IllegalArgumentException("Loopback configuration required");
            MediaEngine engine = MediaEngine.get(probe.getTargetContext());
            stage("import"); engine.importSource(address);
            Site site = engine.site(args.getString("site"));
            result.putString("site", site.key);
            engine.autoRefresh(false);
            engine.enableSites(java.util.Collections.emptySet());
            stage("host");
            hostActivity = probe.startActivitySync(new Intent(probe.getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (args.getString("occupied_proxy") != null) {
                foreignProxy = new fi.iki.elonen.NanoHTTPD("127.0.0.1", 9978) {
                    @Override public Response serve(IHTTPSession session) {
                        if ("ck".equals(session.getParms().get("do"))) return newFixedLengthResponse("ok");
                        foreignEvents.add(session.getUri() + ":" + session.getParms().get("do") + ":" + session.getParms().get("type"));
                        foreignRequests.incrementAndGet();
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "");
                    }
                };
                foreignProxy.start();
            }
            List<Movie> candidates = new ArrayList<>();
            stage("home");
            Page home;
            try { home = engine.home(site); }
            catch (Exception error) {
                result.putString("home_error", describe(engine, error));
                home = new Page(new JSONObject());
            }
            result.putInt("categories", home.categories.size());
            result.putInt("home_movies", home.movies.size());
            candidates.addAll(home.movies);
            if (!home.categories.isEmpty() && args.getString("skip_category") == null) {
                stage("category");
                try {
                    Page category = engine.category(site, home.categories.get(0).id, 1);
                    result.putInt("category_movies", category.movies.size());
                    if (candidates.isEmpty()) candidates.addAll(category.movies);
                } catch (Exception error) { result.putString("category_error", error.getClass().getSimpleName()); }
            }
            if (site.searchable) {
                stage("search");
                try {
                    Page search = engine.search(site, args.getString("word", "流浪地球"), 1);
                    result.putInt("search_movies", search.movies.size());
                    if (!search.movies.isEmpty()) candidates.addAll(0, search.movies);
                } catch (Exception error) { result.putString("search_error", error.getClass().getSimpleName()); }
            }
            if (candidates.isEmpty()) throw new IllegalStateException("No playable candidates returned");
            candidates = leaves(engine, site, candidates);
            Exception last = new IllegalStateException("No episodes returned");
            org.json.JSONArray failures = new org.json.JSONArray();
            int attempts = Math.min(3, candidates.size());
            for (int i = 0; i < attempts; i++) {
                stage("detail");
                Movie detail;
                try { detail = engine.detail(site, candidates.get(i).id); }
                catch (Exception error) { last = error; continue; }
                result.putInt("lines", detail.lines.size());
                for (int line = 0; line < Math.min(3, detail.lines.size()); line++) {
                    try {
                        stage("resolve");
                        JSONObject stream = engine.resolve(site, detail, line, 0);
                        result.putString("stream_host", android.net.Uri.parse(stream.optString("url")).getHost());
                        result.putInt("stream_port", android.net.Uri.parse(stream.optString("url")).getPort());
                        result.putInt("host_proxy_port", com.github.catvod.Proxy.getPort());
                        stage("playback"); play(site, detail, line);
                        result.putInt("foreign_proxy_requests", foreignRequests.get());
                        result.putString("foreign_proxy_events", foreignEvents.toString());
                        if (foreignRequests.get() != 0) throw new IllegalStateException("Media reached another application's proxy");
                        result.putString("result", "PASS");
                        probe.finish(Activity.RESULT_OK, result); return;
                    } catch (Exception error) {
                        last = error;
                        failures.put(new JSONObject().put("candidate", i).put("line", line)
                                .put("stage", result.getString("stage")).put("error", describe(engine, error)));
                        result.putString("attempts", failures.toString());
                    } finally { closePlayer(); }
                }
            }
            throw last;
        } catch (Throwable error) {
            result.putString("foreign_proxy_events", foreignEvents.toString());
            result.putString("result", "FAIL");
            result.putString("error", error.getClass().getSimpleName());
            result.putString("message", String.valueOf(error.getMessage()));
            Throwable cause = error.getCause();
            if (cause != null) result.putString("cause", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            probe.finish(Activity.RESULT_CANCELED, result);
        } finally {
            closePlayer();
            if (hostActivity != null) probe.runOnMainSync(hostActivity::finish);
            if (foreignProxy != null) foreignProxy.stop();
        }
    }

    private String describe(MediaEngine engine, Exception error) {
        if (error instanceof MediaEngine.Failure) return engine.error(error);
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
    private static final class Node {
        final Movie movie;
        final int depth;
        Node(Movie movie, int depth) { this.movie = movie; this.depth = depth; }
    }
    private List<Movie> leaves(MediaEngine engine, Site site, List<Movie> entries) throws Exception {
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        for (Movie movie : entries) queue.add(new Node(movie, 0));
        java.util.Set<String> visited = new java.util.HashSet<>();
        List<Movie> movies = new ArrayList<>();
        int walked = 0;
        while (!queue.isEmpty() && walked++ < 50 && movies.size() < 6) {
            Node node = queue.remove();
            if (!node.movie.folder) { movies.add(node.movie); continue; }
            if (node.depth >= 3 || !visited.add(node.movie.id)) continue;
            stage("folder");
            try {
                for (Movie child : engine.category(site, node.movie.id, 1).movies) queue.add(new Node(child, node.depth + 1));
            } catch (Exception error) { result.putString("folder_error", describe(engine, error)); }
        }
        result.putInt("folders", visited.size());
        return movies;
    }

    private void closePlayer() {
        if (playerActivity != null) {
            Activity current = playerActivity; playerActivity = null;
            probe.runOnMainSync(current::finish);
            probe.waitForIdleSync();
        }
    }

    private void play(Site site, Movie movie, int line) throws Exception {
        playerActivity = probe.startActivitySync(new Intent(probe.getTargetContext(), MediaPlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("site", site.key).putExtra("id", movie.id)
                .putExtra("line", line).putExtra("episode", 0));
        Field field = MediaPlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
        long[] position = {0}, start = {0};
        int[] frames = {0}, audio = {0}, playing = {0};
        String[] failure = {null};
        Runnable sample = () -> {
            try {
                ExoPlayer player = (ExoPlayer) field.get(playerActivity);
                if (player == null) return;
                if (player.getPlayerError() != null) failure[0] = player.getPlayerError().getErrorCodeName();
                position[0] = player.getCurrentPosition();
                if (player.isPlaying()) playing[0]++;
                DecoderCounters video = player.getVideoDecoderCounters(), sound = player.getAudioDecoderCounters();
                if (video != null) { video.ensureUpdated(); frames[0] = video.renderedOutputBufferCount; }
                if (sound != null) { sound.ensureUpdated(); audio[0] = sound.renderedOutputBufferCount; }
            } catch (IllegalAccessException error) { failure[0] = error.getClass().getSimpleName(); }
        };
        for (int i = 0; i < 80 && position[0] < 2000 && failure[0] == null; i++) {
            Thread.sleep(250); probe.runOnMainSync(sample);
        }
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
        if (position[0] < 2000) throw new IllegalStateException("Playback did not start in 20 seconds");
        probe.runOnMainSync(() -> {
            try {
                ExoPlayer player = (ExoPlayer) field.get(playerActivity);
                if (player.getDuration() > 90000) player.seekTo(60000);
            } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        });
        playing[0] = 0;
        for (int i = 0; i < 30 && failure[0] == null; i++) {
            Thread.sleep(500); probe.runOnMainSync(sample);
            if (start[0] == 0 && playing[0] > 0) start[0] = position[0];
        }
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
        if (playing[0] < 16 || position[0] - start[0] < 7000 || audio[0] < 60)
            throw new IllegalStateException("No sustained decoded audio/video after seek");
        result.putString("media", frames[0] > 60 ? "video" : "audio");
        result.putString("title", movie.title);
        result.putInt("video_frames", frames[0]); result.putInt("audio_buffers", audio[0]);
        result.putInt("playing_samples", playing[0]); result.putLong("position_ms", position[0]);
        probe.runOnMainSync(() -> {
            try {
                ExoPlayer player = (ExoPlayer) field.get(playerActivity);
                result.putInt("video_width", player.getVideoSize().width);
                result.putInt("video_height", player.getVideoSize().height);
            } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        });
        android.graphics.Bitmap image = probe.getUiAutomation().takeScreenshot();
        if (image != null) try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                new java.io.File(probe.getTargetContext().getCacheDir(), "source-audit-player.jpg"))) {
            image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, output);
            image.recycle();
        }
    }
}
