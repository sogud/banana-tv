package com.sogud.bbtv;

import android.content.Context;
import android.widget.FrameLayout;
import java.io.File;

/** Shared scene for the preview, idle screen and Android's screen saver service. */
final class ScreensaverScene extends FrameLayout {
    private final VideoWallpaper video;
    private final AmbientWallpaperView ambient;

    ScreensaverScene(Context context) {
        super(context);
        setBackgroundColor(android.graphics.Color.BLACK);
        int mode = WallpaperCatalog.mode(context);
        if (mode >= WallpaperCatalog.AMBIENT) {
            ambient = new AmbientWallpaperView(context, null);
            ambient.setStyle(mode - WallpaperCatalog.AMBIENT);
            addView(ambient, new LayoutParams(-1, -1));
            video = null;
            return;
        }
        ambient = null;
        video = new VideoWallpaper(context, null);
        File local = VideoWallpaper.localFile(context, "wallpaper.jpg");
        if (local != null) video.setPoster(android.graphics.BitmapFactory.decodeFile(local.getAbsolutePath()));
        else video.setPoster(android.graphics.BitmapFactory.decodeResource(context.getResources(), R.drawable.mountain_poster));
        addView(video, new LayoutParams(-1, -1));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void setRunning(boolean running) {
        if (video != null) video.setRunning(running);
        if (ambient != null) ambient.setRunning(running);
    }
}
