package com.sogud.bananatv.media;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import com.sogud.bananatv.R;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.sogud.bananatv.media.MediaModels.*;
import static com.sogud.bananatv.media.MediaViews.*;

@OptIn(markerClass = UnstableApi.class)
public final class MediaPlayerActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaEngine engine;
    private PlayerView video;
    private TextView status;
    private TextView provider;
    private TextView feedback;
    private final Runnable hideFeedback = () -> feedback.setVisibility(android.view.View.GONE);
    private ExoPlayer player;
    private Site site;
    private Movie movie;
    private String siteKey, movieId;
    private int line, episode, generation;
    private long position;
    private boolean active;
    private MediaPanel linePanel;
    private MediaPanel episodePanel;
    private MediaPanel recoveryPanel;
    private MediaSourceQuality quality;
    private final java.util.Set<String> failedCandidates = new java.util.HashSet<>();
    private boolean selectFastest;
    private boolean recovering;
    private long playbackStarted, bufferingStarted;
    private boolean frameRecorded, seeking;
    private final Runnable stalled = () -> playbackFailed();
    private final Runnable save = new Runnable() {
        @Override public void run() { remember(); if (active) main.postDelayed(this, 5000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        siteKey = getIntent().getStringExtra("site"); movieId = getIntent().getStringExtra("id");
        line = state == null ? getIntent().getIntExtra("line", 0) : state.getInt("line");
        episode = state == null ? getIntent().getIntExtra("episode", 0) : state.getInt("episode");
        position = state == null ? getIntent().getLongExtra("position", 0) : state.getLong("position");
        engine = MediaEngine.get(this); quality = new MediaSourceQuality(this, engine);
        try {
            org.json.JSONArray references = new org.json.JSONArray(getIntent().getStringExtra("sources") == null ? "[]" : getIntent().getStringExtra("sources"));
            org.json.JSONArray ordered = new org.json.JSONArray().put(new JSONObject().put("site", siteKey).put("id", movieId));
            for (int i = 0; i < references.length(); i++) if (!references.getJSONObject(i).optString("site").equals(siteKey))
                ordered.put(references.getJSONObject(i));
            getIntent().putExtra("sources", ordered.toString());
        } catch (org.json.JSONException error) { getIntent().putExtra("sources", "[]"); }
        if (state != null) { siteKey = state.getString("site", siteKey); movieId = state.getString("id", movieId); }
        selectFastest = state == null && position == 0 && quality.automatic();
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xff000000);
        video = new PlayerView(this); video.setKeepScreenOn(true); video.setControllerAutoShow(false); video.setControllerAnimationEnabled(false);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = row(this); top.setPadding(dp(this, 28), dp(this, 16), dp(this, 28), dp(this, 12)); top.setBackgroundColor(0xb010141d);
        status = text(this, getString(R.string.media_loading), 20, 0xfff4f5f8);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        provider = text(this, "", 14, 0xffb7bdc8); top.addView(provider);
        addButton(this, top, getString(R.string.media_previous_episode), () -> step(-1));
        addButton(this, top, getString(R.string.media_next_episode), () -> step(1));
        addButton(this, top, getString(R.string.media_episodes), this::chooseEpisode);
        addButton(this, top, getString(R.string.media_lines), this::chooseLine);
        addButton(this, top, getString(R.string.media_switch_source), () -> chooseSource(false));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); root.addView(top, params);
        feedback = text(this, "", 18, 0xfff4f5f8);
        feedback.setPadding(dp(this, 20), dp(this, 10), dp(this, 20), dp(this, 10));
        feedback.setBackground(shape(0xd9181b22, dp(this, 14))); feedback.setVisibility(android.view.View.GONE);
        FrameLayout.LayoutParams feedbackPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        feedbackPosition.bottomMargin = dp(this, 70); root.addView(feedback, feedbackPosition);
        video.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            top.setVisibility(visibility);
            if (visibility == android.view.View.VISIBLE) feedback.setVisibility(android.view.View.GONE);
        });
        setContentView(root); video.requestFocus();
    }
    @Override protected void onStart() {
        super.onStart(); active = true; MediaEngine.foreground(this); load(); main.postDelayed(save, 5000);
    }
    private void load() {
        final int ticket = ++generation;
        final String requestedSite = siteKey, requestedMovie = movieId;
        final int requestedLine = line, requestedEpisode = episode;
        final boolean automatic = selectFastest; selectFastest = false;
        recovering = false;
        status.setText(automatic ? R.string.media_checking_sources : R.string.media_loading);
        worker.execute(() -> {
            try {
                engine.load(); Site owner = engine.site(requestedSite); Movie item = engine.detail(owner, requestedMovie);
                if (requestedLine < 0 || requestedLine >= item.lines.size() || requestedEpisode < 0 || requestedEpisode >= item.lines.get(requestedLine).episodes.size())
                    throw new MediaEngine.Failure(R.string.media_unplayable);
                main.post(() -> { if (active && ticket == generation) { site = owner; movie = item; } });
                java.util.List<MediaPlaybackSources.Candidate> choices = MediaPlaybackSources.candidates(engine, owner, item,
                        requestedLine, requestedEpisode, automatic ? getIntent().getStringExtra("sources") : "[]");
                MediaPlaybackSources.Candidate selected = choices.get(0);
                if (automatic) {
                    choices.sort(java.util.Comparator.comparingLong(candidate -> quality.score(candidate.site, candidate.movie.lines.get(candidate.line).name)));
                    java.util.List<MediaPlaybackSources.Candidate> checked = new java.util.ArrayList<>();
                    long probeDeadline = android.os.SystemClock.elapsedRealtime() + 4000;
                    for (MediaPlaybackSources.Candidate candidate : choices.subList(0, Math.min(3, choices.size()))) {
                        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                        long remaining = probeDeadline - android.os.SystemClock.elapsedRealtime();
                        if (remaining <= 0) break;
                        try {
                            candidate.stream = engine.resolve(candidate.site, candidate.movie, candidate.line, candidate.episode);
                            quality.probe(candidate.site, candidate.movie.lines.get(candidate.line).name, candidate.stream,
                                    Math.max(1, probeDeadline - android.os.SystemClock.elapsedRealtime()));
                            checked.add(candidate);
                        } catch (Exception | LinkageError ignored) {
                            quality.observation(candidate.site, candidate.movie.lines.get(candidate.line).name, "failed", System.currentTimeMillis());
                        }
                    }
                    if (!checked.isEmpty()) selected = java.util.Collections.min(checked,
                            java.util.Comparator.comparingLong(candidate -> quality.score(candidate.site, candidate.movie.lines.get(candidate.line).name)));
                }
                if (selected.stream == null) selected.stream = engine.resolve(selected.site, selected.movie, selected.line, selected.episode);
                MediaPlaybackSources.Candidate chosen = selected;
                main.post(() -> {
                    if (!active || ticket != generation) return;
                    adopt(chosen); start(chosen.stream);
                });
            } catch (Exception | LinkageError error) {
                main.post(() -> { if (active && ticket == generation) { status.setText(engine.error(error)); video.showController(); playbackFailed(); } });
            }
        });
    }
    private void adopt(MediaPlaybackSources.Candidate candidate) {
        site = candidate.site; movie = candidate.movie; siteKey = site.key; movieId = movie.id;
        line = candidate.line; episode = candidate.episode;
    }
    private void playbackFailed() { chooseSource(true); }
    private void chooseSource(boolean failed) {
        if (!active || recovering || recoveryPanel != null) return;
        remember(); main.removeCallbacks(stalled);
        if (player != null) player.pause();
        if (movie == null || site == null) { status.setText(R.string.media_playback_failed); video.showController(); return; }
        String current = new MediaPlaybackSources.Candidate(site, movie, line, episode).identity();
        if (failed) {
            failedCandidates.add(current);
            quality.observation(site, movie.lines.get(line).name, "failed", System.currentTimeMillis());
        }
        java.util.Set<String> excluded = new java.util.HashSet<>(failedCandidates); excluded.add(current);
        recovering = true; status.setText(R.string.media_finding_source); video.showController();
        final int ticket = ++generation;
        final Site originalSite = site; final Movie originalMovie = movie; final int originalLine = line, originalEpisode = episode;
        worker.execute(() -> {
            java.util.List<MediaPlaybackSources.Candidate> alternatives = new java.util.ArrayList<>();
            try {
                for (MediaPlaybackSources.Candidate candidate : MediaPlaybackSources.candidates(engine, originalSite, originalMovie,
                        originalLine, originalEpisode, getIntent().getStringExtra("sources"))) {
                    if (!excluded.contains(candidate.identity())) alternatives.add(candidate);
                }
                alternatives.sort(java.util.Comparator.comparingLong(candidate -> quality.score(candidate.site, candidate.movie.lines.get(candidate.line).name)));
            } catch (Exception | LinkageError ignored) { }
            main.post(() -> {
                if (!active || ticket != generation) return;
                recovering = false;
                MediaPanel panel = new MediaPanel(this, getString(R.string.media_switch_source),
                        getString(R.string.media_switch_source_description, time(position)));
                recoveryPanel = panel;
                if (alternatives.isEmpty()) panel.content(text(this, getString(R.string.media_no_alternative), 18, 0xffb7bdc8));
                for (MediaPlaybackSources.Candidate candidate : alternatives) panel.choice(candidate.label(), false, () -> {
                    panel.dismiss(); release(); adopt(candidate); selectFastest = false; load();
                });
                panel.action(getString(R.string.media_stay), panel::dismiss);
                panel.action(getString(R.string.media_retry), () -> { panel.dismiss(); release(); selectFastest = false; load(); });
                panel.setOnDismissListener(ignored -> { if (recoveryPanel == panel) recoveryPanel = null; }); panel.show();
            });
        });
    }
    private void start(JSONObject source) {
        release();
        Map<String, String> headers = new HashMap<>(); JSONObject object = source.optJSONObject("header");
        if (object != null) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String key = keys.next(); headers.put(key, object.optString(key)); }
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setConnectTimeoutMs(8000).setReadTimeoutMs(15000).setDefaultRequestProperties(headers);
        player = new ExoPlayer.Builder(this).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http)).build();
        video.setPlayer(player);
        String title = movie.title + "  " + movie.lines.get(line).episodes.get(episode).name;
        status.setText(title); provider.setText(site.name);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) { status.setText(R.string.media_playback_failed); playbackFailed(); }
            @Override public void onRenderedFirstFrame() {
                if (!frameRecorded) {
                    frameRecorded = true;
                    quality.observation(site, movie.lines.get(line).name, "frame", android.os.SystemClock.elapsedRealtime() - playbackStarted);
                }
                failedCandidates.remove(new MediaPlaybackSources.Candidate(site, movie, line, episode).identity());
            }
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                main.removeCallbacks(stalled);
                if (ready && player.getPlaybackState() == Player.STATE_BUFFERING && !recovering) {
                    if (bufferingStarted == 0) bufferingStarted = android.os.SystemClock.elapsedRealtime();
                    main.postDelayed(stalled, 15000);
                }
            }
            @Override public void onPlaybackStateChanged(int state) {
                main.removeCallbacks(stalled);
                if (state == Player.STATE_BUFFERING && player.getPlayWhenReady()) {
                    bufferingStarted = android.os.SystemClock.elapsedRealtime(); main.postDelayed(stalled, 15000);
                } else if (state == Player.STATE_READY && bufferingStarted > 0) {
                    if (!seeking) quality.observation(site, movie.lines.get(line).name, "buffer", android.os.SystemClock.elapsedRealtime() - bufferingStarted);
                    bufferingStarted = 0; seeking = false;
                }
                if (state == Player.STATE_ENDED) step(1);
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(source.optString("url"));
        String format = source.optString("format").toLowerCase(java.util.Locale.ROOT);
        if (format.equals("m3u8") || format.equals("hls") || format.equals("application/x-mpegurl") || format.equals("application/vnd.apple.mpegurl"))
            item.setMimeType("application/x-mpegURL");
        if (format.equals("mpd") || format.equals("dash") || format.equals("application/dash+xml")) item.setMimeType("application/dash+xml");
        playbackStarted = android.os.SystemClock.elapsedRealtime(); bufferingStarted = 0; frameRecorded = false; seeking = false;
        player.setMediaItem(item.build()); player.seekTo(Math.max(0, position)); player.prepare(); player.play(); video.hideController(); video.requestFocus();
    }
    private void step(int delta) {
        if (movie == null) return;
        int next = episode + delta;
        if (next < 0 || next >= movie.lines.get(line).episodes.size()) return;
        remember(); release(); episode = next; position = 0; load();
    }
    private void chooseEpisode() {
        if (movie == null) return;
        MediaPanel panel = new MediaPanel(this, getString(R.string.media_episodes), null); episodePanel = panel;
        java.util.List<Episode> episodes = movie.lines.get(line).episodes;
        for (int i = 0; i < episodes.size(); i++) {
            int selected = i;
            panel.choice(episodes.get(i).name, selected == episode, () -> {
                panel.dismiss();
                if (selected == episode) return;
                remember(); release(); episode = selected; position = 0; load();
            });
        }
        panel.setOnDismissListener(ignored -> { if (episodePanel == panel) episodePanel = null; }); panel.show();
    }
    private void seek(long delta) {
        if (player == null || !player.isCurrentMediaItemSeekable()) return;
        long target = Math.max(0, player.getCurrentPosition() + delta);
        long duration = player.getDuration();
        // Seeking at the end should not accidentally select the next episode.
        if (duration > 0) target = Math.min(target, Math.max(0, duration - 1000));
        seeking = true; player.seekTo(target);
        showFeedback(time(target) + " / " + time(duration));
    }
    private static String time(long millis) {
        if (millis < 0) return "--:--";
        long seconds = millis / 1000;
        if (seconds >= 3600) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private void showFeedback(String value) {
        feedback.setText(value); feedback.setVisibility(android.view.View.VISIBLE);
        main.removeCallbacks(hideFeedback); main.postDelayed(hideFeedback, 1500);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean hidden = !video.isControllerFullyVisible();
        boolean toggle = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || (hidden && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER));
        boolean backward = code == KeyEvent.KEYCODE_MEDIA_REWIND || (hidden && code == KeyEvent.KEYCODE_DPAD_LEFT);
        boolean forward = code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || (hidden && code == KeyEvent.KEYCODE_DPAD_RIGHT);
        if (toggle || backward || forward || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && player != null) {
                if (backward || forward) seek(backward ? -10000 : 10000);
                else if (event.getRepeatCount() == 0) {
                    if (code == KeyEvent.KEYCODE_MEDIA_PLAY) player.play();
                    else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE || player.getPlayWhenReady()) player.pause();
                    else player.play();
                    showFeedback(getString(player.getPlayWhenReady() ? R.string.media_playing : R.string.media_paused)
                            + " · " + time(player.getCurrentPosition()));
                }
            }
            return true;
        }
        if (hidden && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MENU)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) video.showController();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (video.isControllerFullyVisible()) { video.hideController(); video.requestFocus(); return; }
        super.onBackPressed();
    }
    private void chooseLine() {
        if (movie == null) return;
        MediaPanel panel = new MediaPanel(this, getString(R.string.media_lines), null); linePanel = panel;
        for (int i = 0; i < movie.lines.size(); i++) {
            int selected = i;
            panel.choice(movie.lines.get(i).name, selected == line, () -> {
                panel.dismiss(); if (selected == line) return;
                int matching = MediaPlaybackSources.matchEpisode(movie.lines.get(line), episode, movie.lines.get(selected),
                        MediaCategories.kind(movie.type).equals("movie"));
                if (matching < 0) { showFeedback(getString(R.string.media_episode_mismatch)); return; }
                remember(); release(); line = selected; episode = matching; load();
            });
        }
        panel.setOnDismissListener(ignored -> { if (linePanel == panel) linePanel = null; }); panel.show();
    }
    private void remember() {
        if (player == null || movie == null || site == null) return;
        if (player.getPlaybackState() == Player.STATE_IDLE) return;
        position = player.getCurrentPosition();
        engine.remember(site, movie, line, episode, position, Math.max(0, player.getDuration()));
    }
    private void release() {
        if (player == null) return;
        main.removeCallbacks(stalled); video.setPlayer(null); player.release(); player = null;
    }
    @Override protected void onStop() {
        if (linePanel != null) linePanel.dismiss();
        if (episodePanel != null) episodePanel.dismiss();
        if (recoveryPanel != null) recoveryPanel.dismiss(); main.removeCallbacks(stalled);
        active = false; generation++; main.removeCallbacks(save); main.removeCallbacks(hideFeedback); remember(); release(); super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        remember(); state.putString("site", siteKey); state.putString("id", movieId); state.putInt("line", line); state.putInt("episode", episode); state.putLong("position", position); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        worker.shutdownNow(); main.removeCallbacksAndMessages(null); release(); super.onDestroy();
    }
}
