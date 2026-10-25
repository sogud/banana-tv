package com.sogud.bananatv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Hides Home chrome without detaching or restarting any wallpaper surface. */
final class WallpaperPeek {
    private final FrameLayout root;
    private final List<View> chrome = new ArrayList<>();
    private final List<Float> alphas = new ArrayList<>();
    private final List<Integer> accessibility = new ArrayList<>();
    private FrameLayout overlay;
    private View previousFocus;
    private long openingPress;

    WallpaperPeek(FrameLayout root) {
        this.root = root;
    }

    boolean isShowing() {
        return overlay != null;
    }

    void show(long downTime) {
        if (isShowing()) return;
        Context context = root.getContext();
        previousFocus = root.findFocus();
        openingPress = downTime;
        // Both the main controls and optional large clock are top-level LinearLayouts.
        // Wallpaper views, including their native video surface, stay in place.
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (!(child instanceof LinearLayout) && child.getId() != R.id.wallpaperShade) continue;
            chrome.add(child);
            alphas.add(child.getAlpha());
            accessibility.add(child.getImportantForAccessibility());
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            fade(child, 0f, motionEnabled());
        }

        overlay = new FrameLayout(context);
        overlay.setId(R.id.wallpaperPeek);
        overlay.setFocusableInTouchMode(true);
        overlay.setClickable(true);
        overlay.setContentDescription(context.getString(R.string.wallpaper_peek_exit));
        overlay.setOnClickListener(view -> dismiss(true));
        overlay.setOnKeyListener((view, code, event) -> {
            if (!isNavigationKey(code)) return false;
            // The release that finishes the opening long press must not close the view.
            if (code == KeyEvent.KEYCODE_BACK && event.getDownTime() == openingPress) return true;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) dismiss(true);
            return true;
        });

        TextView hint = new TextView(context);
        hint.setText(R.string.wallpaper_peek_exit);
        hint.setTextColor(0xEFFFFFFF);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams hintSize = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hintSize.bottomMargin = dp(32);
        overlay.addView(hint, hintSize);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.requestFocusFromTouch();
        overlay.announceForAccessibility(context.getString(R.string.wallpaper_peek_exit));
        hint.postDelayed(() -> {
            if (isShowing() && hint.isAttachedToWindow()) fade(hint, 0f, motionEnabled());
        }, 2500);
    }

    void dismiss(boolean animate) {
        if (!isShowing()) return;
        boolean motion = animate && motionEnabled();
        for (int i = 0; i < chrome.size(); i++) {
            View child = chrome.get(i);
            child.setImportantForAccessibility(accessibility.get(i));
            fade(child, alphas.get(i), motion);
        }
        root.removeView(overlay);
        overlay = null;
        chrome.clear();
        alphas.clear();
        accessibility.clear();
        if (previousFocus != null && previousFocus.isAttachedToWindow() && previousFocus.isShown()) {
            previousFocus.requestFocusFromTouch();
        }
        previousFocus = null;
    }

    private boolean motionEnabled() {
        return ValueAnimator.areAnimatorsEnabled() && root.getContext()
                .getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("icon_motion", true);
    }

    private static boolean isNavigationKey(int code) {
        return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MENU;
    }

    private static void fade(View view, float alpha, boolean animate) {
        view.animate().cancel();
        if (animate) view.animate().alpha(alpha).setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
        else view.setAlpha(alpha);
    }

    private int dp(int value) {
        return Math.round(value * root.getResources().getDisplayMetrics().density);
    }
}
