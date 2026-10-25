package com.sogud.bananatv;

/** Plain Java checks: no emulator, account, Android stubs or test dependency. */
public final class WallpaperPaletteTest {
    public static void main(String[] args) {
        WallpaperPalette warm = WallpaperPalette.fromPixels(new int[]{0xFFFFA040});
        WallpaperPalette cool = WallpaperPalette.fromPixels(new int[]{0xFF40A0FF});
        require(red(warm.panelStart) > blue(warm.panelStart), "Warm wallpaper lost its red tint");
        require(blue(cool.panelStart) > red(cool.panelStart), "Cool wallpaper lost its blue tint");
        require(WallpaperPalette.fromPixels(new int[0]) == WallpaperPalette.DEFAULT, "Empty fallback");
        require(WallpaperPalette.fromPixels(new int[]{0x00FF0000, 0x7FFF0000})
                == WallpaperPalette.DEFAULT, "Transparent pixels must not color the glass");
        WallpaperPalette bordered = WallpaperPalette.fromPixels(new int[]{
                0xFF000000, 0xFFFFFFFF, 0xFFFFA040, 0xFF000000, 0xFFFFFFFF});
        require(bordered.panelStart == warm.panelStart, "Borders changed the tint");
        WallpaperPalette neutral = WallpaperPalette.fromPixels(new int[]{0xFF808080});
        require(red(neutral.panelStart) == blue(neutral.panelStart), "Gray should stay neutral");
        int checked = 0;
        for (int r = 0; r < 256; r += 17) {
            for (int g = 0; g < 256; g += 17) {
                for (int b = 0; b < 256; b += 17) {
                    WallpaperPalette palette = WallpaperPalette.fromPixels(
                            new int[]{0xFF000000 | (r << 16) | (g << 8) | b});
                    for (int surface : new int[]{palette.panelStart, palette.panelEnd,
                            palette.headerStart, palette.headerEnd}) {
                        require(whiteContrastOnWhiteBackdrop(surface) >= 7.0,
                                "White text contrast below 7:1");
                    }
                    checked++;
                }
            }
        }
        System.out.println("PASS: warm/cool, empty/transparent fallback, borders, neutral; "
                + checked + " RGB samples keep white text contrast >= 7:1");
    }

    private static int red(int color) { return (color >> 16) & 255; }
    private static int blue(int color) { return color & 255; }

    private static double whiteContrastOnWhiteBackdrop(int color) {
        double alpha = (color >>> 24) / 255.0;
        double r = channel(red(color) * alpha + 255 * (1 - alpha));
        double g = channel(((color >> 8) & 255) * alpha + 255 * (1 - alpha));
        double b = channel(blue(color) * alpha + 255 * (1 - alpha));
        return 1.05 / (0.2126 * r + 0.7152 * g + 0.0722 * b + 0.05);
    }

    private static double channel(double component) {
        double value = component / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
