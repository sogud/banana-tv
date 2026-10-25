package com.sogud.bananatv;

/** Dark glass colors sampled once from a wallpaper, independent of Android APIs. */
final class WallpaperPalette {
    static final WallpaperPalette DEFAULT = new WallpaperPalette(29, 38, 54);

    final int panelStart;
    final int panelEnd;
    final int headerStart;
    final int headerEnd;
    final int dockTint;

    private WallpaperPalette(int red, int green, int blue) {
        int neutral = Math.min(red, Math.min(green, blue)) * 3 / 4;
        red -= neutral;
        green -= neutral;
        blue -= neutral;
        int maximum = Math.max(1, Math.max(red, Math.max(green, blue)));
        int light = rgb(18 + red * 38 / maximum, 18 + green * 38 / maximum,
                18 + blue * 38 / maximum);
        int dark = rgb(10 + red * 22 / maximum, 10 + green * 22 / maximum,
                10 + blue * 22 / maximum);
        panelStart = 0xF0000000 | light;
        panelEnd = 0xF5000000 | dark;
        headerStart = 0xD8000000 | light;
        headerEnd = 0xE8000000 | dark;
        dockTint = 0x66000000 | dark;
    }

    static WallpaperPalette fromPixels(int[] pixels) {
        long red = 0, green = 0, blue = 0, totalWeight = 0;
        for (int color : pixels) {
            if ((color >>> 24) < 128) continue;
            int r = (color >> 16) & 255;
            int g = (color >> 8) & 255;
            int b = color & 255;
            int maximum = Math.max(r, Math.max(g, b));
            int minimum = Math.min(r, Math.min(g, b));
            // Keep black borders and bright highlights from washing out the tint.
            if (maximum < 24 || minimum > 235) continue;
            int weight = 16 + maximum - minimum;
            red += r * weight;
            green += g * weight;
            blue += b * weight;
            totalWeight += weight;
        }
        if (totalWeight == 0) return DEFAULT;
        return new WallpaperPalette((int) (red / totalWeight), (int) (green / totalWeight),
                (int) (blue / totalWeight));
    }

    private static int rgb(int red, int green, int blue) {
        return (red << 16) | (green << 8) | blue;
    }
}
