package com.sogud.bbtv;

import android.content.Context;
import org.json.JSONObject;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Release metadata only; never downloads or installs APKs. */
final class ReleaseUpdates {
    static void refresh(Context context) { fetch(context, false); }

    static String fetch(Context context, boolean force) {
        long now = System.currentTimeMillis();
        long attempt = HomeOptions.prefs(context).getLong("update_attempt", 0);
        long checked = HomeOptions.prefs(context).getLong("update_checked", 0);
        if (!force && (now - attempt < 3600_000L || now - checked < 24 * 3600_000L)) return HomeOptions.text(context, "latest_version", "");
        HomeOptions.prefs(context).edit().putLong("update_attempt", now).apply();
        try {
            JSONObject latest = new JSONObject(new String(MediaLibrary.download(new URL(
                    "https://api.github.com/repos/sogud/banana-tv/releases/latest"), 256 * 1024), StandardCharsets.UTF_8));
            String tag = latest.getString("tag_name");
            if (!tag.matches("v?[0-9]+(\\.[0-9]+){1,3}([-.][A-Za-z0-9]+)*")) return null;
            HomeOptions.prefs(context).edit().putString("latest_version", tag).putLong("update_checked", now).apply();
            return tag;
        } catch (Exception ignored) { return null; }
    }

    static String available(Context context) {
        try {
            String current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            String latest = HomeOptions.text(context, "latest_version", "");
            return newer(latest, current) ? latest : "";
        } catch (Exception ignored) { return ""; }
    }

    static boolean newer(String latest, String current) {
        String[] left = latest.replaceFirst("^v", "").split("[-+]", 2)[0].split("\\.");
        String[] right = current.replaceFirst("^v", "").split("[-+]", 2)[0].split("\\.");
        try {
            for (int i = 0; i < Math.max(left.length, right.length); i++) {
                int a = i < left.length ? Integer.parseInt(left[i]) : 0;
                int b = i < right.length ? Integer.parseInt(right[i]) : 0;
                if (a != b) return a > b;
            }
        } catch (NumberFormatException ignored) { return false; }
        return false;
    }
}
