package com.github.catvod;

import android.app.Activity;
import android.content.Context;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Interoperability entry points. The application owns and clears the UI providers. */
public final class Runtime {
    private static volatile Context context;
    private static volatile Supplier<Activity> activity;
    private static volatile Consumer<String> toast;

    private Runtime() { }

    public static void set(Context value) {
        context = value == null ? null : value.getApplicationContext();
    }

    public static Context context() { return context; }
    public static void setActivity(Supplier<Activity> value) { activity = value; }
    public static Activity activity() {
        Supplier<Activity> provider = activity;
        return provider == null ? null : provider.get();
    }

    public static void setToast(Consumer<String> value) { toast = value; }
    public static void toast(String message) {
        Consumer<String> consumer = toast;
        if (consumer != null) consumer.accept(message);
    }
}
