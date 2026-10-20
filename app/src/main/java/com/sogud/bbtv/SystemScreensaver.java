package com.sogud.bbtv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

/** Opt-in Android screen saver selection, with the previous system values kept for restoration. */
final class SystemScreensaver {
    private static final String COMPONENTS = "screensaver_components";
    private static final String ENABLED = "screensaver_enabled";
    private static final String ON_SLEEP = "screensaver_activate_on_sleep";

    static boolean usesXiaomiTimer() {
        // This firmware broadcasts idle events instead of automatically starting Android dreams.
        return "Xiaomi/mulan/mulan:9/PPR1.180610.011/OS3.0.101.0.PSTAATV:user/release-keys"
                .equals(Build.FINGERPRINT);
    }

    static Intent previewIntent() {
        return new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
    }

    private static SharedPreferences backup(Context context) {
        return context.getSharedPreferences("system_screensaver_restore", Context.MODE_PRIVATE);
    }

    private static String component(Context context) {
        return new ComponentName(context, BananaDreamService.class).flattenToString();
    }

    static boolean supported(Context context) {
        int id = context.getResources().getIdentifier("config_dreamsSupported", "bool", "android");
        return id != 0 && context.getResources().getBoolean(id);
    }

    private static boolean selected(Context context) {
        String selected = Settings.Secure.getString(context.getContentResolver(), COMPONENTS);
        return new ComponentName(context, BananaDreamService.class)
                .equals(ComponentName.unflattenFromString(selected == null ? "" : selected));
    }

    static boolean enabled(Context context) {
        return supported(context) && selected(context)
                && Settings.Secure.getInt(context.getContentResolver(), ENABLED, 0) == 1;
    }

    static int minutes(Context context) {
        int timeout = Settings.System.getInt(context.getContentResolver(),
                usesXiaomiTimer() ? "screen_saver_timeout" : Settings.System.SCREEN_OFF_TIMEOUT, 0);
        return timeout > 0 && timeout % 60_000 == 0 ? timeout / 60_000 : 0;
    }

    static boolean enable(Context context, int minutes) {
        if (!supported(context) || !TvSystemPreferences.canWrite(context) || minutes < 1 || minutes > 30) return false;
        SharedPreferences previous = backup(context);
        if (!previous.getBoolean("saved", false)) {
            boolean saved = previous.edit()
                    .putString(COMPONENTS, Settings.Secure.getString(context.getContentResolver(), COMPONENTS))
                    .putString(ENABLED, Settings.Secure.getString(context.getContentResolver(), ENABLED))
                    .putString(ON_SLEEP, Settings.Secure.getString(context.getContentResolver(), ON_SLEEP))
                    .putString("timeout", Settings.System.getString(context.getContentResolver(),
                            Settings.System.SCREEN_OFF_TIMEOUT))
                    .putBoolean("saved", true).commit();
            if (!saved) return false;
        }
        try {
            boolean saved = Settings.Secure.putString(context.getContentResolver(), COMPONENTS, component(context))
                    && Settings.Secure.putInt(context.getContentResolver(), ENABLED, 1)
                    && Settings.Secure.putInt(context.getContentResolver(), ON_SLEEP, 1)
                    && (usesXiaomiTimer() || setMinutes(context, minutes));
            if (saved && enabled(context)) return true;
        } catch (SecurityException | IllegalArgumentException error) {
            // Restore before reporting a partially applied selection as a failure.
        }
        restore(context);
        return false;
    }

    static boolean setMinutes(Context context, int minutes) {
        if (usesXiaomiTimer() || !TvSystemPreferences.canWrite(context) || minutes < 1 || minutes > 30) return false;
        try {
            return Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60_000)
                    && minutes(context) == minutes;
        } catch (SecurityException | IllegalArgumentException error) {
            return false;
        }
    }

    static boolean restore(Context context) {
        SharedPreferences previous = backup(context);
        if (!TvSystemPreferences.canWrite(context) || !previous.getBoolean("saved", false)) return false;
        // Another screen saver selected outside this app takes precedence.
        if (!selected(context)) {
            previous.edit().clear().apply();
            return true;
        }
        try {
            boolean restored = restoreValue(context, "timeout", previous.getString("timeout", null));
            restored &= restoreValue(context, ON_SLEEP, previous.getString(ON_SLEEP, null));
            restored &= restoreValue(context, ENABLED, previous.getString(ENABLED, null));
            restored &= restoreValue(context, COMPONENTS, previous.getString(COMPONENTS, null));
            if (!restored) return false;
            previous.edit().clear().apply();
            return true;
        } catch (SecurityException | IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean restoreValue(Context context, String key, String value) {
        boolean saved;
        String actual;
        if ("timeout".equals(key)) {
            saved = Settings.System.putString(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, value);
            actual = Settings.System.getString(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
        } else {
            saved = Settings.Secure.putString(context.getContentResolver(), key, value);
            actual = Settings.Secure.getString(context.getContentResolver(), key);
        }
        return saved && java.util.Objects.equals(value, actual);
    }
}
