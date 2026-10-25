package com.sogud.bananatv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import java.io.File;

/** Rasterize once during discovery; focus animation never decodes or samples an icon. */
final class AppArtwork {
    static Drawable tile(Context context, Drawable banner, Drawable icon, File custom) {
        if (custom != null && custom.isFile()) {
            Bitmap bitmap = MediaLibrary.decode(custom, 640, 384);
            if (bitmap != null) return new BitmapDrawable(context.getResources(), bitmap);
        }
        if (banner != null && banner.getIntrinsicWidth() >= banner.getIntrinsicHeight() * 1.4f) return banner;
        if (icon == null) return banner;
        Bitmap sample = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 64, 64);
        icon.draw(new Canvas(sample));
        int color = dominant(sample);
        sample.recycle();
        Bitmap bitmap = Bitmap.createBitmap(320, 192, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new LinearGradient(0, 0, 320, 192, color, darker(color), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 320, 192, paint);
        Drawable logo = icon instanceof AdaptiveIconDrawable ? ((AdaptiveIconDrawable) icon).getForeground() : icon;
        // Adaptive foregrounds include a large safe-zone inset; compensate for that inset.
        int size = icon instanceof AdaptiveIconDrawable ? 160 : 112;
        logo.setBounds(new Rect(160 - size / 2, 96 - size / 2, 160 + size / 2, 96 + size / 2));
        logo.draw(canvas);
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    static int dominant(Bitmap bitmap) {
        int[] bins = new int[512];
        long[] red = new long[512], green = new long[512], blue = new long[512];
        int best = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 2) for (int x = 0; x < bitmap.getWidth(); x += 2) {
            int color = bitmap.getPixel(x, y);
            if (Color.alpha(color) < 180) continue;
            int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
            int bin = (r >> 5) * 64 + (g >> 5) * 8 + (b >> 5);
            bins[bin]++; red[bin] += r; green[bin] += g; blue[bin] += b;
            if (bins[bin] > bins[best]) best = bin;
        }
        if (bins[best] == 0) return 0xFF25334A;
        return Color.rgb((int) (red[best] / bins[best]), (int) (green[best] / bins[best]), (int) (blue[best] / bins[best]));
    }

    private static int darker(int color) {
        return Color.rgb(Color.red(color) * 2 / 3, Color.green(color) * 2 / 3, Color.blue(color) * 2 / 3);
    }
}
