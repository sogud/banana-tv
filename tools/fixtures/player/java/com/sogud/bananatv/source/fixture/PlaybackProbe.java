package com.sogud.bananatv.source.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Uses the app's pinned Media3 version; URL/header inputs and player errors remain private. */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackProbe {
    public static JSONObject run(Instrumentation instrumentation, JSONObject resolved) throws Exception {
        Activity activity = instrumentation.startActivitySync(new Intent(instrumentation.getContext(), PlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<ExoPlayer> current = new AtomicReference<>();
        JSONObject result = new JSONObject(); CountDownLatch finished = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper()); AtomicReference<Runnable> poll = new AtomicReference<>();
        try {
            Map<String, String> headers = new HashMap<>();
            Object raw = resolved.opt("header");
            JSONObject values = raw instanceof JSONObject ? (JSONObject) raw : raw instanceof String && ((String) raw).startsWith("{") ? new JSONObject((String) raw) : new JSONObject();
            Iterator<String> names = values.keys(); while (names.hasNext()) { String name = names.next(); headers.put(name, values.getString(name)); }
            String address = resolved.getString("url");
            instrumentation.runOnMainSync(() -> {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                PlayerView view = new PlayerView(activity); activity.setContentView(view);
                DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setConnectTimeoutMs(8000)
                        .setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers);
                ExoPlayer player = new ExoPlayer.Builder(activity).setMediaSourceFactory(new DefaultMediaSourceFactory(http)).build();
                current.set(player); view.setPlayer(player); player.setVolume(0);
                player.addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException error) {
                        try { result.put("errorCode", error.getErrorCodeName()); } catch (org.json.JSONException ignored) { }
                        finished.countDown();
                    }
                    @Override public void onRenderedFirstFrame() {
                        try { result.put("firstFrame", true); } catch (org.json.JSONException ignored) { }
                    }
                });
                Runnable progress = new Runnable() {
                    @Override public void run() {
                        if (finished.getCount() == 0) return;
                        DecoderCounters counters = player.getVideoDecoderCounters();
                        if (counters != null) counters.ensureUpdated();
                        if (result.optBoolean("firstFrame") && counters != null && counters.renderedOutputBufferCount > 0 && player.getCurrentPosition() >= 1500) {
                            try {
                                result.put("renderedFrames", counters.renderedOutputBufferCount);
                                result.put("positionAdvanced", true); result.put("decoded", true);
                                if (player.getVideoFormat() != null) result.put("width", player.getVideoFormat().width).put("height", player.getVideoFormat().height);
                            } catch (org.json.JSONException ignored) { }
                            finished.countDown(); return;
                        }
                        main.postDelayed(this, 100);
                    }
                };
                poll.set(progress);
                MediaItem.Builder item = new MediaItem.Builder().setUri(address);
                String format = resolved.optString("format").toLowerCase(java.util.Locale.ROOT);
                if (format.equals("m3u8") || format.equals("hls") || format.equals("application/x-mpegurl") || format.equals("application/vnd.apple.mpegurl")) item.setMimeType("application/x-mpegURL");
                if (format.equals("mpd") || format.equals("dash") || format.equals("application/dash+xml")) item.setMimeType("application/dash+xml");
                player.setMediaItem(item.build()); player.prepare(); player.play(); progress.run();
            });
            boolean completed = finished.await(25, TimeUnit.SECONDS);
            if (!completed) instrumentation.runOnMainSync(() -> {
                try {
                    ExoPlayer player = current.get(); result.put("timeout", true).put("state", player.getPlaybackState())
                            .put("positionMs", player.getCurrentPosition()).put("bufferedMs", player.getBufferedPosition());
                } catch (org.json.JSONException ignored) { }
            });
            return result;
        } finally {
            instrumentation.runOnMainSync(() -> {
                Runnable progress = poll.get(); if (progress != null) main.removeCallbacks(progress);
                ExoPlayer player = current.get(); if (player != null) player.release(); activity.finish();
            });
        }
    }
}
