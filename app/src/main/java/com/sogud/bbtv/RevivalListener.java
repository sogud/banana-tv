package com.sogud.bbtv;

import android.service.notification.NotificationListenerService;

/** Optional OS binding only. Never accesses notification contents. */
public final class RevivalListener extends NotificationListenerService {
    private static java.lang.ref.WeakReference<RevivalListener> active = new java.lang.ref.WeakReference<>(null);
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    static void setEnabled(android.content.Context context, boolean enabled) {
        HomeOptions.set(context, "notification_revival", enabled);
        RevivalListener listener = active.get();
        if (!enabled && listener != null) listener.requestUnbind();
        if (enabled) requestRebind(new android.content.ComponentName(context, RevivalListener.class));
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        active = new java.lang.ref.WeakReference<>(this);
        if (!HomeOptions.flag(this, "notification_revival", false)) { requestUnbind(); return; }
        HomeIntegration.syncRecovery(this);
        handler.postDelayed(() -> {
            if (HomeIntegration.needsService(this) && HomeIntegration.canRecover(this) && !HomeRedirectService.isConnected()) {
                if (HomeIntegration.disconnectService(this)) handler.postDelayed(() -> HomeIntegration.restoreService(this), 200);
            }
        }, 5000);
    }

    @Override public void onListenerDisconnected() {
        if (active.get() == this) active.clear();
        handler.removeCallbacksAndMessages(null);
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        if (active.get() == this) active.clear();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
