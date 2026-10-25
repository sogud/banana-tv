package com.sogud.bananatv;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Original monochrome weather glyphs, drawn as small vector paths. */
final class WeatherIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int group;
    private ColorStateList tint;
    WeatherIconDrawable(int code, boolean day) {
        group = WeatherModel.iconGroup(code, day);
        paint.setColor(0xFFF6F7FA);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public boolean isStateful() { return tint != null && tint.isStateful(); }
    @Override public void setTintList(ColorStateList tint) {
        this.tint = tint;
        onStateChange(getState());
    }
    @Override protected boolean onStateChange(int[] state) {
        int color = tint == null ? 0xFFF6F7FA : tint.getColorForState(state, tint.getDefaultColor());
        if (paint.getColor() == color) return false;
        paint.setColor(color);
        invalidateSelf();
        return true;
    }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 32f, bounds.height() / 32f);
        paint.setStyle(Paint.Style.STROKE);
        if (group == 0) {
            canvas.drawCircle(16, 16, 6, paint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                canvas.drawLine(16 + (float) Math.cos(angle) * 10, 16 + (float) Math.sin(angle) * 10,
                        16 + (float) Math.cos(angle) * 13, 16 + (float) Math.sin(angle) * 13, paint);
            }
        } else if (group == 1) {
            path.reset(); path.moveTo(22, 5); path.cubicTo(8, 1, 1, 22, 15, 27);
            path.cubicTo(23, 30, 29, 22, 28, 18); path.cubicTo(17, 22, 12, 10, 22, 5);
            canvas.drawPath(path, paint);
        } else {
            path.reset(); path.moveTo(8, 21); path.cubicTo(0, 21, 2, 10, 10, 12);
            path.cubicTo(11, 3, 25, 3, 25, 14); path.cubicTo(32, 13, 33, 23, 25, 23);
            path.lineTo(9, 23); canvas.drawPath(path, paint);
            if (group == 4) {
                for (int x = 10; x <= 24; x += 7) canvas.drawLine(x, 26, x - 2, 30, paint);
            } else if (group == 5) {
                for (int x = 10; x <= 24; x += 7) {
                    canvas.drawLine(x, 26, x, 31, paint); canvas.drawLine(x - 2, 28, x + 2, 28, paint);
                }
            } else if (group == 6) {
                path.reset(); path.moveTo(17, 20); path.lineTo(13, 27); path.lineTo(18, 27);
                path.lineTo(15, 32); canvas.drawPath(path, paint);
            } else if (group == 3) {
                canvas.drawLine(5, 27, 27, 27, paint); canvas.drawLine(9, 31, 23, 31, paint);
            }
        }
        canvas.restoreToCount(saved);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
