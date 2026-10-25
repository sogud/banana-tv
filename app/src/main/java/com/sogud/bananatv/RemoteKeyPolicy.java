package com.sogud.bananatv;

import android.view.KeyEvent;

/** Shortcut/media keys only: navigation, power, audio, voice and typing stay available. */
final class RemoteKeyPolicy {
    static boolean canMap(int code) {
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) return true;
        if (code >= KeyEvent.KEYCODE_VIDEO_APP_1 && code <= KeyEvent.KEYCODE_DEMO_APP_4) return true;
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_EXPLORER:
            case KeyEvent.KEYCODE_ENVELOPE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_TV:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_BOOKMARK:
            case KeyEvent.KEYCODE_CAPTIONS:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_TV_INPUT:
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_MEDIA_TOP_MENU:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
            case KeyEvent.KEYCODE_TV_CONTENTS_MENU:
            case KeyEvent.KEYCODE_ALL_APPS:
                return true;
            default: return false;
        }
    }
}
