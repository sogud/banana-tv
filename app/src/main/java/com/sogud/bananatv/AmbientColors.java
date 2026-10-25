package com.sogud.bananatv;

/** Offline palettes: local time controls light, the day of year changes the accent. */
final class AmbientColors {
    private static final int[][] STYLES = {
            {0xFF143746, 0xFF247C80, 0xFF8170AC},
            {0xFF10284B, 0xFF36699C, 0xFF51A7B0},
            {0xFF36243C, 0xFF96536C, 0xFFE4A491},
            {0xFF352B26, 0xFF9B6B49, 0xFFCFAC76},
            {0xFF292542, 0xFF675B9B, 0xFFB58FC2}
    };

    static int[] at(int style, int dayOfYear, double minutes) {
        int[] base = STYLES[Math.max(0, Math.min(STYLES.length - 1, style))];
        double phase = (minutes % 1440 + 1440) % 1440;
        double daylight = Math.max(0, Math.sin((phase - 360) * Math.PI / 720));
        double seasonal = (Math.sin(dayOfYear * Math.PI * 2 / 366.0) + 1) / 2;
        int top = mix(0xFF080D20, base[0], 0.36 + daylight * 0.64);
        int bottom = mix(base[0], base[1], 0.24 + daylight * 0.64);
        int accent = mix(base[1], base[2], 0.28 + seasonal * 0.44);
        return new int[]{top, bottom, accent};
    }

    private static int mix(int a, int b, double amount) {
        int result = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int start = (a >> shift) & 255;
            int end = (b >> shift) & 255;
            result |= ((int) Math.round(start + (end - start) * amount)) << shift;
        }
        return result;
    }
}
