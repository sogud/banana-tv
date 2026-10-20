package com.sogud.bbtv;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;

/** Tests a same-value write as the launcher UID, without enabling an advertisement. */
final class TvPreferencesProbe {
    static Bundle runGlobal(Context context) {
        Bundle result = new Bundle();
        String key = "mitv.settings.advertise.state";
        String before = Settings.Global.getString(context.getContentResolver(), key);
        Context denied = new ContextWrapper(context) {
            @Override
            public int checkSelfPermission(String permission) {
                return PackageManager.PERMISSION_DENIED;
            }
        };
        try {
            boolean rejected = !TvSystemPreferences.setBootAdsBlocked(denied, true)
                    && !TvSystemPreferences.setAnimationScale(denied, 0.5f);
            boolean unchanged = java.util.Objects.equals(before,
                    Settings.Global.getString(context.getContentResolver(), key));
            result.putBoolean("permission_denial_handled", rejected && unchanged);
            if (!rejected || !unchanged) throw new IllegalStateException("Permission denial was ignored");
            boolean saved = TvSystemPreferences.setBootAdsBlocked(context, true);
            String after = Settings.Global.getString(context.getContentResolver(), key);
            result.putBoolean("boot_ad_policy_saved_by_app", saved && "1".equals(after));
            result.putString("after", after);
            result.putString("result", saved && "1".equals(after) ? "PASS" : "FAIL");
        } catch (Exception error) {
            result.putString("result", "FAIL");
            result.putString("error", error.toString());
        }
        return result;
    }

    static Bundle run(Context context) {
        Bundle result = new Bundle();
        String key = "boot_ad_switch";
        String before = Settings.System.getString(context.getContentResolver(), key);
        result.putString("before", before);
        try {
            if (!"0".equals(before)) throw new IllegalStateException("Expected boot ads already off");
            boolean saved = Settings.System.putString(context.getContentResolver(), key, before);
            String after = Settings.System.getString(context.getContentResolver(), key);
            boolean passed = saved && before.equals(after);
            result.putString("after", after);
            result.putString("result", passed ? "PASS" : "FAIL");
        } catch (Exception error) {
            result.putString("result", "FAIL");
            result.putString("error", error.toString());
        }
        return result;
    }
}
