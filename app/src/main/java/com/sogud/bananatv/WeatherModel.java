package com.sogud.bananatv;

/** Bounds and age checks shared by API parsing and cached weather. */
final class WeatherModel {
    static final long FRESH_MS = 30 * 60_000L;
    static final long MAX_AGE_MS = 2 * 60 * 60_000L;

    static boolean coordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    static boolean usable(double temperature, long observedAt, long now) {
        return Double.isFinite(temperature) && observedAt > 0
                && now - observedAt >= -15 * 60_000L && now - observedAt <= MAX_AGE_MS;
    }

    static int iconGroup(int code, boolean day) {
        if (code == 0) return day ? 0 : 1;
        if (code >= 1 && code <= 3) return 2;
        if (code == 45 || code == 48) return 3;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return 5;
        if (code >= 95 && code <= 99) return 6;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return 4;
        return 2;
    }
}
