package com.sogud.bbtv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/** Explicit, reversible compatibility mode for firmware that forces its own HOME activity. */
final class HomeIntegration {
    static final String HOME_REQUEST = "com.sogud.bbtv.HOME_REQUEST";
    static boolean isSystemHome(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo resolved = context.getPackageManager()
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && context.getPackageName().equals(resolved.activityInfo.packageName);
    }

    static boolean enabled(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("home_integration", false);
    }

    static boolean needsService(Context context) {
        return enabled(context) || RemoteKeys.enabled(context);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                .putBoolean("home_integration", enabled).apply();
        syncRecovery(context);
    }

    static boolean serviceEnabled(Context context) {
        String services = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) return false;
        ComponentName expected = new ComponentName(context, HomeRedirectService.class);
        for (String service : services.split(":")) {
            if (expected.equals(ComponentName.unflattenFromString(service))) return true;
        }
        return false;
    }

    static boolean canRecover(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean restoreService(Context context) {
        if (!needsService(context) || !canRecover(context)) return false;
        try {
            // Preserve Xiao AI and every other enabled service; add only our component.
            if (!serviceEnabled(context)) {
                String services = Settings.Secure.getString(context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                ComponentName component = new ComponentName(context, HomeRedirectService.class);
                String updated = component.flattenToString();
                if (services != null && !services.isEmpty()) updated = services + ":" + updated;
                if (!Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)) return false;
            }
            if (Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                return Settings.Secure.putInt(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            return true;
        } catch (SecurityException error) {
            Log.w("BananaHome", "Home recovery permission is unavailable");
            return false;
        }
    }

    static boolean disconnectService(Context context) {
        if (!needsService(context) || !canRecover(context)) return false;
        String services = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) return false;
        ComponentName expected = new ComponentName(context, HomeRedirectService.class);
        java.util.List<String> others = new java.util.ArrayList<>();
        boolean found = false;
        for (String service : services.split(":")) {
            if (expected.equals(ComponentName.unflattenFromString(service))) found = true;
            else others.add(service);
        }
        if (!found) return false;
        try {
            return Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    android.text.TextUtils.join(":", others));
        } catch (SecurityException error) {
            return false;
        }
    }

    static void syncRecovery(Context context) {
        Intent recovery = new Intent(context, HomeRecoveryService.class);
        if (needsService(context) && canRecover(context)) {
            restoreService(context);
            context.startForegroundService(recovery);
        } else {
            context.stopService(recovery);
        }
    }

    static void openHome(Context context) {
        BananaDreamService.finishIfRunning();
        // Starting an already resumed singleTask activity pauses it on Android 9.
        if (MainActivity.handleHomeIfVisible()) return;
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(HOME_REQUEST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }
}
