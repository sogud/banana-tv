package com.sogud.bbtv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

/** Restores only our Home service when its setting changes; no screen or app polling. */
public final class HomeRecoveryService extends Service {
    private static final String CHANNEL = "home_routing";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastReconnectAt = -30_000L;

    private final Runnable repair = this::repairHome;
    private final Runnable checkConnection = this::repairConnection;

    private void repairHome() {
        if (!HomeIntegration.restoreService(this)) {
            stopSelf();
            return;
        }
        handler.removeCallbacks(checkConnection);
        handler.postDelayed(checkConnection, 3_000L);
    }

    private void repairConnection() {
        if (HomeRedirectService.isConnected()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastReconnectAt < 30_000L) return;
        lastReconnectAt = now;
        // Xiaomi can retain an enabled component with a dead Binder after process death.
        if (HomeIntegration.disconnectService(this)) {
            handler.removeCallbacks(repair);
            handler.postDelayed(repair, 200L);
        }
    }

    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(repair);
            handler.postDelayed(repair, 200);
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.post(repair);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.home_title), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_input)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.home_notification))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        startForeground(14, notification);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_ENABLED), false, observer);
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(repair);
        handler.post(repair);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        getContentResolver().unregisterContentObserver(observer);
        unregisterReceiver(screenOn);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
