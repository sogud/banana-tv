package com.sogud.bananatv;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.File;
import java.io.IOException;

/** A muted local video, decoded only while its owner is visible. */
public final class VideoWallpaper extends FrameLayout implements SurfaceHolder.Callback {
    // Some TV codecs block during release. Serialize player work away from remote input.
    private static final Handler decoder;
    static {
        HandlerThread thread = new HandlerThread("banana-video");
        thread.start();
        decoder = new Handler(thread.getLooper());
    }

    private MediaPlayer player;
    private final SurfaceView video;
    private final ImageView poster;
    private boolean posterReady;
    private volatile boolean running;
    private volatile int generation;
    private int videoWidth;
    private int videoHeight;

    public VideoWallpaper(Context context, AttributeSet attributes) {
        super(context, attributes);
        video = new SurfaceView(context);
        video.getHolder().addCallback(this);
        addView(video, new LayoutParams(-1, -1, Gravity.CENTER));
        poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(poster, new LayoutParams(-1, -1));
        setVisibility(GONE);
    }

    void setPoster(Bitmap bitmap) {
        poster.setImageBitmap(bitmap);
        posterReady = bitmap != null;
        setRunning(running);
    }

    public void setRunning(boolean running) {
        if (this.running != running) poster.setVisibility(VISIBLE);
        this.running = running;
        setVisibility(running && posterReady ? VISIBLE : GONE);
        Surface surface = video.getHolder().getSurface();
        if (running && posterReady && surface.isValid()) {
            int request = generation;
            decoder.post(() -> startPlayer(surface, request));
        } else {
            generation++;
            decoder.post(this::releasePlayer);
        }
    }

    static File localFile(Context context, String name) {
        File directory = context.getExternalFilesDir("wallpapers");
        if (directory == null) return null;
        File file = new File(directory, name);
        return file.isFile() ? file : null;
    }

    private void startPlayer(Surface surface, int request) {
        if (player != null || !running || request != generation) return;
        MediaPlayer next = new MediaPlayer();
        player = next;
        next.setOnPreparedListener(prepared -> {
            if (prepared != player || !running || request != generation) return;
            int width = prepared.getVideoWidth();
            int height = prepared.getVideoHeight();
            post(() -> {
                if (request != generation) return;
                videoWidth = width;
                videoHeight = height;
                updateCrop();
            });
            prepared.start();
        });
        next.setOnInfoListener((prepared, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                post(() -> { if (running && request == generation) poster.setVisibility(INVISIBLE); });
            }
            return false;
        });
        next.setOnErrorListener((failed, what, extra) -> {
            if (failed != player) return true;
            releasePlayer();
            post(() -> { if (request == generation) poster.setVisibility(VISIBLE); });
            return true;
        });
        try {
            next.setSurface(surface);
            next.setVolume(0, 0);
            next.setLooping(true);
            File local = localFile(getContext(), "wallpaper.mp4");
            if (local != null) next.setDataSource(local.getAbsolutePath());
            else {
                try (AssetFileDescriptor file = getResources().openRawResourceFd(R.raw.mountain_loop)) {
                    next.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
                }
            }
            next.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException error) {
            releasePlayer();
        }
    }

    private void updateCrop() {
        if (videoWidth == 0 || videoHeight == 0 || getWidth() == 0 || getHeight() == 0) return;
        float scale = Math.max((float) getWidth() / videoWidth, (float) getHeight() / videoHeight);
        video.setLayoutParams(new LayoutParams(Math.round(videoWidth * scale),
                Math.round(videoHeight * scale), Gravity.CENTER));
        video.getHolder().setFixedSize(videoWidth, videoHeight);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        int request = generation;
        Surface surface = holder.getSurface();
        decoder.post(() -> startPlayer(surface, request));
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        generation++;
        poster.setVisibility(VISIBLE);
        decoder.post(this::releasePlayer);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateCrop();
    }

    @Override
    protected void onDetachedFromWindow() {
        setRunning(false);
        super.onDetachedFromWindow();
    }
}
