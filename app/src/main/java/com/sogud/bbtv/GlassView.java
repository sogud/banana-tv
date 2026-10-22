package com.sogud.bbtv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Lighting attached to its card, so it stays aligned during focus and scrolling. */
public class GlassView extends View {
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float density;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(0.6f * density);
        rim.setColor(0x55FFFFFF);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        sheen.setShader(new LinearGradient(0, 0, w * 0.7f, h,
                new int[]{0x20FFFFFF, 0x04FFFFFF, 0x00000000}, null, Shader.TileMode.CLAMP));
        float inset = 0.6f * density;
        bounds.set(inset, inset, w - inset, h - inset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = 14f * density;
        canvas.drawRoundRect(bounds, radius, radius, sheen);
        canvas.drawRoundRect(bounds, radius, radius, rim);
    }
}
