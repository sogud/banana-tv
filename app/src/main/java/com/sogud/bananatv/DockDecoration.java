package com.sogud.bananatv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Wallpaper-tinted material behind the pinned row, with no per-frame blur. */
public class DockDecoration extends RecyclerView.ItemDecoration {
    private final int dockSize;
    private final float density;
    private final Paint material = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final int[] location = new int[2];
    private Bitmap wallpaper;
    private float shaderTop = Float.NaN;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        material.setColor(0x66303A4D);
        tint.setColor(0x44141C2C);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density * 0.5f);
        edge.setColor(0x26FFFFFF);
    }

    void setWallpaper(Bitmap bitmap) {
        wallpaper = bitmap;
        material.setShader(bitmap == null ? null : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    void setPalette(WallpaperPalette palette) {
        tint.setColor(palette.dockTint);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position >= 0 && position < dockSize) {
            outRect.top = (int) (10 * density);
            outRect.bottom = (int) (16 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean found = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position < 0 || position >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float left = child.getLeft() + card.getLeft();
            float top = child.getTop() + card.getTop() + child.getTranslationY();
            if (!found) {
                rect.set(left, top, left + card.getWidth(), top + card.getHeight());
                found = true;
            } else rect.union(left, top, left + card.getWidth(), top + card.getHeight());
        }
        if (!found) return;
        rect.inset(-16 * density, -16 * density);
        if (wallpaper != null) {
            View screen = parent.getRootView();
            float scale = Math.max((float) screen.getWidth() / wallpaper.getWidth(),
                    (float) screen.getHeight() / wallpaper.getHeight());
            parent.getLocationInWindow(location);
            matrix.setScale(scale, scale);
            matrix.postTranslate((screen.getWidth() - wallpaper.getWidth() * scale) / 2f - location[0],
                    (screen.getHeight() - wallpaper.getHeight() * scale) / 2f - location[1]);
            material.getShader().setLocalMatrix(matrix);
        }
        if (shaderTop != rect.top) {
            shaderTop = rect.top;
            light.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    0x20FFFFFF, 0x04FFFFFF, Shader.TileMode.CLAMP));
        }
        float radius = 30 * density;
        canvas.drawRoundRect(rect, radius, radius, material);
        canvas.drawRoundRect(rect, radius, radius, tint);
        canvas.drawRoundRect(rect, radius, radius, light);
        canvas.drawRoundRect(rect, radius, radius, edge);
    }
}
