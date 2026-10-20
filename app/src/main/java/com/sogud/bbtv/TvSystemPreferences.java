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
