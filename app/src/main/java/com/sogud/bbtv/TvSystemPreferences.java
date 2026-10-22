package com.sogud.bbtv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Small allowlist of system preferences; Settings remains the source of truth. */
final class TvSystemPreferences {
    private static final String BOOT_AD_POLICY = "mitv.settings.advertise.state";
    private static final String[] ANIMATION_KEYS = {
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE
    };

    static boolean canWrite(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean supportsBrightness(Context context) {
        String maker = Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        if (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("sony") || maker.contains("tcl")) return false;
        int value = brightness(context);
        return Settings.System.canWrite(context) && value >= 0 && value <= 255
                && Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
    }

    static int brightness(Context context) {
        return Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
    }

    static boolean setBrightness(Context context, int value) {
        if (!supportsBrightness(context) || value < 1 || value > 255) return false;
        if (!HomeOptions.prefs(context).contains("brightness_before")) {
            HomeOptions.prefs(context).edit().putInt("brightness_before", brightness(context)).apply();
        }
        try {
            return Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value)
                    && brightness(context) == value;
        } catch (SecurityException | IllegalArgumentException error) { return false; }
    }

    static boolean supportsBootAdPolicy(Context context) {
        // This reader was verified in this TV Home build; do not guess for other firmware.
        if (Build.VERSION.SDK_INT != 28 || !"mulan".equals(Build.DEVICE)) return false;
        try {
            return context.getPackageManager().getPackageInfo("com.mitv.tvhome", 0)
                    .getLongVersionCode() == 100413;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    static int bootAdPolicy(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), BOOT_AD_POLICY, 0);
    }

    static boolean setBootAdsBlocked(Context context, boolean blocked) {
        if (!canWrite(context) || !supportsBootAdPolicy(context)) return false;
        int value = blocked ? 1 : 0;
        try {
            return Settings.Global.putInt(context.getContentResolver(), BOOT_AD_POLICY, value)
                    && bootAdPolicy(context) == value;
        } catch (SecurityException | IllegalArgumentException error) {
            return false;
        }
    }

    /** Negative means the three system animation scales differ or use a custom value. */
    static float animationScale(Context context) {
        float scale = Settings.Global.getFloat(context.getContentResolver(), ANIMATION_KEYS[0], 1f);
        for (String key : ANIMATION_KEYS) {
            if (Settings.Global.getFloat(context.getContentResolver(), key, 1f) != scale) return -1f;
        }
        return scale;
    }

    static boolean setAnimationScale(Context context, float scale) {
        if (!canWrite(context) || (scale != 0f && scale != 0.5f && scale != 1f)) return false;
        String[] previous = new String[ANIMATION_KEYS.length];
        for (int i = 0; i < ANIMATION_KEYS.length; i++) {
            previous[i] = Settings.Global.getString(context.getContentResolver(), ANIMATION_KEYS[i]);
        }
        try {
            for (String key : ANIMATION_KEYS) {
                if (!Settings.Global.putFloat(context.getContentResolver(), key, scale)) {
                    restoreAnimationScales(context, previous);
                    return false;
                }
            }
            if (animationScale(context) == scale) return true;
        } catch (SecurityException | IllegalArgumentException error) {
            // A firmware can reject a later key after accepting an earlier one.
        }
        restoreAnimationScales(context, previous);
        return false;
    }

    private static void restoreAnimationScales(Context context, String[] previous) {
        for (int i = 0; i < ANIMATION_KEYS.length; i++) {
            try {
                Settings.Global.putString(context.getContentResolver(), ANIMATION_KEYS[i], previous[i]);
            } catch (SecurityException | IllegalArgumentException error) {
                // The menu rereads actual values, including an incomplete restoration.
            }
        }
    }
}
