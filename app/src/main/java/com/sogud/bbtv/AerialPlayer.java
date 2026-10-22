package com.sogud.bbtv;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Media3 streaming and bounded opener cache, adapted from Aerial's MIT implementation. */
@OptIn(markerClass = UnstableApi.class)
final class AerialPlayer {
    interface Listener { void caption(String text); void firstFrame(); void unavailable(); }
    private static SimpleCache sharedCache;
    private static final long HEAD = 8L * 1024 * 1024;
    private static final long TAIL = 1024 * 1024;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService prefetch = Executors.newSingleThreadExecutor();
    private final List<AerialCatalog.Film> films;
    private final ExoPlayer player;
    private final Listener listener;
    private final SimpleCache cache;
    private Future<?> pending;
    private volatile CacheWriter writer;
    private boolean running;
    private boolean closed;
    private boolean rendered;
    private int failures;
    private long waitingSince;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running || closed) return;
            int index = player.getCurrentMediaItemIndex();
            if (index >= 0 && index < films.size()) listener.caption(films.get(index).caption(player.getCurrentPosition()));
            if (waitingSince != 0 && SystemClock.elapsedRealtime() - waitingSince > 8000) fail();
            handler.postDelayed(this, 1000);
        }
    };

    private static synchronized SimpleCache cache(Context context) {
        if (sharedCache == null) sharedCache = new SimpleCache(new File(context.getCacheDir(), "aerial-openers"),
                new LeastRecentlyUsedCacheEvictor(48L * 1024 * 1024), new StandaloneDatabaseProvider(context));
        return sharedCache;
    }

    private static DefaultHttpDataSource.Factory http() {
        return new DefaultHttpDataSource.Factory().setConnectTimeoutMs(5000).setReadTimeoutMs(8000);
    }

    AerialPlayer(Context context, SurfaceView surface, Listener listener) {
        this.listener = listener;
        films = AerialCatalog.load(context);
        Collections.shuffle(films);
        cache = cache(context.getApplicationContext());
        for (int i = 0; i < films.size(); i++) if (ready(films.get(i))) { Collections.swap(films, 0, i); break; }
        CacheDataSource.Factory source = new CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory(http()).setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        player = new ExoPlayer.Builder(context)
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 1000, 2000)
                        .setTargetBufferBytes(24 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(source)
                        .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(1))).build();
        player.setVolume(0);
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
        player.setVideoSurfaceView(surface);
        List<MediaItem> playlist = new ArrayList<>();
        for (AerialCatalog.Film film : films) playlist.add(new MediaItem.Builder().setUri(film.url)
                .setMediaId(film.id).setCustomCacheKey(film.id).build());
        player.setMediaItems(playlist);
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                rendered = true; waitingSince = 0; failures = 0; listener.firstFrame(); prepareNext();
            }
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                rendered = false; waitingSince = SystemClock.elapsedRealtime();
            }
            @Override public void onPlayerError(PlaybackException error) { fail(); }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING && waitingSince == 0) waitingSince = SystemClock.elapsedRealtime();
                if (state == Player.STATE_READY && rendered) waitingSince = 0;
            }
        });
    }

    void setRunning(boolean enabled) {
        if (closed) return;
        running = enabled;
        player.setPlayWhenReady(enabled);
        handler.removeCallbacks(tick);
        if (enabled) {
            if (films.isEmpty()) { listener.unavailable(); return; }
            if (player.getPlaybackState() == Player.STATE_IDLE) { waitingSince = SystemClock.elapsedRealtime(); player.prepare(); }
            handler.post(tick);
        }
    }

    void step(int direction) {
        if (closed || films.isEmpty()) return;
        rendered = false;
        player.seekToDefaultPosition(Math.floorMod(player.getCurrentMediaItemIndex() + direction, films.size()));
        waitingSince = SystemClock.elapsedRealtime();
        player.prepare();
        player.setPlayWhenReady(running);
    }

    private void fail() {
        if (closed) return;
        if (++failures >= 3) { setRunning(false); listener.unavailable(); }
        else step(1);
    }

    private boolean ready(AerialCatalog.Film film) {
        long length = ContentMetadata.getContentLength(cache.getContentMetadata(film.id));
        return length >= HEAD + TAIL && cache.isCached(film.id, 0, HEAD) && cache.isCached(film.id, length - TAIL, TAIL);
    }

    private void prepareNext() {
        if (pending != null && !pending.isDone() || films.size() < 2) return;
        AerialCatalog.Film next = films.get((player.getCurrentMediaItemIndex() + 1) % films.size());
        if (ready(next)) return;
        pending = prefetch.submit(() -> {
            DefaultHttpDataSource probe = http().createDataSource();
            try {
                probe.open(new DataSpec.Builder().setUri(next.url).setLength(1).build());
                long length = -1;
                for (java.util.Map.Entry<String, List<String>> entry : probe.getResponseHeaders().entrySet()) {
                    if (entry.getKey().equalsIgnoreCase("Content-Range") && !entry.getValue().isEmpty()) {
                        String range = entry.getValue().get(0);
                        length = Long.parseLong(range.substring(range.lastIndexOf('/') + 1));
                    }
                }
                probe.close();
                if (length < HEAD + TAIL) return;
                ContentMetadataMutations changes = new ContentMetadataMutations();
                ContentMetadataMutations.setContentLength(changes, length);
                cache.applyContentMetadataMutations(next.id, changes);
                long[] positions = {0, length - TAIL};
                long[] sizes = {HEAD, TAIL};
                for (int i = 0; i < positions.length && !Thread.currentThread().isInterrupted(); i++) {
                    CacheDataSource.Factory target = new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http());
                    writer = new CacheWriter(target.createDataSource(), new DataSpec.Builder().setUri(Uri.parse(next.url))
                            .setKey(next.id).setPosition(positions[i]).setLength(sizes[i]).build(), null, null);
                    writer.cache();
                }
            } catch (Exception ignored) { /* Playback falls back without changing transport security. */ }
            finally { try { probe.close(); } catch (Exception ignored) { } writer = null; }
        });
    }

    void close() {
        if (closed) return;
        closed = true; running = false;
        handler.removeCallbacksAndMessages(null);
        CacheWriter active = writer;
        if (active != null) active.cancel();
        if (pending != null) pending.cancel(true);
        prefetch.shutdownNow();
        player.release();
    }
}
