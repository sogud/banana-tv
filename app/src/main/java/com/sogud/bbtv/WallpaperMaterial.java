package com.sogud.bbtv;

import android.graphics.Bitmap;
import android.graphics.Color;

/** A small cached wallpaper blur. Called only by the background wallpaper loader. */
final class WallpaperMaterial {
    static WallpaperPalette palette(Bitmap material) {
        if (material == null) return WallpaperPalette.DEFAULT;
        int[] samples = new int[16 * 9];
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int sampleX = (2 * x + 1) * material.getWidth() / 32;
                int sampleY = (2 * y + 1) * material.getHeight() / 18;
                samples[y * 16 + x] = material.getPixel(sampleX, sampleY);
            }
        }
        return WallpaperPalette.fromPixels(samples);
    }

    static Bitmap blur(Bitmap wallpaper) {
        int width = 192;
        int height = Math.max(1, wallpaper.getHeight() * width / wallpaper.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(wallpaper, width, height, true);
        int[] source = new int[width * height];
        int[] temporary = new int[source.length];
        int[] result = new int[source.length];
        small.getPixels(source, 0, width, 0, 0, width, height);
        blurPass(source, temporary, width, height, true);
        blurPass(temporary, result, width, height, false);
        Bitmap blurred = Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888);
        if (small != wallpaper) small.recycle();
        return blurred;
    }

    private static void blurPass(int[] source, int[] output, int width, int height, boolean horizontal) {
        int radius = 7;
        int samples = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0;
                for (int offset = -radius; offset <= radius; offset++) {
                    int sampleX = horizontal ? Math.max(0, Math.min(width - 1, x + offset)) : x;
                    int sampleY = horizontal ? y : Math.max(0, Math.min(height - 1, y + offset));
                    int color = source[sampleY * width + sampleX];
                    red += Color.red(color);
                    green += Color.green(color);
                    blue += Color.blue(color);
                }
                output[y * width + x] = Color.rgb(red / samples, green / samples, blue / samples);
            }
        }
    }
}
