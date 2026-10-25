package com.sogud.bananatv;

import android.view.KeyEvent;
import java.util.Arrays;

public final class HomeFeaturesTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (int style = 0; style < 5; style++) {
            int[] night = AmbientColors.at(style, 80, 0);
            int[] noon = AmbientColors.at(style, 80, 720);
            check(!Arrays.equals(night, noon), "Wallpaper must change from night to noon");
            check(!Arrays.equals(noon, AmbientColors.at(style, 240, 720)), "Date must change accent");
            check(Arrays.equals(noon, AmbientColors.at(style, 80, 720)), "Palette must be deterministic");
            for (int color : noon) check((color >>> 24) == 255, "Opaque background required");
        }
        check(WeatherModel.coordinates(52.52, 13.41), "Valid city");
        check(!WeatherModel.coordinates(Double.NaN, 13.41), "Reject invalid latitude");
        check(!WeatherModel.coordinates(91, 0), "Reject out-of-range latitude");
        check(!WeatherModel.coordinates(0, -181), "Reject out-of-range longitude");
        long now = 2_000_000_000_000L;
        check(WeatherModel.usable(18.5, now - 60_000, now), "Fresh weather");
        check(!WeatherModel.usable(Double.NaN, now, now), "Reject null/non-finite temperature");
        check(!WeatherModel.usable(18, now - WeatherModel.MAX_AGE_MS - 1, now), "Expire stale weather");
        check(!WeatherModel.usable(18, now + 16 * 60_000, now), "Reject future observation");
        check(WeatherModel.iconGroup(0, true) != WeatherModel.iconGroup(0, false), "Night glyph");
        check(WeatherModel.iconGroup(75, true) == 5, "Snow glyph");
        check(WeatherModel.iconGroup(95, true) == 6, "Storm glyph");
        check(RemoteKeyPolicy.canMap(KeyEvent.KEYCODE_PROG_RED), "Allow color shortcut");
        check(RemoteKeyPolicy.canMap(KeyEvent.KEYCODE_VIDEO_APP_1), "Allow app shortcut");
        int[] reserved = {KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_VOICE_ASSIST,
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_UNKNOWN};
        for (int code : reserved) check(!RemoteKeyPolicy.canMap(code), "Reserved key " + code);
        System.out.println("PASS: adaptive palettes, weather age/coordinate failures and protected remote keys");
    }
}
