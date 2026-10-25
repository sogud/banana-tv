package com.sogud.bananatv.media;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DecoderCounters;
import java.lang.reflect.Field;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.sogud.bananatv.media.MediaModels.*;

/** Explicit opt-in TV test: existing owner configuration, no source import or system changes. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class LiveTvPlaybackProbe extends Instrumentation {
    private Bundle args;
    private Activity activity;
    private String injectedQualityKey;
    private long injectedAt;
    private void removeInjectedFailure(String key, long after) throws Exception {
        android.content.SharedPreferences preferences = getTargetContext().getSharedPreferences("media_quality", 0);
        JSONObject entry = new JSONObject(preferences.getString(key, "{}"));
        long failed = entry.optLong("failed");
        if (failed >= after && failed <= System.currentTimeMillis()) {
            entry.remove("failed"); preferences.edit().putString(key, entry.toString()).commit();
            results.putBoolean("injected_failure_removed", true);
        }
    }
    private String qualityKey(MediaEngine engine, Site site, String line) throws Exception {
        java.lang.reflect.Method method = MediaSourceQuality.class.getDeclaredMethod("key", Site.class, String.class);
        method.setAccessible(true); return (String) method.invoke(new MediaSourceQuality(getTargetContext(), engine), site, line);
    }
    private final Bundle results = new Bundle();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    private Object field(String name) throws Exception {
        Field field = MediaPlayerActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        Bundle status = new Bundle(); status.putString("check", name); sendStatus(0, status);
    }
    private void stage(String name) {
        Bundle status = new Bundle(); status.putString("stage", name); sendStatus(0, status);
    }
    private Site owner(MediaEngine engine, String name) throws Exception {
        for (Site site : engine.enabledSites()) if (site.name.equals(name)) return site;
        throw new IllegalStateException("Configured provider missing");
    }
    private Movie find(MediaEngine engine, Site site, String title, String year) throws Exception {
        for (Movie movie : engine.search(site, title, 1).movies) {
            if (movie.title.trim().equals(title) && movie.year.equals(year)) return movie;
        }
        throw new IllegalStateException("Matching title/year unavailable");
    }
    private ExoPlayer player() throws Exception { return (ExoPlayer) field("player"); }
    private long position() throws Exception {
        long[] position = {0}; runOnMainSync(() -> {
            try { position[0] = player().getCurrentPosition(); } catch (Exception error) { throw new RuntimeException(error); }
        }); return position[0];
    }
    private boolean framed() throws Exception {
        boolean[] ready = {false}; runOnMainSync(() -> {
            try {
                ExoPlayer player = player();
                DecoderCounters counters = player == null ? null : player.getVideoDecoderCounters();
                if (counters != null) counters.ensureUpdated();
                ready[0] = player != null && player.getPlaybackState() == Player.STATE_READY
                        && counters != null && counters.renderedOutputBufferCount > 0;
            } catch (Exception error) { throw new RuntimeException(error); }
        }); return ready[0];
    }
    private long awaitPlayback() throws Exception {
        long started = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - started < 90000) {
            if (framed()) return SystemClock.elapsedRealtime() - started;
            Thread.sleep(200);
        }
        throw new AssertionError("No decoded playback within 90 seconds");
    }
    private MediaPanel panel() throws Exception { return (MediaPanel) field("recoveryPanel"); }
    private void awaitPanel() throws Exception {
        long started = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - started < 60000) {
            if (panel() != null && panel().isShowing()) return;
            Thread.sleep(200);
        }
        throw new AssertionError("Source confirmation missing");
    }
    private TextView text(View root, String value) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(value)) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = text(group.getChildAt(i), value); if (found != null) return found;
            }
        }
        return null;
    }
    private void click(String label) throws Exception {
        runOnMainSync(() -> {
            try {
                View view = text(panel().getWindow().getDecorView(), label);
                if (view == null) throw new AssertionError("Confirmation option missing");
                while (!view.isClickable()) view = (View) view.getParent();
                check(view.performClick(), "confirm_action_" + label);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
    private void failCurrent(long resume) throws Exception {
        runOnMainSync(() -> {
            try {
                ExoPlayer player = player();
                player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:9/banana-tv-connection-failure.m3u8"));
                player.seekTo(resume); player.prepare(); player.play();
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        awaitPanel();
    }
    @Override public void onStart() {
        try {
            if (!"true".equals(args.getString("live_tv"))) throw new IllegalArgumentException("Explicit live_tv opt-in required");
            stage("existing_configuration");
            MediaEngine engine = MediaEngine.get(getTargetContext()); engine.load();
            if ("true".equals(args.getString("cleanup_injected_failure"))) {
                long after = Long.parseLong(args.getString("cleanup_after"));
                check(after > 0 && after <= System.currentTimeMillis(), "cleanup_window_valid");
                removeInjectedFailure(qualityKey(engine, owner(engine, args.getString("first", "量子")),
                        args.getString("line", "lzm3u8")), after);
                check(results.getBoolean("injected_failure_removed"), "injected_test_failure_removed");
                results.putString("result", "PASS"); return;
            }
            String title = args.getString("title", "JOJO的奇妙冒险 不灭钻石");
            String year = args.getString("year", "2016");
            Site first = owner(engine, args.getString("first", "量子"));
            Site second = owner(engine, args.getString("second", "非凡"));
            Movie a = find(engine, first, title, year), b = find(engine, second, title, year);
            JSONArray references = new JSONArray().put(new JSONObject().put("site", first.key).put("id", a.id))
                    .put(new JSONObject().put("site", second.key).put("id", b.id));
            check(new MediaSourceQuality(getTargetContext(), engine).automatic(), "automatic_selection_enabled");
            stage("automatic_start"); long started = SystemClock.elapsedRealtime();
            activity = startActivitySync(new Intent(getTargetContext(), MediaPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("site", first.key).putExtra("id", a.id).putExtra("line", 0).putExtra("episode", 0)
                    .putExtra("position", 0L).putExtra("sources", references.toString()));
            awaitPlayback(); results.putLong("automatic_click_to_decoded_ms", SystemClock.elapsedRealtime() - started);
            Site selected = (Site) field("site"); results.putString("selected_provider", selected.name);
            stage("continuous_playback");
            long initial = position(), sampleStart = SystemClock.elapsedRealtime(); int buffered = 0, samples = 0;
            while (SystemClock.elapsedRealtime() - sampleStart < 60000) {
                boolean[] buffering = {false}; runOnMainSync(() -> {
                    try { buffering[0] = player().getPlaybackState() == Player.STATE_BUFFERING; }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                if (buffering[0]) buffered++; samples++; Thread.sleep(1000);
            }
            long before = position(); results.putLong("playback_progress_ms", before - initial);
            results.putInt("buffering_samples", buffered); results.putInt("playback_samples", samples);
            check(before - initial > 50000, "one_minute_progress");
            injectedQualityKey = qualityKey(engine, selected, ((Movie) field("movie")).lines.get((Integer) field("line")).name);
            injectedAt = System.currentTimeMillis();
            stage("connection_failure"); failCurrent(before);
            check(((Site) field("site")).key.equals(selected.key), "failure_does_not_switch_without_confirmation");
            results.putLong("failure_saved_position_ms", (Long) field("position"));
            check(Math.abs((Long) field("position") - before) < 1500, "failure_keeps_progress");
            click(getTargetContext().getString(com.sogud.bananatv.R.string.media_stay));
            check(((Site) field("site")).key.equals(selected.key), "cancel_keeps_provider");
            sendKeySync(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP));
            sendKeySync(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_UP));
            waitForIdleSync();
            runOnMainSync(() -> {
                View button = text(activity.getWindow().getDecorView(), getTargetContext().getString(com.sogud.bananatv.R.string.media_switch_source));
                check(button != null && button.isShown(), "remote_up_reopens_controls_after_cancel");
                check(button.performClick(), "player_switch_button_opens_confirmation");
            });
            awaitPanel();
            Site replacement = selected.key.equals(first.key) ? second : first;
            stage("confirmed_switch"); started = SystemClock.elapsedRealtime(); click(replacement.name + " ·");
            awaitPlayback(); results.putLong("confirmed_switch_to_decoded_ms", SystemClock.elapsedRealtime() - started);
            check(((Site) field("site")).key.equals(replacement.key), "confirmed_provider_changed");
            long after = position(); results.putLong("resumed_position_ms", after);
            check(after >= before - 1500 && after < before + 10000, "confirmed_switch_restores_progress");
            Thread.sleep(10000); check(position() > after + 7000, "replacement_continues_playback");
            results.putString("result", "PASS");
        } catch (Throwable error) {
            results.putString("result", "FAIL"); results.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (injectedQualityKey != null) {
                try { removeInjectedFailure(injectedQualityKey, injectedAt); }
                catch (Exception error) { results.putString("result", "FAIL"); results.putString("cleanup_error", error.getClass().getSimpleName()); }
            }
            if (activity != null) runOnMainSync(() -> activity.finish());
            getTargetContext().startActivity(new Intent(getTargetContext(), com.sogud.bananatv.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish("PASS".equals(results.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, results);
        }
    }
}
