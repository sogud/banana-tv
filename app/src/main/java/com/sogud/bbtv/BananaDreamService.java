package com.sogud.bbtv;

import android.service.dreams.DreamService;

/** Available to Android TV's system screen saver chooser. */
public final class BananaDreamService extends DreamService {
    private ScreensaverScene scene;
    private static java.lang.ref.WeakReference<BananaDreamService> active = new java.lang.ref.WeakReference<>(null);

    static boolean isRunning() {
        return active.get() != null;
    }

    static void finishIfRunning() {
        BananaDreamService dream = active.get();
        if (dream != null) dream.finish();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        scene = new ScreensaverScene(this);
        setContentView(scene);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        active = new java.lang.ref.WeakReference<>(this);
        MainActivity.systemDreamChanged();
        scene.setRunning(true);
    }

    @Override
    public void onDreamingStopped() {
        scene.setRunning(false);
        if (active.get() == this) active.clear();
        MainActivity.systemDreamChanged();
        super.onDreamingStopped();
    }
}
