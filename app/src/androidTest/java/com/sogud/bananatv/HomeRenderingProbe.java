package com.sogud.bananatv;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Rendering regressions use generated fixtures and restore the emulator's preferences. */
final class HomeRenderingProbe {
    static Bundle run(Instrumentation instrumentation, String scenario) {
        Bundle result = new Bundle();
        Context context = instrumentation.getTargetContext();
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) {
            result.putString("result", "FAIL");
            result.putString("error", "Emulator only");
            return result;
        }
        SharedPreferences prefs = HomeOptions.prefs(context);
        Map<String, ?> original = prefs.getAll();
        File poster = new File(context.getExternalFilesDir("wallpapers"), "wallpaper.jpg");
        File savedPoster = new File(poster.getParentFile(), "wallpaper.jpg.probe-backup");
        boolean fixtureCreated = false;
        boolean posterMoved = false;
        MainActivity home = null;
        try {
            if (scenario.equals("names")) {
                prefs.edit().remove("name_style").commit();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                instrumentation.runOnMainSync(() -> {
                    try {
                        AppAdapter.Holder holder = new AppAdapter.Holder(LayoutInflater.from(context)
                                .inflate(R.layout.item_app, null, false), null);
                        holder.name.setText("Test app");
                        holder.setFocusedState(false, false);
                        require(holder.name.getAlpha() == 1f, "Default app name is invisible");
                        HomeOptions.set(context, "name_style", "hidden");
                        holder.setFocusedState(true, false);
                        require(holder.name.getAlpha() == 0f, "Explicit hidden names changed");
                        HomeOptions.set(context, "name_style", "focused");
                        holder.setFocusedState(false, false);
                        require(holder.name.getAlpha() == 0f, "Unfocused name should be hidden");
                        holder.setFocusedState(true, false);
                        require(holder.name.getAlpha() == 1f, "Focused name should be visible");
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                });
                if (failure.get() != null) throw new AssertionError(failure.get());
            } else if (scenario.equals("poster")) {
                require(!savedPoster.exists(), "Unrestored poster fixture exists");
                if (poster.exists()) {
                    require(poster.renameTo(savedPoster), "Cannot preserve local poster");
                    posterMoved = true;
                }
                try (FileOutputStream output = new FileOutputStream(poster)) {
                    output.write(new byte[]{1, 2, 3});
                }
                fixtureCreated = true;
                prefs.edit().putString("wallpaper_source", "legacy").putInt("wallpaper", 4)
                        .putInt("screensaver_minutes", 0).commit();
                home = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                MainActivity activity = home;
                AtomicBoolean ready = new AtomicBoolean();
                long deadline = SystemClock.uptimeMillis() + 8000;
                while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync(() -> {
                        ImageView wallpaper = activity.findViewById(R.id.wallpaper);
                        ready.set(wallpaper.getDrawable() instanceof BitmapDrawable
                                && ((BitmapDrawable) wallpaper.getDrawable()).getBitmap() != null);
                    });
                    SystemClock.sleep(50);
                }
                require(ready.get(), "Invalid local poster left the wallpaper blank");
            } else {
                throw new IllegalArgumentException("Unknown rendering scenario");
            }
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL");
            result.putString("error", error.toString());
        } finally {
            if (home != null) {
                MainActivity activity = home;
                instrumentation.runOnMainSync(activity::finish);
            }
            if (fixtureCreated) poster.delete();
            if (posterMoved && !savedPoster.renameTo(poster)) {
                result.putString("result", "FAIL");
                result.putString("error", "Restore poster from wallpaper.jpg.probe-backup");
            }
            FeatureParityProbe.restore(prefs, original);
        }
        result.putString("scenario", scenario);
        result.putString("scope", "Emulator app views; excludes TV video output and firmware ads.");
        return result;
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
