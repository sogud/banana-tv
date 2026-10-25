package com.sogud.bananatv.media;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class MediaViews {
    private MediaViews() { }
    static int dp(Activity activity, float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(radius); return drawable;
    }
    static TextView text(Activity activity, String value, int size, int color) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text;
    }
    static Button button(Activity activity, String label, Runnable action) {
        Button button = new Button(activity); button.setText(label); button.setTextSize(15); button.setAllCaps(false);
        button.setPadding(dp(activity, 18), 0, dp(activity, 18), 0); button.setMinHeight(dp(activity, 44));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, shape(0xfff4f5f8, dp(activity, 12)));
        background.addState(new int[]{android.R.attr.state_pressed}, shape(0xffdfe6f0, dp(activity, 12)));
        background.addState(new int[]{android.R.attr.state_selected}, shape(0xff45536b, dp(activity, 12)));
        background.addState(new int[]{}, shape(0xff252d3c, dp(activity, 12)));
        button.setBackground(background);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused}, new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{0xff10141d, 0xff10141d, Color.WHITE}));
        button.setOnFocusChangeListener((view, focused) -> {
            float scale = focused ? 1.035f : 1f;
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) view.animate().scaleX(scale).scaleY(scale).setDuration(130).start();
            else { view.setScaleX(scale); view.setScaleY(scale); }
        });
        button.setOnClickListener(view -> action.run()); return button;
    }
    static LinearLayout row(Activity activity) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL); return row;
    }
    static void addButton(Activity activity, LinearLayout row, String text, Runnable action) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(activity, 44)); params.setMargins(0, 0, dp(activity, 12), 0);
        row.addView(button(activity, text, action), params);
    }
}
