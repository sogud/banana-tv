package com.sogud.bananatv;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Verifies the first actual app draw has a wallpaper, including a cold start. */
public final class WallpaperStartupProbe extends Instrumentation {
    private final CountDownLatch drawn = new CountDownLatch(1);
    private final AtomicBoolean captured = new AtomicBoolean();
    private final Bundle result = new Bundle();
    private boolean checkSystemSettings;
    private boolean checkGlobalSettings;
    private boolean checkSystemScreensaver;
    private boolean checkWallpaperPeek;
    private boolean checkPinRace;
    private boolean checkFeatureParity;
    private boolean checkWallpaperQuality;
    private String homeRendering;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        homeRendering = arguments == null ? null : arguments.getString("home_rendering");
        checkPinRace = arguments != null && "true".equals(arguments.getString("pin_race"));
        checkWallpaperPeek = arguments != null && "true".equals(arguments.getString("wallpaper_peek"));
        checkSystemSettings = arguments != null && "true".equals(arguments.getString("system_settings"));
        checkGlobalSettings = arguments != null && "true".equals(arguments.getString("global_settings"));
        checkFeatureParity = arguments != null && "true".equals(arguments.getString("feature_parity"));
        checkWallpaperQuality = arguments != null && "true".equals(arguments.getString("wallpaper_quality"));
        checkSystemScreensaver = arguments != null && "true".equals(arguments.getString("system_screensaver"));
        start();
    }

    @Override
    public void onStart() {
        if (checkWallpaperQuality) {
            Bundle probe = WallpaperQualityProbe.run(getTargetContext());
            finish("PASS".equals(probe.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, probe);
            return;
        }
        if (homeRendering != null) {
            Bundle probe = HomeRenderingProbe.run(this, homeRendering);
            finish("PASS".equals(probe.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, probe);
            return;
        }
        if (checkPinRace) {
            Bundle probe = PinRaceProbe.run(this);
            finish("PASS".equals(probe.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, probe);
            return;
        }
        if (checkWallpaperPeek) {
            Bundle probe = WallpaperPeekProbe.run(this);
            finish("PASS".equals(probe.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, probe);
            return;
        }
        if (checkFeatureParity) {
            Bundle probe = FeatureParityProbe.run(getTargetContext());
            finish("PASS".equals(probe.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, probe);
            return;
        }
        if (checkSystemScreensaver) {
            Bundle probe = SystemScreensaverProbe.run(getTargetContext());
            finish("PASS".equals(probe.getString("result"))
                    ? Activity.RESULT_OK : Activity.RESULT_CANCELED, probe);
            return;
        }
        if (checkGlobalSettings) {
            Bundle probe = TvPreferencesProbe.runGlobal(getTargetContext());
            finish("PASS".equals(probe.getString("result"))
                    ? Activity.RESULT_OK : Activity.RESULT_CANCELED, probe);
            return;
        }
        if (checkSystemSettings) {
            Bundle probe = TvPreferencesProbe.run(getTargetContext());
            finish("PASS".equals(probe.getString("result"))
                    ? Activity.RESULT_OK : Activity.RESULT_CANCELED, probe);
            return;
        }
        Application application = (Application) getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
                if (!activity.getClass().getName().equals("com.sogud.bananatv.MainActivity")) return;
                View decor = activity.getWindow().getDecorView();
                decor.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
                    @Override
                    public void onDraw() {
                        if (!captured.compareAndSet(false, true)) return;
                        int id = activity.getResources().getIdentifier(
                                "wallpaper", "id", getTargetContext().getPackageName());
                        ImageView wallpaper = activity.findViewById(id);
                        int ambientId = activity.getResources().getIdentifier(
                                "ambientWallpaper", "id", getTargetContext().getPackageName());
                        View ambient = activity.findViewById(ambientId);
                        boolean imageReady = wallpaper != null && wallpaper.isShown() && wallpaper.getDrawable() != null;
                        boolean ambientReady = ambient instanceof AmbientWallpaperView && hasAmbientPixels(ambient);
                        result.putBoolean("first_draw_has_wallpaper",
                                imageReady || ambientReady);
                        result.putString("wallpaper_type", imageReady ? "image" : ambientReady ? "ambient" : "missing");
                        drawn.countDown();
                        decor.post(() -> decor.getViewTreeObserver().removeOnDrawListener(this));
                    }
                });
            }

            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        };
        application.registerActivityLifecycleCallbacks(callbacks);
        try {
            Intent intent = new Intent().setClassName(getTargetContext(), "com.sogud.bananatv.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivitySync(intent);
            boolean completed = drawn.await(10, TimeUnit.SECONDS);
            boolean passed = completed && result.getBoolean("first_draw_has_wallpaper");
            result.putString("result", passed ? "PASS" : "FAIL");
            result.putString("scope", "First application draw only; physical video output is not captured.");
            if (!completed) result.putString("error", "No app draw within 10 seconds");
            finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        } catch (Exception error) {
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks);
        }
    }

    private static boolean hasAmbientPixels(View ambient) {
        if (!ambient.isShown() || ambient.getWidth() <= 0 || ambient.getHeight() <= 0) return false;
        android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888);
        try {
            android.graphics.Canvas canvas = new android.graphics.Canvas(sample);
            canvas.scale(8f / ambient.getWidth(), 8f / ambient.getHeight());
            ambient.draw(canvas);
            int first = sample.getPixel(0, 0);
            boolean varied = false;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int pixel = sample.getPixel(x, y);
                if (android.graphics.Color.alpha(pixel) != 255) return false;
                if (pixel != first) varied = true;
            }
            return varied;
        } finally { sample.recycle(); }
    }
}
