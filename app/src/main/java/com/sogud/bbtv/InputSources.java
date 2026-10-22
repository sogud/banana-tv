package com.sogud.bbtv;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;

final class InputSources {
    private static long lastRoute;
    private static String lastId = "";
    static List<TvInputInfo> all(Context context) {
        TvInputManager manager = context.getSystemService(TvInputManager.class);
        return manager == null ? new ArrayList<>() : manager.getTvInputList();
    }

    static List<TvInputInfo> selectable(Context context) {
        List<TvInputInfo> result = new ArrayList<>();
        for (TvInputInfo input : all(context)) {
            if (input.getParentId() == null && !input.isHidden(context)
                    && (input.isPassthroughInput() || input.getType() == TvInputInfo.TYPE_TUNER)) result.add(input);
        }
        return result;
    }

    static String label(Context context, TvInputInfo input) {
        CharSequence custom = input.loadCustomLabel(context);
        String name = String.valueOf(custom == null || custom.length() == 0 ? input.loadLabel(context) : custom);
        TvInputManager manager = context.getSystemService(TvInputManager.class);
        if (manager != null) for (TvInputInfo child : all(context)) {
            if (input.getId().equals(child.getParentId())
                    && manager.getInputState(child.getId()) == TvInputManager.INPUT_STATE_CONNECTED) {
                CharSequence device = child.loadLabel(context);
                if (device != null && !device.toString().equals(name)) name += " · " + device;
            }
        }
        return name;
    }

    static void routeBoot(Context context) {
        String selected = HomeOptions.text(context, "boot_source", "");
        if (selected.isEmpty()) return;
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0 || HomeOptions.prefs(context).getInt("input_boot_count", -2) == boot) return;
        HomeOptions.prefs(context).edit().putInt("input_boot_count", boot).apply();
        route(context, selected);
    }

    static boolean route(Context context, String id) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (id.equals(lastId) && now - lastRoute < 15_000L) return false;
        for (TvInputInfo input : all(context)) if (input.getId().equals(id)) {
            Intent intent = TvInputRouting.create(context, input);
            if (intent == null) return false;
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); lastId = id; lastRoute = now; return true; }
            catch (android.content.ActivityNotFoundException | SecurityException ignored) { return false; }
        }
        return false;
    }
}
