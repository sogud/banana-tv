package com.sogud.bananatv;

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
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        scene = new ScreensaverScene(this);
        scene.setOnClickListener(view -> finish());
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

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        if (scene != null && scene.handleKey(code, event)) return true;
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || code == android.view.KeyEvent.KEYCODE_VOLUME_MUTE || code == android.view.KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) finish();
        return true;
    }
}
