package com.sogud.bananatv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.util.Calendar;

/** Two moving gradients; no video decoder, network or per-frame bitmap allocations. */
public final class AmbientWallpaperView extends View {
    private final Paint background = new Paint();
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Calendar calendar = Calendar.getInstance();
    private int style;
    private long paletteMinute = Long.MIN_VALUE;
    private boolean running;
    private boolean motion;

    public AmbientWallpaperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setStyle(int value) {
        if (style != value) {
            style = value;
            paletteMinute = Long.MIN_VALUE;
        }
        invalidate();
    }

    void setRunning(boolean value) {
        running = value;
        motion = LauncherPreferences.motionEnabled(getContext());
        if (value) invalidate();
    }

    int[] colors() {
        calendar.setTimeInMillis(System.currentTimeMillis());
        return AmbientColors.at(style, calendar.get(Calendar.DAY_OF_YEAR),
                calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        paletteMinute = Long.MIN_VALUE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long minute = System.currentTimeMillis() / 60_000L;
        if (minute != paletteMinute) {
            paletteMinute = minute;
            int[] colors = colors();
            background.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                    colors[0], colors[1], Shader.TileMode.CLAMP));
            glow.setShader(new RadialGradient(0, 0, Math.max(1, getWidth() * 0.65f),
                    new int[]{colors[2], colors[2] & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), background);
        double phase = motion ? SystemClock.uptimeMillis() / 40_000.0 : 0;
        matrix.setTranslate(getWidth() * (0.55f + 0.18f * (float) Math.sin(phase)),
                getHeight() * (0.38f + 0.12f * (float) Math.cos(phase * 0.7)));
        glow.getShader().setLocalMatrix(matrix);
        canvas.drawRect(0, 0, getWidth(), getHeight(), glow);
        if (running && isShown() && getWindowVisibility() == VISIBLE) {
            if (motion) postInvalidateOnAnimation();
            else postInvalidateDelayed(60_000L);
        }
    }
}
