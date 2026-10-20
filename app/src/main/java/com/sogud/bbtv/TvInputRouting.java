package com.sogud.bbtv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.os.Build;

/** Standard TV inputs first; the verified Xiaomi player uses a separate source-number API. */
final class TvInputRouting {
    static Intent create(Context context, TvInputInfo input) {
        Intent standard = new Intent(Intent.ACTION_VIEW).setDataAndType(
                TvContract.buildChannelUriForPassthroughInput(input.getId()),
                "vnd.android.cursor.item/channel");
        if (standard.resolveActivity(context.getPackageManager()) != null) return standard;
        if (Build.VERSION.SDK_INT != 28 || !"mulan".equals(Build.DEVICE)) return null;
        try {
            if (context.getPackageManager().getPackageInfo("com.xiaomi.mitv.tvplayer", 0)
                    .getLongVersionCode() != 30000561) return null;
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
        int source = xiaomiSource(input.getId());
        if (source < 0) return null;
        Intent nativeInput = new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setPackage("com.xiaomi.mitv.tvplayer").putExtra("input", source);
        return nativeInput.resolveActivity(context.getPackageManager()) == null ? null : nativeInput;
    }

    static int xiaomiSource(String id) {
        switch (id) {
            case "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5": return 23;
            case "com.droidlogic.tvinput/.services.Hdmi2InputService/HW6": return 24;
            case "com.droidlogic.tvinput/.services.Hdmi3InputService/HW7": return 25;
            case "com.droidlogic.tvinput/.services.AV1InputService/HW1": return 2;
            default: return -1;
        }
    }
}
