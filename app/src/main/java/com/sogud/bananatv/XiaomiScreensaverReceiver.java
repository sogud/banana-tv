package com.sogud.bananatv;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Uses the verified firmware's idle decision instead of running a second global idle timer. */
public final class XiaomiScreensaverReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"com.duokan.intent.action.show.screensaver".equals(intent.getAction())
                || !SystemScreensaver.usesXiaomiTimer() || !SystemScreensaver.enabled(context)
                || BananaDreamService.isRunning()) return;
        try {
            Log.i("BananaScreensaver", "Received the verified firmware idle event");
            context.startActivity(SystemScreensaver.previewIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w("BananaScreensaver", "System screen saver entry is unavailable", error);
        }
    }
}
