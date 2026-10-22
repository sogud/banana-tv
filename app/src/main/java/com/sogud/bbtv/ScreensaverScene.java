package com.sogud.bbtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared, independently owned scene for Home idle, preview and Android Dream. */
final class ScreensaverScene extends FrameLayout {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final MediaLibrary library;
    private final ImageView photo;
    private final TextView caption;
    private final String mode;
    private VideoWallpaper video;
    private AmbientWallpaperView ambient;
    private AerialPlayer aerial;
    private TextClock clock;
    private boolean running;
    private boolean closed;
    private boolean photoFallback;
    private int imageRequest;
    private int photoIndex;
    private final Random random = new Random();
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (!running || closed) return;
            if (clock != null) moveClock();
            else if (mode.equals("photos") || photoFallback) loadPhoto(true);
            handler.postDelayed(this, 60_000L);
        }
    };

    ScreensaverScene(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        library = new MediaLibrary(context);
        mode = HomeOptions.text(context, "screensaver_mode", "wallpaper");
        photo = new ImageView(context);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setImageResource(WallpaperCatalog.image(context, true));
        addView(photo, new LayoutParams(-1, -1));
        caption = new TextView(context);
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(18);
        caption.setShadowLayer(4, 0, 1, 0xCC000000);
        caption.setPadding(dp(48), 0, dp(48), dp(40));
        LayoutParams label = new LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
        if (mode.equals("aerial")) {
            SurfaceView surface = new SurfaceView(context);
            addView(surface, 0, new LayoutParams(-1, -1));
            aerial = new AerialPlayer(context, surface, new AerialPlayer.Listener() {
                @Override public void caption(String text) { caption.setText(text); }
                @Override public void firstFrame() { photo.setVisibility(GONE); }
                @Override public void unavailable() {
                    photoFallback = true; photo.setVisibility(VISIBLE);
                    loadPhoto(false);
                }
            });
            loadPhoto(false);
        } else if (mode.equals("clock")) {
            photo.setVisibility(GONE);
            clock = new TextClock(context);
            clock.setFormat12Hour("HH:mm"); clock.setFormat24Hour("HH:mm");
            clock.setTextSize(72); clock.setTextColor(0xFFECEFF4);
            clock.setFontFeatureSettings("tnum");
            addView(clock, new LayoutParams(-2, -2));
            post(this::moveClock);
        } else if (mode.equals("photos")) {
            loadPhoto(true);
        } else {
            int wallpaperMode = WallpaperCatalog.mode(context);
            if (!library.selected().equals("legacy")) loadPhoto(false);
            else if (wallpaperMode >= WallpaperCatalog.AMBIENT) {
                photo.setVisibility(GONE);
                ambient = new AmbientWallpaperView(context, null);
                ambient.setStyle(wallpaperMode - WallpaperCatalog.AMBIENT);
                addView(ambient, new LayoutParams(-1, -1));
            } else if (wallpaperMode == WallpaperCatalog.VIDEO) {
                video = new VideoWallpaper(context, null);
                File local = VideoWallpaper.localFile(context, "wallpaper.jpg");
                Bitmap poster = local == null ? BitmapFactory.decodeResource(getResources(), R.drawable.mountain_poster)
                        : MediaLibrary.decode(local, 1920, 1080);
                video.setPoster(poster);
                addView(video, new LayoutParams(-1, -1));
                photo.setVisibility(GONE);
            } else loadPhoto(false);
        }
        addView(caption, label);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void moveClock() {
        if (clock == null || getWidth() == 0) return;
        int margin = dp(48);
        int width = Math.max(clock.getWidth(), dp(280));
        int height = Math.max(clock.getHeight(), dp(100));
        clock.setTranslationX(margin + random.nextInt(Math.max(1, getWidth() - width - margin * 2)));
        clock.setTranslationY(margin + random.nextInt(Math.max(1, getHeight() - height - margin * 2)));
    }

    private void loadPhoto(boolean rotatePhoto) {
        String id = library.selected();
        if (rotatePhoto) {
            List<MediaLibrary.Item> pictures = new ArrayList<>();
            for (MediaLibrary.Item item : library.items()) {
                if (item.id.startsWith("photo:") || item.id.matches("daily:[0-9]{8}")) pictures.add(item);
            }
            if (!pictures.isEmpty()) id = pictures.get(Math.floorMod(photoIndex++, pictures.size())).id;
        }
        final String selected = id;
        final int request = ++imageRequest;
        images.execute(() -> {
            Bitmap bitmap = library.picture(selected, 1920, 1080);
            MediaLibrary.Item item = library.resolve(selected);
            handler.post(() -> {
                if (closed || request != imageRequest) { if (bitmap != null) bitmap.recycle(); return; }
                photo.setImageBitmap(bitmap);
                if (!mode.equals("aerial") || photoFallback) {
                    caption.setText(photoFallback && item.caption.isEmpty()
                            ? getContext().getString(R.string.aerial_unavailable) : item.caption);
                }
                photo.animate().cancel();
                photo.setScaleX(1f); photo.setScaleY(1f);
                if (running && (mode.equals("photos") || photoFallback) && LauncherPreferences.motionEnabled(getContext())) {
                    photo.animate().scaleX(1.08f).scaleY(1.08f).setDuration(60_000L)
                            .setInterpolator(new android.view.animation.LinearInterpolator()).start();
                }
            });
        });
    }

    boolean handleKey(int keyCode, KeyEvent event) {
        if (aerial == null || photoFallback) return false;
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            aerial.step(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
        }
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        closed = true; imageRequest++;
        setRunning(false);
        if (aerial != null) aerial.close();
        images.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        photo.animate().cancel();
        super.onDetachedFromWindow();
    }

    void setRunning(boolean enabled) {
        running = enabled;
        if (video != null) video.setRunning(enabled);
        if (ambient != null) ambient.setRunning(enabled);
        if (aerial != null) aerial.setRunning(enabled);
        handler.removeCallbacks(rotate);
        if (enabled) handler.postDelayed(rotate, 60_000L);
        else photo.animate().cancel();
    }
}
