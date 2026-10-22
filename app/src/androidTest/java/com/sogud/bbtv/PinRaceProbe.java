package com.sogud.bbtv;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Reproduces Home while a correct PIN is queued, without relying on hash timing. */
final class PinRaceProbe {
    static Bundle run(Instrumentation owner) {
        Bundle result = new Bundle();
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) {
            result.putString("result", "FAIL: emulator only"); return result;
        }
        Context context = owner.getTargetContext();
        SharedPreferences prefs = HomeOptions.prefs(context);
        Map<String, ?> original = prefs.getAll();
        CountDownLatch release = new CountDownLatch(1);
        try {
            prefs.edit().putBoolean("daily_enabled", false).putLong("update_attempt", System.currentTimeMillis()).commit();
            ParentalLock.set(context, "4726");
            Activity home = owner.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            owner.waitForIdleSync();
            Field workers = MainActivity.class.getDeclaredField("featureWorker"); workers.setAccessible(true);
            ThreadPoolExecutor worker = (ThreadPoolExecutor) workers.get(home);
            Field panel = MainActivity.class.getDeclaredField("activePanel"); panel.setAccessible(true);
            Method protect = MainActivity.class.getDeclaredMethod("protect", Runnable.class); protect.setAccessible(true);
            CountDownLatch occupied = new CountDownLatch(2);
            for (int i = 0; i < 2; i++) worker.execute(() -> {
                occupied.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            check(occupied.await(5, TimeUnit.SECONDS), "workers not ready");
            AtomicInteger opened = new AtomicInteger();
            submit(owner, home, panel, protect, opened);
            Method homeRequest = MainActivity.class.getDeclaredMethod("returnToHome");
            homeRequest.setAccessible(true);
            onUi(owner, () -> {
                try { homeRequest.invoke(home); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            owner.waitForIdleSync(); release.countDown(); idle(worker, owner);
            check(opened.get() == 0 && ParentalLock.locked(context), "queued PIN undid Home relock");
            onUi(owner, () -> check(read(panel, home) == null, "queued PIN reopened panel"));
            submit(owner, home, panel, protect, opened); idle(worker, owner);
            check(opened.get() == 1 && !ParentalLock.locked(context), "current PIN did not unlock");
            result.putString("result", "PASS");
            result.putString("checks", "queued_PIN_then_Home_stays_locked; current_PIN_opens_once");
        } catch (Exception | AssertionError error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
        } finally {
            release.countDown(); FeatureParityProbe.restore(prefs, original); ParentalLock.relock();
        }
        return result;
    }

    private static void submit(Instrumentation owner, Activity home, Field panel, Method protect, AtomicInteger opened) {
        onUi(owner, () -> {
            try { protect.invoke(home, (Runnable) opened::incrementAndGet); }
            catch (Exception error) { throw new AssertionError(error); }
            Dialog dialog = (Dialog) read(panel, home); check(dialog != null, "PIN panel missing");
            View root = dialog.getWindow().getDecorView();
            EditText input = (EditText) find(root, EditText.class, null); check(input != null, "PIN input missing"); input.setText("4726");
            Button button = (Button) find(root, Button.class, home.getString(R.string.pin_submit));
            check(button != null, "PIN submit missing"); button.performClick();
        });
    }

    private static void idle(ThreadPoolExecutor worker, Instrumentation owner) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            SystemClock.sleep(30);
            if (worker.getActiveCount() == 0 && worker.getQueue().isEmpty()) { owner.waitForIdleSync(); return; }
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("PIN worker timed out");
    }

    private static View find(View view, Class<?> type, String text) {
        if (type.isInstance(view) && (text == null || ((Button) view).getText().toString().equals(text))) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), type, text); if (found != null) return found;
        }
        return null;
    }

    private static Object read(Field field, Object target) {
        try { return field.get(target); } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void onUi(Instrumentation owner, Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        owner.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
