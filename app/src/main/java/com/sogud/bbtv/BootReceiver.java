package com.sogud.bbtv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android 9 target: start the selected home after boot, only when the owner enabled it. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_USER_UNLOCKED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (HomeIntegration.needsService(context)) HomeIntegration.syncRecovery(context);
        if (HomeIntegration.enabled(context)) {
            HomeIntegration.openHome(context);
        }
    }
}
