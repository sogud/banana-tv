package com.sogud.bananatv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.TreeMap;

/** Local key-to-app assignments; no shell commands, scripts or input history. */
final class RemoteKeys {
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("remote_keys", Context.MODE_PRIVATE);
    }

    static Map<Integer, String> bindings(Context context) {
        Map<Integer, String> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            try {
                int code = Integer.parseInt(entry.getKey());
                if (RemoteKeyPolicy.canMap(code) && entry.getValue() instanceof String) {
                    result.put(code, (String) entry.getValue());
                }
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    static boolean enabled(Context context) { return !bindings(context).isEmpty(); }

    static void bind(Context context, int code, String pkg) {
        if (!RemoteKeyPolicy.canMap(code)) throw new IllegalArgumentException("Reserved remote key");
        SharedPreferences.Editor editor = prefs(context).edit();
        if (pkg == null) editor.remove(Integer.toString(code));
        else {
            if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid package");
            editor.putString(Integer.toString(code), pkg);
        }
        editor.apply();
        HomeIntegration.syncRecovery(context);
    }

    static void clear(Context context) {
        prefs(context).edit().clear().apply();
        HomeIntegration.syncRecovery(context);
    }

    static Intent target(Context context, int key) {
        if (!RemoteKeyPolicy.canMap(key)) return null;
        String pkg = prefs(context).getString(Integer.toString(key), null);
        if (pkg == null) return null;
        Intent intent = context.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        return intent == null ? null : intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
