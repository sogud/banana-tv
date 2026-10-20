package com.sogud.bbtv;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import java.util.Arrays;

/** Verifies app-UID configuration and exact readback restoration, without starting a dream. */
final class SystemScreensaverProbe {
    private static String[] snapshot(Context context) {
        return new String[]{
                Settings.Secure.getString(context.getContentResolver(), "screensaver_components"),
                Settings.Secure.getString(context.getContentResolver(), "screensaver_enabled"),
                Settings.Secure.getString(context.getContentResolver(), "screensaver_activate_on_sleep"),
                Settings.System.getString(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT)
        };
    }

    static Bundle run(Context context) {
        Bundle result = new Bundle();
        String[] before = snapshot(context);
        boolean changed = false;
        try {
            if (SystemScreensaver.enabled(context)) {
                throw new IllegalStateException("Restore the owner's screen saver selection before this probe");
            }
            Context denied = new ContextWrapper(context) {
                @Override public int checkSelfPermission(String permission) {
                    return PackageManager.PERMISSION_DENIED;
                }
            };
            boolean rejected = !SystemScreensaver.enable(denied, 5)
                    && !SystemScreensaver.enable(context, 0)
                    && Arrays.equals(before, snapshot(context));
            result.putBoolean("invalid_or_unauthorized_rejected", rejected);
            if (!rejected) throw new IllegalStateException("Invalid or unauthorized configuration changed settings");
            changed = true;
            boolean enabled = SystemScreensaver.enable(context, 5);
            String expectedTimeout = SystemScreensaver.usesXiaomiTimer() ? before[3] : "300000";
            result.putBoolean("system_configuration_saved", enabled && SystemScreensaver.enabled(context)
                    && java.util.Objects.equals(expectedTimeout, snapshot(context)[3]));
            result.putBoolean("uses_verified_xiaomi_timer", SystemScreensaver.usesXiaomiTimer());
            if (!result.getBoolean("system_configuration_saved")) {
                throw new IllegalStateException("System screen saver configuration did not match");
            }
            boolean restored = SystemScreensaver.restore(context) && Arrays.equals(before, snapshot(context));
            result.putBoolean("previous_values_restored", restored);
            if (!restored) throw new IllegalStateException("Previous system values were not restored");
            changed = false;
            result.putString("result", "PASS");
        } catch (Exception error) {
            result.putString("result", "FAIL");
            result.putString("error", error.toString());
        } finally {
            if (changed) SystemScreensaver.restore(context);
        }
        return result;
    }
}
