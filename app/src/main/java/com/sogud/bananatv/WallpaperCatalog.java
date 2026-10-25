package com.sogud.bananatv;

import android.content.Context;

/** Modes preserve the original four still slots and the video slot at index four. */
final class WallpaperCatalog {
    static final int STILL = 0;
    static final int VIDEO = 1;
    static final int AMBIENT = 2;

    static int image(Context context, boolean thumbnail) {
        android.content.res.TypedArray images = context.getResources().obtainTypedArray(
                thumbnail ? R.array.wallpaper_thumbnails : R.array.wallpaper_images);
        int index = LauncherPreferences.wallpaper(context);
        int resource = images.getResourceId(index >= 0 && index < images.length() ? index : 0, 0);
        images.recycle();
        return resource;
    }

    static int mode(Context context) {
        int[] modes = context.getResources().getIntArray(R.array.wallpaper_modes);
        int index = LauncherPreferences.wallpaper(context);
        return index >= 0 && index < modes.length ? modes[index] : STILL;
    }
}
