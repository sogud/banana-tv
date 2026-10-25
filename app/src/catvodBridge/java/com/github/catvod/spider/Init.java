package com.github.catvod.spider;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Host lifecycle ABI for the explicitly fingerprinted adapter, compiled into a separate DEX. */
public final class Init {
    public static final String v = "Banana TV";
    private static final Init INSTANCE = new Init();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newFixedThreadPool(5);
    private static Application application;
    private static Supplier<Activity> activity = () -> null;

    public static void configure(Context context, Supplier<Activity> foreground) {
        application = (Application) context.getApplicationContext();
        activity = foreground;
    }
    public static void init(Context context) { application = (Application) context.getApplicationContext(); }
    public static Init get() { return INSTANCE; }
    public static Application context() { return application; }
    public static Activity getActivity() {
        Activity current = activity.get();
        return current == null || current.isFinishing() || current.isDestroyed() ? null : current;
    }
    public static Activity getConfigActivity() { return null; }
    public static void execute(Runnable work) { WORK.execute(work); }
    public static void post(Runnable work) { MAIN.post(work); }
    public static void run(Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) work.run();
        else MAIN.post(work);
    }
    public static void run(Runnable work, int delay) { MAIN.postDelayed(work, delay); }
}
