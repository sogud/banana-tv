package com.sogud.bbtv;

import android.content.Context;
import android.provider.Settings;

/** ADB is never enabled by default and never receives arbitrary settings from the UI. */
final class RecoveryOptions {
    static boolean keepAdb(Context context) {
        return HomeOptions.flag(context, "keep_adb", false);
    }

    static boolean setKeepAdb(Context context, boolean enabled) {
        if (enabled && !HomeIntegration.canRecover(context)) return false;
        if (enabled) {
            try {
                String previous = Settings.Global.getString(context.getContentResolver(), Settings.Global.ADB_ENABLED);
                if (!HomeOptions.prefs(context).contains("adb_before_recorded")) {
                    HomeOptions.prefs(context).edit().putBoolean("adb_before_recorded", true)
                            .putString("adb_before", previous).apply();
                }
                if (!Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1)
                        || Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) return false;
            } catch (SecurityException ignored) { return false; }
        }
        HomeOptions.set(context, "keep_adb", enabled);
        HomeIntegration.syncRecovery(context);
        return true;
    }

    static void restoreAdb(Context context) {
        if (!keepAdb(context) || !HomeIntegration.canRecover(context)) return;
        try {
            if (Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            }
        } catch (SecurityException ignored) { }
    }
}
