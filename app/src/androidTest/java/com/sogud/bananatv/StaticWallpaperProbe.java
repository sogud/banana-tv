package com.sogud.bananatv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import java.lang.reflect.Method;

/** Static backgrounds exercised through the real Home activity, on an isolated emulator. */
public final class StaticWallpaperProbe {
    public static void run(Instrumentation probe) throws Exception {
        android.content.Context context = probe.getTargetContext();
        if (!context.getPackageName().endsWith(".mediatest")) throw new IllegalStateException("Fixture only");
        LauncherPreferences.setWallpaper(context, 0);
        Activity home = probe.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Method load = MainActivity.class.getDeclaredMethod("loadWallpaper"); load.setAccessible(true);
        int[] modes = context.getResources().getIntArray(R.array.wallpaper_modes);
        try {
            for (int pass = 0; pass < 2; pass++) for (int index = 0; index < modes.length; index++) {
                if (modes[index] != WallpaperCatalog.STILL) continue;
                int choice = index;
                probe.runOnMainSync(() -> {
                    LauncherPreferences.setWallpaper(context, choice);
                    try { java.lang.reflect.Field ready = MainActivity.class.getDeclaredField("wallpaperReady"); ready.setAccessible(true); ready.setBoolean(home, false); load.invoke(home); } catch (Exception error) { throw new AssertionError(error); }
                });
                final boolean[] visible = {false};
                for (int retry = 0; retry < 100 && !visible[0]; retry++) {
                    probe.runOnMainSync(() -> {
                        ImageView view = home.findViewById(R.id.wallpaper);
                        if (!(view.getDrawable() instanceof BitmapDrawable)) return;
                        Bitmap bitmap = ((BitmapDrawable) view.getDrawable()).getBitmap();
                        // Request completion is checked as well, so a previous wallpaper cannot pass.
                        try {
                            java.lang.reflect.Field ready = MainActivity.class.getDeclaredField("wallpaperReady"); ready.setAccessible(true);
                            visible[0] = ready.getBoolean(home) && bitmap != null && !bitmap.isRecycled() && view.isShown();
                        } catch (Exception e) { throw new AssertionError(e); }
                    });
                    Thread.sleep(100);
                }
                if (!visible[0]) throw new AssertionError("Static wallpaper did not display: " + index);
                probe.waitForIdleSync();
                Bitmap screen = probe.getUiAutomation().takeScreenshot();
                if (screen == null) throw new AssertionError("No rendered frame: " + index);
                try {
                    java.util.Set<Integer> colors = new java.util.HashSet<>(); int brightest = 0;
                    for (int y = 1; y < 7; y++) for (int x = 1; x < 12; x++) {
                        int color = screen.getPixel(screen.getWidth() * x / 12, screen.getHeight() * y / 9);
                        colors.add(color);
                        brightest = Math.max(brightest, Math.max(android.graphics.Color.red(color), Math.max(android.graphics.Color.green(color), android.graphics.Color.blue(color))));
                    }
                    if (colors.size() < 12 || brightest < 40) throw new AssertionError("Blank rendered wallpaper: " + index);
                    if (pass == 1 && index >= 18) {
                        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(context.getCacheDir(), "static-wallpaper-" + index + ".png"))) {
                            screen.compress(Bitmap.CompressFormat.PNG, 100, output);
                        }
                    }
                } finally { screen.recycle(); }
                android.os.Bundle status = new android.os.Bundle(); status.putString("check", "rendered_static_wallpaper_" + index + "_pass_" + pass); probe.sendStatus(0, status);
            }
        } finally { probe.runOnMainSync(home::finish); }
    }
}
