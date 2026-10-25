package com.sogud.bananatv;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.os.SystemClock;
import android.view.KeyEvent;

/** Handles Home and Xiaomi home windows without reading screen contents or retaining input. */
public class HomeRedirectService extends AccessibilityService {
    private long lastRedirect;
    private static boolean connected;
    private int heldKey = -1;
    private android.content.Intent keyTarget;
    private android.content.ComponentName fallbackHome;

    static boolean isConnected() {
        return connected;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        android.content.pm.ResolveInfo home = getPackageManager().resolveActivity(new android.content.Intent(
                android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null && !getPackageName().equals(home.activityInfo.packageName)
                && !"android".equals(home.activityInfo.packageName)) {
            fallbackHome = new android.content.ComponentName(home.activityInfo.packageName, home.activityInfo.name);
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (MainActivity.captureRemoteKeyIfWaiting(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            android.content.Intent target = RemoteKeys.target(this, event.getKeyCode());
            if (target != null) {
                if (event.getRepeatCount() == 0) {
                    heldKey = event.getKeyCode();
                    keyTarget = target;
                }
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && heldKey == event.getKeyCode()) {
            android.content.Intent target = keyTarget;
            heldKey = -1;
            keyTarget = null;
            if (!event.isCanceled() && target != null) {
                try { startActivity(target); }
                catch (android.content.ActivityNotFoundException | SecurityException ignored) {
                    android.widget.Toast.makeText(this, R.string.remote_missing_app, android.widget.Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (!HomeIntegration.enabled(this) || event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // Xiaomi's Android 9 settings is a system window above the launcher.
            if (android.os.Build.VERSION.SDK_INT < 31) {
                sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                        .putExtra("reason", "homekey"));
            }
            HomeIntegration.openHome(this);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!HomeIntegration.enabled(this)) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;
        CharSequence className = event.getClassName();
        if (className == null) return;
        String name = className.toString();
        boolean xiaomi = "com.mitv.tvhome".contentEquals(packageName)
                && (name.equals("com.mitv.tvhome.MainActivityUserMode") || name.equals("com.mitv.tvhome.MainActivity"));
        boolean forcedHome = fallbackHome != null && fallbackHome.getPackageName().contentEquals(packageName)
                && fallbackHome.getClassName().equals(name);
        if (!xiaomi && !forcedHome) return;
        // A delayed Xiaomi event must not dismiss our own settings or screen saver.
        if (MainActivity.isHomeVisible()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRedirect < 800) return;
        lastRedirect = now;
        HomeIntegration.openHome(this);
    }

    @Override
    public void onInterrupt() {}
}
