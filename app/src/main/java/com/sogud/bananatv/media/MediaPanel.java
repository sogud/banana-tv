package com.sogud.bananatv.media;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sogud.bananatv.R;
import static com.sogud.bananatv.media.MediaViews.*;

/** Shared TV panels: source menus, forms and player choices use the same remote focus. */
final class MediaPanel extends Dialog {
    private final Activity activity;
    private final LinearLayout body;
    private final LinearLayout actions;
    private final TextView error;
    private View firstFocus;
    private int width = 520;

    MediaPanel(Activity activity, String title, String description) {
        super(activity, R.style.Theme_MediaPanel);
        this.activity = activity;
        LinearLayout surface = new LinearLayout(activity); surface.setOrientation(LinearLayout.VERTICAL);
        surface.setPadding(dp(activity, 28), dp(activity, 26), dp(activity, 28), dp(activity, 24));
        GradientDrawable background = shape(0xf51b1e25, dp(activity, 26));
        background.setStroke(dp(activity, 1), 0x22ffffff); surface.setBackground(background);
        TextView heading = text(activity, title, 27, 0xfff4f5f8);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        surface.addView(heading);
        if (description != null && !description.isEmpty()) {
            TextView detail = text(activity, description, 15, 0xffb7bdc8);
            detail.setLineSpacing(dp(activity, 4), 1); detail.setPadding(0, dp(activity, 10), 0, 0);
            surface.addView(detail);
        }
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int maximum = activity.getResources().getDisplayMetrics().heightPixels - dp(activity, 210);
                if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
                    maximum = Math.min(maximum, View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(Math.max(0, maximum), View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false); scroll.setClipToPadding(false); scroll.setClipChildren(false);
        body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setPadding(dp(activity, 8), dp(activity, 20), dp(activity, 8), dp(activity, 4));
        scroll.addView(body); surface.addView(scroll);
        error = text(activity, "", 14, 0xffffb9ad); error.setVisibility(View.GONE);
        error.setPadding(0, dp(activity, 12), 0, 0); surface.addView(error);
        actions = row(activity); actions.setGravity(Gravity.END); actions.setVisibility(View.GONE);
        actions.setClipChildren(false); actions.setClipToPadding(false);
        actions.setPadding(0, dp(activity, 20), 0, 0); surface.addView(actions);
        setContentView(surface); setCanceledOnTouchOutside(true);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setDimAmount(.62f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    void width(int value) { width = value; }
    void content(View view) { body.addView(view); }
    void error(String message) { error.setText(message); error.setVisibility(View.VISIBLE); }

    EditText input(String hint, int type) {
        EditText input = new EditText(activity); input.setSingleLine(); input.setInputType(type);
        input.setHint(hint); input.setTextSize(17);
        body.addView(input, new LinearLayout.LayoutParams(-1, dp(activity, 54)));
        firstFocus = input; return input;
    }

    void choice(String label, boolean selected, Runnable action) {
        item(label, selected ? "✓" : "", false, selected, action);
    }
    void toggle(String label, boolean checked, java.util.function.Consumer<Boolean> changed) {
        final LinearLayout[] item = new LinearLayout[1];
        item[0] = item(label, checked ? "✓" : "", true, checked, () -> {
            boolean enabled = !item[0].isSelected(); item[0].setSelected(enabled);
            ((TextView) item[0].getChildAt(1)).setText(enabled ? "✓" : "");
            changed.accept(enabled);
            item[0].sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        });
    }
    private LinearLayout item(String label, String value, boolean checkable, boolean checked, Runnable action) {
        LinearLayout item = row(activity); item.setFocusable(true); focusOnTouch(item);
        item.setClickable(true); item.setSelected(checked);
        item.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        TextView name = text(activity, label, 18, 0xfff4f5f8);
        TextView status = text(activity, value, 17, 0xffaeb6c5); status.setGravity(Gravity.END);
        item.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        item.addView(status, new LinearLayout.LayoutParams(-2, -2));
        item.setBackground(shape(Color.TRANSPARENT, dp(activity, 12)));
        item.setOnFocusChangeListener((view, focused) -> {
            view.setBackground(shape(focused ? 0xfff3f5f8 : Color.TRANSPARENT, dp(activity, 12)));
            name.setTextColor(focused ? 0xff161a21 : 0xfff4f5f8);
            status.setTextColor(focused ? 0xff414853 : 0xffaeb6c5);
            float scale = focused ? 1.015f : 1f;
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) view.animate().scaleX(scale).scaleY(scale).setDuration(130).start();
            else { view.setScaleX(scale); view.setScaleY(scale); }
        });
        item.setOnClickListener(view -> action.run());
        if (checkable) item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.CheckBox"); info.setCheckable(true); info.setChecked(host.isSelected());
            }
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(activity, 52));
        size.bottomMargin = dp(activity, 4); body.addView(item, size);
        if (firstFocus == null || (checked && !checkable)) firstFocus = item;
        return item;
    }
    void action(String label, Runnable action) {
        actions.setVisibility(View.VISIBLE);
        Button button = button(activity, label, action);
        focusOnTouch(button);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, dp(activity, 44));
        size.leftMargin = dp(activity, 10); actions.addView(button, size);
        if (firstFocus == null) firstFocus = button;
    }
    private void focusOnTouch(View view) {
        view.setFocusableInTouchMode(true);
        view.setOnTouchListener((target, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) target.requestFocus();
            return false;
        });
    }
    @Override public void show() {
        super.show();
        int available = activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 64);
        getWindow().setLayout(Math.min(dp(activity, width), available), -2);
        if (firstFocus != null) firstFocus.requestFocus();
    }
}
