package com.sogud.bbtv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Additional choices share the existing Home store; old installations keep their defaults. */
final class HomeOptions {
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static String text(Context context, String key, String fallback) {
        return prefs(context).getString(key, fallback);
    }

    static boolean flag(Context context, String key, boolean fallback) {
        return prefs(context).getBoolean(key, fallback);
    }

    static void set(Context context, String key, String value) {
        prefs(context).edit().putString(key, value).apply();
    }

    static void set(Context context, String key, boolean value) {
        prefs(context).edit().putBoolean(key, value).apply();
    }

    static int columns(Context context) {
        return text(context, "layout", "dock").equals("grid") ? 5 : 4;
    }

    static Set<String> pinnedInputs(Context context) {
        return new HashSet<>(prefs(context).getStringSet("pinned_inputs", java.util.Collections.emptySet()));
    }

    static void pinInput(Context context, String id, boolean pin) {
        Set<String> ids = pinnedInputs(context);
        if (pin) ids.add(id); else ids.remove(id);
        prefs(context).edit().putStringSet("pinned_inputs", ids).apply();
    }

    static boolean validChoice(String key, String value) {
        String choices;
        switch (key) {
            case "layout": choices = "dock,grid"; break;
            case "name_style": choices = "hidden,focused,all"; break;
            case "screensaver_mode": choices = "wallpaper,aerial,photos,clock"; break;
            case "focus_style": choices = "standard,contrast"; break;
            default: return false;
        }
        return Arrays.asList(choices.split(",")).contains(value);
    }
}
