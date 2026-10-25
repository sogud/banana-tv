package com.sogud.bananatv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

/** Palette values adapted from Aerial, copyright 2026 pengchujin (MIT). */
final class GradientWallpapers {
    static final String[] KEYS = {"aurora", "sunset", "ocean", "violet", "forest", "dune", "rose", "graphite"};
    static final int[] BASE = {0xFF070B1E, 0xFF17081A, 0xFF020F1E, 0xFF0E0824,
            0xFF031109, 0xFF1A110A, 0xFF1A0C10, 0xFF0B0B0D};
    private static final int[][] COLORS = {
            {0xFF1FD1A5, 0xFF5B3FD9, 0xFF0E6FFF}, {0xFFFF6B3D, 0xFFFF2E7E, 0xFFFFC15E},
            {0xFF0A5BD8, 0xFF00B3C6, 0xFF213E9A}, {0xFF8E5CFF, 0xFFFF5CAA, 0xFF3D2DB3},
            {0xFF1F8A5B, 0xFF9BD15A, 0xFF0E4D3C}, {0xFFE3A35C, 0xFFC8643B, 0xFFF2D3A0},
            {0xFFF2A0A8, 0xFFD46A7E, 0xFFF7D4C2}, {0xFF3A3A44, 0xFF5E5E6A, 0xFF23232C}};
    private static final float[][] CENTERS = {
            {.18f,.32f,.42f, .74f,.22f,.48f, .52f,.86f,.5f},
            {.24f,.62f,.46f, .7f,.3f,.44f, .5f,.98f,.4f},
            {.3f,.25f,.5f, .78f,.7f,.42f, .1f,.9f,.4f},
            {.28f,.3f,.46f, .82f,.55f,.38f, .55f,.95f,.5f},
            {.22f,.4f,.46f, .76f,.2f,.34f, .62f,.85f,.5f},
            {.3f,.3f,.44f, .78f,.62f,.44f, .12f,.92f,.34f},
            {.7f,.28f,.42f, .25f,.6f,.46f, .55f,.95f,.3f},
            {.25f,.25f,.5f, .8f,.5f,.42f, .5f,.95f,.5f}};

    static int index(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }

    static Bitmap render(int index, int width, int height) {
        index = Math.max(0, Math.min(index, KEYS.length - 1));
        Bitmap small = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        canvas.drawColor(BASE[index]);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        for (int i = 0; i < 3; i++) {
            float x = CENTERS[index][i * 3] * 320;
            float y = CENTERS[index][i * 3 + 1] * 180;
            float radius = CENTERS[index][i * 3 + 2] * 320;
            int color = COLORS[index][i];
            paint.setShader(new RadialGradient(x, y, radius, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, radius, paint);
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        paint.setShader(null);
        new Canvas(result).drawBitmap(small, null, new Rect(0, 0, width, height), paint);
        small.recycle();
        return result;
    }
}
