package com.sogud.bananatv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/** Real key dispatch and focus checks, restricted to a disposable emulator. */
final class WallpaperPeekProbe {
    static Bundle run(Instrumentation owner) {
        Bundle result = new Bundle();
        String hardware = android.os.Build.HARDWARE;
        if (!hardware.equals("ranchu") && !hardware.equals("goldfish")) {
            result.putString("result", "FAIL: emulator only");
            return result;
        }
        Context context = owner.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        boolean hadMotion = prefs.contains("icon_motion");
        boolean originalMotion = prefs.getBoolean("icon_motion", true);
        try {
            Activity home = owner.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle(owner);
            FrameLayout root = home.findViewById(R.id.root);
            List<View> chrome = new ArrayList<>();
            List<Float> alphas = new ArrayList<>();
            List<Integer> accessibility = new ArrayList<>();
            View[] focus = new View[1];
            Object[] player = new Object[1];
            java.lang.reflect.Field playerField = VideoWallpaper.class.getDeclaredField("player");
            playerField.setAccessible(true);
            onUi(owner, () -> {
                for (int i = 0; i < root.getChildCount(); i++) {
                    View child = root.getChildAt(i);
                    if (child instanceof LinearLayout || child.getId() == R.id.wallpaperShade) {
                        chrome.add(child);
                        alphas.add(child.getAlpha());
                        accessibility.add(child.getImportantForAccessibility());
                    }
                }
            });
            owner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            settle(owner);
            check(root.findViewById(R.id.wallpaperPeek) == null, "short Back changed");
            onUi(owner, () -> focus[0] = home.getCurrentFocus());
            check(focus[0] != null, "no initial focus");
            player[0] = playerField.get(home.findViewById(R.id.videoWallpaper));

            longBack(owner);
            settle(owner);
            onUi(owner, () -> {
                View overlay = root.findViewById(R.id.wallpaperPeek);
                check(overlay != null && overlay.hasFocus(), "opening release dismissed preview");
                for (View child : chrome) {
                    check(child.getAlpha() == 0f, "chrome still visible");
                    check(child.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            "hidden controls remain accessible");
                }
            });
            check(playerField.get(home.findViewById(R.id.videoWallpaper)) == player[0], "wallpaper player replaced");
            owner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            settle(owner);
            onUi(owner, () -> {
                check(root.findViewById(R.id.wallpaperPeek) == null, "OK did not exit");
                check(home.getCurrentFocus() == focus[0], "exit moved focus or opened an app");
                for (int i = 0; i < chrome.size(); i++) {
                    check(chrome.get(i).getAlpha() == alphas.get(i), "alpha not restored");
                    check(chrome.get(i).getImportantForAccessibility() == accessibility.get(i), "accessibility not restored");
                }
            });

            longBack(owner);
            owner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            settle(owner);
            onUi(owner, () -> check(home.getCurrentFocus() == focus[0], "exit key reached apps"));

            longBack(owner);
            owner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            settle(owner);
            check(root.findViewById(R.id.wallpaperPeek) == null, "Back did not exit");

            // Motion off must restore the exact alpha immediately, without an animation.
            check(prefs.edit().putBoolean("icon_motion", false).commit(), "motion fixture failed");
            longBack(owner);
            owner.waitForIdleSync();
            onUi(owner, () -> {
                for (View child : chrome) check(child.getAlpha() == 0f, "motion-off fade still running");
            });
            context.startActivity(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle(owner);
            onUi(owner, () -> {
                check(root.findViewById(R.id.wallpaperPeek) == null, "Home did not restore controls");
                for (int i = 0; i < chrome.size(); i++) check(chrome.get(i).getAlpha() == alphas.get(i), "Home left hidden chrome");
            });
            result.putString("result", "PASS");
            result.putString("checks", "short_Back,long_Back,hidden_chrome,accessibility,player_retained,OK_exit,focus,arrow_exit,Back_exit,motion_off,Home_exit");
            result.putBoolean("active_video_checked", player[0] != null);
        } catch (Exception | AssertionError error) {
            result.putString("result", "FAIL");
            result.putString("error", error.toString());
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (hadMotion) edit.putBoolean("icon_motion", originalMotion);
            else edit.remove("icon_motion");
            edit.commit();
        }
        return result;
    }

    private static void onUi(Instrumentation owner, Runnable task) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        owner.runOnMainSync(() -> {
            try { task.run(); }
            catch (RuntimeException | AssertionError error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void longBack(Instrumentation owner) {
        long down = SystemClock.uptimeMillis();
        owner.sendKeySync(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
        SystemClock.sleep(600);
        owner.sendKeySync(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS));
        owner.sendKeySync(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
    }

    private static void settle(Instrumentation owner) {
        owner.waitForIdleSync();
        SystemClock.sleep(400);
        owner.waitForIdleSync();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
