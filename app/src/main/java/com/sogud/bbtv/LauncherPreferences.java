package com.sogud.bbtv;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Legacy favourites seed the first tile order; existing preference keys remain upgrade-compatible. */
final class LauncherPreferences {
    private LauncherPreferences() {}

    static List<String> favourites(Context context) {
        String[] defaults = context.getResources().getStringArray(R.array.dock_apps);
        List<String> result = new ArrayList<>();
        for (int slot = 0; slot < defaults.length; slot++) {
            result.add(context.getSharedPreferences("home", Context.MODE_PRIVATE)
                    .getString("slot_" + slot, defaults[slot]));
        }
        return result;
    }

    static void setFavourite(Context context, int slot, String pkg) {
        List<String> current = favourites(context);
        if (slot < 0 || slot >= current.size()) throw new IllegalArgumentException("Invalid dock slot");
        int previous = current.indexOf(pkg);
        String displaced = current.get(slot);
        android.content.SharedPreferences.Editor editor = context
                .getSharedPreferences("home", Context.MODE_PRIVATE).edit();
        editor.putString("slot_" + slot, pkg);
        if (previous >= 0 && previous != slot) editor.putString("slot_" + previous, displaced);
        editor.apply();
    }

    static int wallpaper(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("wallpaper", 5);
    }

    static int dockPercent(Context context) {
        int value = context.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("dock_percent", 90);
        return Math.max(75, Math.min(110, value));
    }

    static void setDockPercent(Context context, int value) {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                .putInt("dock_percent", Math.max(75, Math.min(110, value))).apply();
    }

    static boolean motionEnabled(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("icon_motion", true)
                && android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    static void setMotionEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("icon_motion", enabled).apply();
    }

    static java.util.Set<String> hiddenApps(Context context) {
        return new java.util.HashSet<>(context.getSharedPreferences("home", Context.MODE_PRIVATE)
                .getStringSet("hidden_apps", java.util.Collections.emptySet()));
    }

    static void setAppHidden(Context context, String pkg, boolean hidden) {
        java.util.Set<String> apps = hiddenApps(context);
        if (hidden) apps.add(pkg);
        else apps.remove(pkg);
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putStringSet("hidden_apps", apps).apply();
    }

    static void setWallpaper(Context context, int index) {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putInt("wallpaper", index).putString("wallpaper_source", "legacy").apply();
    }

    static int screensaverMinutes(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("screensaver_minutes", 5);
    }

    static void setScreensaverMinutes(Context context, int minutes) {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                .putInt("screensaver_minutes", minutes).apply();
    }
}
