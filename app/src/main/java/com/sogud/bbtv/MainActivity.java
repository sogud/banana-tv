package com.sogud.bbtv;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.lang.ref.WeakReference;

/**
 * Wallpaper-led TV home with a pinned row and directional app navigation.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;
    private MediaLibrary media;
    private PhoneServer phoneServer;
    private int movingTile = -1;
    private final java.util.concurrent.ExecutorService featureWorker = java.util.concurrent.Executors.newFixedThreadPool(2);
    private volatile List<AppAdapter.Entry> phoneApps = java.util.Collections.emptyList();
    private final android.content.BroadcastReceiver wakeSource = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && HomeOptions.flag(context, "wake_input", false)) {
                InputSources.route(context, HomeOptions.text(context, "boot_source", ""));
            }
        }
    };

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private int pinRequest;
    private WallpaperPeek wallpaperPeek;
    private DockDecoration dockDecoration;
    private TextView btnControls;
    private String networkLabel;
    private TextView btnWeather;
    private WeatherController weather;
    private Dialog weatherPanel;
    private Dialog remoteCapturePanel;
    private int capturedRemoteKey = -1;
    private int lastFocused = 0;
    private int focusRequest;
    private float density;
    private int wallpaperRequest;
    private boolean wallpaperReady;
    private WallpaperPalette wallpaperPalette = WallpaperPalette.DEFAULT;
    private Dialog activePanel;
    private final java.util.Map<String, Integer> menuPositions = new java.util.HashMap<>();
    private PopupWindow activeInputs;
    private VideoWallpaper videoWallpaper;
    private AmbientWallpaperView ambientWallpaper;
    private ScreensaverScene screensaverScene;
    private boolean systemScreensaverEnabled;
    private int systemScreensaverMinutes;
    private boolean resumed;
    private static WeakReference<MainActivity> visibleHome = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable startScreensaver = () -> {
        if (wallpaperPeek != null && wallpaperPeek.isShowing()) return;
        if (!resumed || !hasWindowFocus() || activePanel != null
                || (activeInputs != null && activeInputs.isShowing())) return;
        if (systemScreensaverEnabled) {
            if (SystemScreensaver.enabled(this)) open(SystemScreensaver.previewIntent());
        } else showScreensaver(false);
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateWifiLabel();
            updateLargeClock();
            weather.refresh(false);
            refreshDailyInBackground();
            if (media.selected().equals("legacy") && WallpaperCatalog.mode(MainActivity.this) >= WallpaperCatalog.AMBIENT) updateAmbientMaterial();
            if (media.selected().equals("daily:latest") || media.selected().equals("photos:rotate")) loadWallpaper();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isHomeRequest(getIntent())) ParentalLock.relock();
        WindowManager.LayoutParams windowParams = getWindow().getAttributes();
        windowParams.preferredRefreshRate = 60f;
        getWindow().setAttributes(windowParams);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = HomeOptions.columns(this);
        media = new MediaLibrary(this);
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        // Keep the starting window until the first app frame has its wallpaper.
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!wallpaperReady) return false;
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                return true;
            }
        });
        videoWallpaper = findViewById(R.id.videoWallpaper);
        ambientWallpaper = findViewById(R.id.ambientWallpaper);
        btnControls = findViewById(R.id.btnControls);
        btnWeather = findViewById(R.id.btnWeather);
        weather = new WeatherController(this, (snapshot, failed) -> {
            updateWeather(snapshot, failed);
            if (weatherPanel != null && weatherPanel == activePanel) {
                weatherPanel.dismiss();
                showWeatherSettings();
            }
        });
        updateHeaderColors(null);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(COLUMNS, density);
        grid.addItemDecoration(dockDecoration);
        loadWallpaper();
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) { updateLargeClock(); }
        });

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            updateDockSize();
            int dockRow = dp(96 * adapter.tileScale() + (HomeOptions.text(this, "name_style", "hidden").equals("hidden") ? 28 : 52) + 10 + 16);
            int want = HomeOptions.text(this, "layout", "dock").equals("grid")
                    ? Math.max(dp(120), h / 2 - dp(10)) : Math.max(0, h - dockRow);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(wakeSource,
                new android.content.IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(wakeSource, new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));
        updateLargeClock();
        btnWeather.setOnClickListener(v -> protect(this::showWeatherSettings));
        btnControls.setOnClickListener(v -> showControlCenter());

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusTile(lastFocused);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnWeather) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnControls;
        };
        btnWeather.setOnKeyListener(pillKeys);
        btnControls.setOnKeyListener(pillKeys);


    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isHomeRequest(intent)) returnToHome();
    }

    private static boolean isHomeRequest(Intent intent) {
        return Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME)
                || intent.getBooleanExtra(HomeIntegration.HOME_REQUEST, false);
    }

    private void returnToHome() {
        pinRequest++;
        if (wallpaperPeek != null) wallpaperPeek.dismiss(false);
        if (activePanel != null) activePanel.dismiss();
        if (activeInputs != null) activeInputs.dismiss();
        ParentalLock.relock();
        stopPhone();
        movingTile = -1;
        lastFocused = 0;
        grid.post(() -> focusTile(0));
        resetScreensaver();
    }

    static boolean handleHomeIfVisible() {
        MainActivity home = visibleHome.get();
        if (home == null) return false;
        home.returnToHome();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        visibleHome = new WeakReference<>(this);
        HomeIntegration.syncRecovery(this);
        updateVideoWallpaper();
        if (screensaverScene != null) screensaverScene.setRunning(true);
        refreshScreensaverTimer();
        adapter.reload(() -> grid.post(() -> {
            updateDockSize();
            phoneApps = adapter.entries();
            focusTile(lastFocused);
        }));
        updateWifiLabel();
        updateWeather(weather.snapshot(), weather.failed());
        weather.refresh(false);
        updateDockSize();
        handler.postDelayed(minuteTick, MINUTE_MS);

        updateLargeClock();
        if (HomeIntegration.isSystemHome(this) || HomeIntegration.enabled(this)) InputSources.routeBoot(this);
        refreshDailyInBackground();
        featureWorker.execute(() -> ReleaseUpdates.refresh(this));
        // Return to the tile we left from, or the first one.
        grid.post(() -> {
            View focused = getCurrentFocus();
            if (focused != null && grid.findContainingViewHolder(focused) != null) return;
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        pinRequest++;
        if (wallpaperPeek != null) wallpaperPeek.dismiss(false);
        resumed = false;
        if (visibleHome.get() == this) visibleHome.clear();
        if (screensaverScene != null) screensaverScene.setRunning(false);
        handler.removeCallbacks(startScreensaver);
        super.onPause();
        handler.removeCallbacks(minuteTick);
    }

    @Override
    protected void onStop() {
        // Native Home briefly pauses the activity without hiding its wallpaper.
        videoWallpaper.setRunning(false);
        ambientWallpaper.setRunning(false);
        super.onStop();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetScreensaver();
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && activePanel == null
                && (activeInputs == null || !activeInputs.isShowing())) {
            if (wallpaperPeek == null) wallpaperPeek = new WallpaperPeek(root);
            wallpaperPeek.show(event.getDownTime());
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) resetScreensaver();
        if (hasFocus && movingTile >= 0) grid.postOnAnimation(() -> focusTile(movingTile));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (movingTile >= 0) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) onTileVerticalKey(movingTile, code);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) onTileActivate(movingTile);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void resetScreensaver() {
        handler.removeCallbacks(startScreensaver);
        if (BananaDreamService.isRunning()) return;
        if (systemScreensaverEnabled && !SystemScreensaver.usesXiaomiTimer()) return;
        // The Xiaomi video detector also sees our wallpaper, so Home must handle its own idle time.
        int minutes = systemScreensaverEnabled ? systemScreensaverMinutes
                : LauncherPreferences.screensaverMinutes(this);
        if (resumed && minutes > 0) handler.postDelayed(startScreensaver, minutes * 60_000L);
    }

    private void refreshScreensaverTimer() {
        systemScreensaverEnabled = SystemScreensaver.enabled(this);
        systemScreensaverMinutes = systemScreensaverEnabled ? SystemScreensaver.minutes(this) : 0;
        resetScreensaver();
    }

    private void updateVideoWallpaper() {
        int mode = media.selected().equals("legacy") ? WallpaperCatalog.mode(this) : WallpaperCatalog.STILL;
        boolean visible = resumed && screensaverScene == null && !BananaDreamService.isRunning();
        videoWallpaper.setVisibility(mode == WallpaperCatalog.VIDEO ? View.VISIBLE : View.GONE);
        videoWallpaper.setRunning(visible && mode == WallpaperCatalog.VIDEO);
        ambientWallpaper.setVisibility(mode >= WallpaperCatalog.AMBIENT ? View.VISIBLE : View.GONE);
        ambientWallpaper.setStyle(Math.max(0, mode - WallpaperCatalog.AMBIENT));
        ambientWallpaper.setRunning(visible && mode >= WallpaperCatalog.AMBIENT);
    }

    static void systemDreamChanged() {
        MainActivity home = visibleHome.get();
        if (home == null) return;
        home.updateVideoWallpaper();
        home.resetScreensaver();
    }

    static boolean isHomeVisible() {
        return visibleHome.get() != null;
    }

    @Override
    protected void onDestroy() {
        if (visibleHome.get() == this) visibleHome.clear();
        stopPhone();
        unregisterReceiver(wakeSource);
        featureWorker.shutdownNow();
        adapter.close();
        weather.close();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && event.getRepeatCount() == 0) {
            View focused = getCurrentFocus();
            if (focused != null && grid.findContainingViewHolder(focused) != null) onTileAction(lastFocused);
            else showLauncherSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private interface MenuAction {
        void select(int index);
    }

    private LinearLayout panelContent(Dialog dialog, String title) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(20));
        content.setBackgroundResource(R.drawable.panel_glass);
        tintPanel(content);
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(26);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        heading.setPadding(dp(12), 0, 0, dp(20));
        content.addView(heading);
        dialog.setContentView(content);
        return content;
    }

    private void showPanel(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.25f;
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.width = dp(336);
        params.height = getResources().getDisplayMetrics().heightPixels - dp(48);
        params.x = dp(24);
        window.setAttributes(params);
        activePanel = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (activePanel == dialog) activePanel = null;
            root.post(() -> {
                View focused = getCurrentFocus();
                if (focused != null && focused.getId() == R.id.card) onTileFocused(lastFocused, focused);
                else if (focused == null || focused == grid) focusTile(lastFocused);
            });
        });
        dialog.show();
    }

    private Dialog showMenu(String title, String description, String[] labels, String[] values,
                          Runnable parent, MenuAction action) {
        Dialog dialog = new Dialog(this);
        LinearLayout content = panelContent(dialog, title);
        if (description != null) {
            TextView explanation = new TextView(this);
            explanation.setText(description);
            explanation.setTextColor(0xDDFFFFFF);
            explanation.setTextSize(14);
            explanation.setLineSpacing(dp(3), 1f);
            explanation.setPadding(dp(6), 0, dp(6), dp(22));
            content.addView(explanation);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setId(View.generateViewId());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackgroundResource(R.drawable.panel_row);
            row.setFocusable(true);
            row.setClickable(true);
            String value = values == null ? null : values[i];
            row.setContentDescription(labels[i] + (value == null ? "" : "，" + value));
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextSize(16);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setTextColor(getColorStateList(R.color.panel_text));
            label.setDuplicateParentStateEnabled(true);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView detail = new TextView(this);
            detail.setText(value == null ? "›" : value);
            detail.setTextSize(value == null ? 22 : 14);
            detail.setTextColor(getColorStateList(R.color.panel_text));
            detail.setAlpha(0.76f);
            detail.setDuplicateParentStateEnabled(true);
            detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-2, -2);
            detailParams.leftMargin = dp(12);
            row.addView(detail, detailParams);
            row.setNextFocusLeftId(row.getId());
            row.setNextFocusRightId(row.getId());
            if (i == 0) row.setNextFocusUpId(row.getId());
            if (i == labels.length - 1) row.setNextFocusDownId(row.getId());
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-1, dp(44));
            item.bottomMargin = dp(4);
            rows.addView(row, item);
            row.setOnFocusChangeListener((view, focused) -> {
                if (focused) menuPositions.put(title, index);
            });
            row.setOnClickListener(v -> { dialog.dismiss(); action.select(index); });
        }
        setPanelBack(dialog, parent);
        showPanel(dialog);
        if (rows.getChildCount() > 0) {
            int selected = Math.min(menuPositions.getOrDefault(title, 0), rows.getChildCount() - 1);
            rows.getChildAt(selected).requestFocusFromTouch();
        }
        return dialog;
    }

    private void showControlCenter() {
        updateWifiLabel();
        showMenu(getString(R.string.control_center), null,
                new String[]{getString(R.string.inputs), getString(R.string.volume_title),
                        getString(R.string.brightness_title), getString(R.string.network_title),
                        getString(R.string.start_screensaver), getString(R.string.phone_setup),
                        getString(R.string.settings), getString(R.string.update_title)},
                new String[]{null, null, null, networkLabel, null, null, null, ReleaseUpdates.available(this)}, null, which -> {
                    if (which == 0) showInputs();
                    else if (which == 1) showVolume();
                    else if (which == 2) showBrightness();
                    else if (which == 3) openSystemSettings(true);
                    else if (which == 4) showScreensaver(false);
                    else if (which == 5) protect(this::showPhoneSetup);
                    else if (which == 6) showLauncherSettings();
                    else protect(this::showUpdates);
                });
    }

    private void setPanelBack(Dialog dialog, Runnable parent) {
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            resetScreensaver();
            if (parent == null) return false;
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                dialog.dismiss();
                parent.run();
            }
            return true;
        });
    }

    private void showLauncherSettings() {
        if (ParentalLock.locked(this)) { protect(this::showLauncherSettings); return; }
        String[] values = {LauncherPreferences.dockPercent(this) + "%", null, screensaverState(), null,
                weather.mode().equals("off") ? getString(R.string.weather_off) : null,
                null, null, homeState(), null, null, null, null, null, onOff(ParentalLock.enabled(this)), null, null, null};
        showMenu(getString(R.string.launcher_settings), null,
                getResources().getStringArray(R.array.settings_actions), values, null, which -> {
            if (which == 0) showAppearance();
            else if (which == 1) chooseWallpaper();
            else if (which == 2) showScreensaverSettings();
            else if (which == 3) chooseDockSlot();
            else if (which == 4) showWeatherSettings();
            else if (which == 5) showRemoteSettings();
            else if (which == 6) showAppManagement();
            else if (which == 7) showHomeIntegration();
            else if (which == 8) showSystemPreferences();
            else if (which == 9) openSystemSettings(false);
            else if (which == 10) showAbout();
            else if (which == 11) showStockConfirmation();
            else if (which == 12) showInputSettings();
            else if (which == 13) showPinSettings();
            else if (which == 14) showRecoveryOptions();
            else if (which == 15) showPhoneSetup();
            else showUpdates();
        });
    }

    private void showAppearance() {
        boolean motion = getSharedPreferences("home", MODE_PRIVATE).getBoolean("icon_motion", true);
        showMenu(getString(R.string.appearance_title), null,
                new String[]{getString(R.string.dock_size), getString(R.string.icon_motion),
                        getString(R.string.layout_title), getString(R.string.name_style_title),
                        getString(R.string.focus_style_title), getString(R.string.clock_style_title),
                        getString(R.string.back_screensaver), getString(R.string.phone_setup)},
                new String[]{LauncherPreferences.dockPercent(this) + "%", onOff(motion), null, null, null, null,
                        onOff(HomeOptions.flag(this, "back_saver", false)), null},
                this::showLauncherSettings, which -> {
                    if (which == 0) {
                        int[] sizes = {75, 85, 90, 100, 110};
                        String[] labels = new String[sizes.length];
                        for (int i = 0; i < sizes.length; i++) labels[i] = sizes[i] + "%";
                        showMenu(getString(R.string.dock_size), null, labels, null, this::showAppearance, index -> {
                            LauncherPreferences.setDockPercent(this, sizes[index]); updateDockSize(); grid.requestLayout(); showAppearance();
                        });
                    } else if (which == 1) {
                        LauncherPreferences.setMotionEnabled(this, !motion); adapter.notifyDataSetChanged(); showAppearance();
                    } else if (which == 2) chooseOption("layout", R.string.layout_title, R.array.layout_names, new String[]{"dock", "grid"});
                    else if (which == 3) chooseOption("name_style", R.string.name_style_title, R.array.name_style_names, new String[]{"hidden", "focused", "all"});
                    else if (which == 4) chooseOption("focus_style", R.string.focus_style_title, R.array.focus_style_names, new String[]{"standard", "contrast"});
                    else if (which == 5) chooseOption("large_clock", R.string.clock_style_title, R.array.clock_style_names, new String[]{"off", "left", "right"});
                    else if (which == 6) { HomeOptions.set(this, "back_saver", !HomeOptions.flag(this, "back_saver", false)); showAppearance(); }
                    else showPhoneSetup();
                });
    }

    private String onOff(boolean enabled) { return getString(enabled ? R.string.home_on : R.string.home_off); }

    private void chooseOption(String key, int title, int names, String[] values) {
        showMenu(getString(title), null, getResources().getStringArray(names), null, this::showAppearance, index -> {
            HomeOptions.set(this, key, values[index]); applyOptions(); showAppearance();
        });
    }

    private void refreshDailyInBackground() {
        if (!HomeOptions.flag(this, "daily_enabled", false)) return;
        featureWorker.execute(() -> {
            try { media.refreshDaily(false); } catch (Exception ignored) { }
            handler.post(() -> {
                if (!isDestroyed() && resumed && media.selected().startsWith("daily:")) loadWallpaper();
            });
        });
    }

    private void applyOptions() {
        int columns = HomeOptions.columns(this);
        if (columns != COLUMNS) {
            COLUMNS = columns;
            grid.removeItemDecoration(dockDecoration);
            dockDecoration = new DockDecoration(COLUMNS, density);
            grid.addItemDecoration(dockDecoration);
        }
        updateDockSize(); grid.requestLayout(); updateLargeClock(); loadWallpaper();
        adapter.reload(() -> { phoneApps = adapter.entries(); adapter.notifyDataSetChanged(); });
    }

    private void updateLargeClock() {
        View group = findViewById(R.id.largeClockGroup);
        ((TextView) findViewById(R.id.largeDate)).setText(new java.text.SimpleDateFormat(
                getString(R.string.large_date_pattern), java.util.Locale.SIMPLIFIED_CHINESE).format(new java.util.Date()));
        String alignment = HomeOptions.text(this, "large_clock", "off");
        boolean browsing = grid != null && grid.getLayoutManager() instanceof GridLayoutManager
                && ((GridLayoutManager) grid.getLayoutManager()).findFirstVisibleItemPosition() >= COLUMNS;
        boolean show = !alignment.equals("off") && lastFocused < COLUMNS && !browsing;
        group.setVisibility(show ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) group.getLayoutParams();
        params.gravity = Gravity.TOP | (alignment.equals("right") ? Gravity.END : Gravity.START);
        group.setLayoutParams(params);
        findViewById(R.id.headerClock).setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void updateWeather(WeatherController.Snapshot snapshot, boolean failed) {
        boolean off = weather.mode().equals("off");
        if (off || snapshot == null || !WeatherModel.usable(snapshot.temperature, snapshot.observedAt, System.currentTimeMillis())) {
            btnWeather.setText(off ? getString(R.string.weather_title) : "—°");
            btnWeather.setCompoundDrawables(null, null, null, null);
            btnWeather.setContentDescription(getString(off ? R.string.weather_off : R.string.weather_unavailable));
            return;
        }
        boolean stale = failed || System.currentTimeMillis() - snapshot.observedAt >= WeatherModel.FRESH_MS;
        btnWeather.setText(Math.round(snapshot.temperature) + "°" + (stale ? " ·" : ""));
        Drawable icon = new WeatherIconDrawable(snapshot.code, snapshot.day);
        icon.setBounds(0, 0, dp(20), dp(20));
        btnWeather.setCompoundDrawables(icon, null, null, null);
        String description = getString(R.string.weather_accessibility, snapshot.city, Math.round(snapshot.temperature));
        if (stale) description += "，" + getString(R.string.weather_stale);
        btnWeather.setContentDescription(description);
    }

    private void showWeatherSettings() {
        WeatherController.Snapshot snapshot = weather.snapshot();
        String description = getString(R.string.weather_description);
        if (snapshot != null) {
            String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(snapshot.observedAt));
            description = getString(R.string.weather_observed, snapshot.city, time) + "\n\n" + description;
        }
        showMenu(getString(R.string.weather_title), description,
                new String[]{getString(R.string.weather_auto), getString(R.string.weather_manual),
                        getString(R.string.weather_refresh), getString(R.string.weather_disable)},
                new String[]{weather.mode().equals("auto") ? "✓" : null,
                        weather.mode().equals("manual") ? "✓" : null, null, null},
                this::showLauncherSettings, which -> {
                    if (which == 1) { searchWeatherCity(); return; }
                    if (which == 0) weather.setAutomatic();
                    else if (which == 2) weather.refresh(true);
                    else weather.disable();
                    showWeatherSettings();
                });
        weatherPanel = activePanel;
    }

    private void searchWeatherCity() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = panelContent(dialog, getString(R.string.weather_manual));
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.weather_city_hint);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x99FFFFFF);
        content.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        android.widget.Button search = new android.widget.Button(this);
        search.setText(R.string.weather_search);
        search.setOnClickListener(view -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) return;
            dialog.dismiss();
            showMenu(getString(R.string.weather_title), getString(R.string.weather_searching),
                    new String[0], null, this::showWeatherSettings, ignored -> {});
            Dialog pending = activePanel;
            weather.search(query, places -> {
                if (!resumed || activePanel != pending) return;
                pending.dismiss();
                if (places.isEmpty()) {
                    Toast.makeText(this, R.string.weather_no_results, Toast.LENGTH_LONG).show();
                    showWeatherSettings();
                    return;
                }
                String[] labels = new String[places.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = places.get(i).name;
                showMenu(getString(R.string.weather_manual), null, labels, null, this::showWeatherSettings, index -> {
                    weather.setPlace(places.get(index));
                    showWeatherSettings();
                });
            });
        });
        content.addView(search);
        setPanelBack(dialog, this::showWeatherSettings);
        showPanel(dialog);
        input.requestFocus();
    }

    private void showAppManagement() {
        if (ParentalLock.locked(this)) { protect(this::showAppManagement); return; }
        showMenu(getString(R.string.apps_title), getString(R.string.apps_description),
                new String[]{getString(R.string.apps_hide), getString(R.string.apps_restore), getString(R.string.apps_system)},
                null, this::showLauncherSettings, which -> {
                    if (which == 2) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                        if (intent.resolveActivity(getPackageManager()) != null) open(intent);
                        else openSystemSettings(false);
                    } else {
                        List<String> packages = new ArrayList<>();
                        List<String> labels = new ArrayList<>();
                        if (which == 0) {
                            for (AppAdapter.Entry entry : adapter.entries()) { if (entry.input != null) continue; packages.add(entry.pkg); labels.add(entry.label); }
                        } else {
                            for (String pkg : LauncherPreferences.hiddenApps(this)) {
                                packages.add(pkg);
                                try { labels.add(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString()); }
                                catch (android.content.pm.PackageManager.NameNotFoundException ignored) { labels.add(pkg); }
                            }
                        }
                        if (packages.isEmpty()) {
                            Toast.makeText(this, R.string.apps_empty, Toast.LENGTH_SHORT).show();
                            showAppManagement(); return;
                        }
                        showMenu(getString(which == 0 ? R.string.apps_hide : R.string.apps_restore), null,
                                labels.toArray(new String[0]), null, this::showAppManagement, index -> {
                                    LauncherPreferences.setAppHidden(this, packages.get(index), which == 0);
                                    adapter.reload(() -> grid.post(() -> focusTile(0)));
                                    showAppManagement();
                                });
                    }
                });
    }

    private void showRemoteSettings() {
        java.util.Map<Integer, String> bindings = RemoteKeys.bindings(this);
        List<Integer> keys = new ArrayList<>(bindings.keySet());
        String[] labels = new String[keys.size() + 3];
        String[] values = new String[labels.length];
        labels[0] = getString(R.string.remote_add);
        labels[1] = getString(R.string.remote_accessibility);
        values[1] = getString(HomeIntegration.serviceEnabled(this) && HomeRedirectService.isConnected()
                ? R.string.home_allowed : R.string.home_permission_needed);
        labels[2] = getString(R.string.remote_restore_all);
        for (int i = 0; i < keys.size(); i++) {
            int code = keys.get(i);
            labels[i + 3] = getString(R.string.remote_key, KeyEvent.keyCodeToString(code).replace("KEYCODE_", ""));
            String pkg = bindings.get(code);
            try { values[i + 3] = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) { values[i + 3] = pkg; }
        }
        showMenu(getString(R.string.remote_title), getString(R.string.remote_description) + "\n\n" + getString(R.string.remote_limit),
                labels, values, this::showLauncherSettings, which -> {
                    if (which == 0) learnRemoteKey();
                    else if (which == 1) open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    else if (which == 2) { RemoteKeys.clear(this); showRemoteSettings(); }
                    else chooseRemoteTarget(keys.get(which - 3));
                });
    }

    private void learnRemoteKey() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = panelContent(dialog, getString(R.string.remote_add));
        TextView description = new TextView(this);
        description.setText(getString(R.string.remote_waiting) + "\n\n" + getString(R.string.remote_limit));
        description.setTextSize(18);
        description.setTextColor(0xDDFFFFFF);
        content.addView(description);
        capturedRemoteKey = -1;
        remoteCapturePanel = dialog;
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) { dialog.dismiss(); showRemoteSettings(); }
                return true;
            }
            return captureRemoteKeyIfWaiting(event);
        });
        showPanel(dialog);
    }

    static boolean captureRemoteKeyIfWaiting(KeyEvent event) {
        MainActivity home = visibleHome.get();
        if (home == null || home.remoteCapturePanel == null || home.activePanel != home.remoteCapturePanel) return false;
        int code = event.getKeyCode();
        if (!RemoteKeyPolicy.canMap(code)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && code != KeyEvent.KEYCODE_BACK) {
                Toast.makeText(home, R.string.remote_reserved, Toast.LENGTH_SHORT).show();
            }
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) home.capturedRemoteKey = code;
        if (event.getAction() == KeyEvent.ACTION_UP && home.capturedRemoteKey == code) {
            home.remoteCapturePanel.dismiss();
            home.remoteCapturePanel = null;
            home.capturedRemoteKey = -1;
            if (!event.isCanceled()) home.chooseRemoteTarget(code);
            else home.showRemoteSettings();
        }
        return true;
    }

    private void chooseRemoteTarget(int code) {
        List<AppAdapter.Entry> apps = adapter.entries();
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.remote_remove);
        for (int i = 0; i < apps.size(); i++) labels[i + 1] = apps.get(i).label;
        showMenu(getString(R.string.remote_choose_app), KeyEvent.keyCodeToString(code).replace("KEYCODE_", ""),
                labels, null, this::showRemoteSettings, which -> {
                    RemoteKeys.bind(this, code, which == 0 ? null : apps.get(which - 1).pkg);
                    showRemoteSettings();
                });
    }

    private String systemAnimationState() {
        float scale = TvSystemPreferences.animationScale(this);
        if (scale == 1f) return getString(R.string.system_animation_standard);
        if (scale == 0.5f) return getString(R.string.system_animation_fast);
        if (scale == 0f) return getString(R.string.home_off);
        return getString(R.string.system_animation_custom);
    }

    private void showSystemPreferences() {
        boolean supported = TvSystemPreferences.supportsBootAdPolicy(this);
        int policy = TvSystemPreferences.bootAdPolicy(this);
        String boot = getString(R.string.system_preferences_unknown);
        if (!supported) boot = getString(R.string.system_preferences_not_supported);
        else if (policy == 1) boot = getString(R.string.home_off);
        else if (policy == 0) boot = getString(R.string.boot_ad_follow);
        String[] actions = {getString(R.string.boot_ad_title), getString(R.string.system_animation_title)};
        String[] values = {boot, systemAnimationState()};
        String description = getString(TvSystemPreferences.canWrite(this)
                ? R.string.system_preferences_description : R.string.system_preferences_permission);
        showMenu(getString(R.string.system_preferences_title), description, actions, values,
                this::showLauncherSettings, index -> {
            if (!TvSystemPreferences.canWrite(this)) {
                showSystemPreferenceMessage(R.string.system_preferences_permission);
            } else if (index == 0 && !supported) {
                showSystemPreferenceMessage(R.string.system_preferences_unavailable);
            } else if (index == 0) {
                chooseBootAdPolicy();
            } else {
                chooseSystemAnimation();
            }
        });
    }

    private void showSystemPreferenceMessage(int message) {
        showMenu(getString(R.string.system_preferences_title), getString(message),
                new String[]{getString(R.string.back)}, null, this::showSystemPreferences,
                ignored -> showSystemPreferences());
    }

    private void chooseBootAdPolicy() {
        int current = TvSystemPreferences.bootAdPolicy(this);
        String[] labels = {getString(R.string.home_off), getString(R.string.boot_ad_follow)};
        String[] values = {current == 1 ? "✓" : "", current == 0 ? "✓" : ""};
        showMenu(getString(R.string.boot_ad_title), getString(R.string.boot_ad_description),
                labels, values, this::showSystemPreferences, index -> {
            if (TvSystemPreferences.setBootAdsBlocked(this, index == 0)) showSystemPreferences();
            else showSystemPreferenceMessage(R.string.system_preferences_failed);
        });
    }

    private void chooseSystemAnimation() {
        float[] scales = {1f, 0.5f, 0f};
        String[] labels = {getString(R.string.system_animation_standard),
                getString(R.string.system_animation_fast), getString(R.string.home_off)};
        String[] values = new String[scales.length];
        float current = TvSystemPreferences.animationScale(this);
        for (int i = 0; i < scales.length; i++) values[i] = current == scales[i] ? "✓" : "";
        showMenu(getString(R.string.system_animation_title), getString(R.string.system_animation_description),
                labels, values, this::showSystemPreferences, index -> {
            if (TvSystemPreferences.setAnimationScale(this, scales[index])) showSystemPreferences();
            else showSystemPreferenceMessage(R.string.system_preferences_failed);
        });
    }

    private void showAbout() {
        String url = getString(R.string.project_url).trim();
        Dialog dialog = new Dialog(this);
        LinearLayout content = panelContent(dialog, getString(R.string.about_title));
        TextView homepage = new TextView(this);
        homepage.setText(url.isEmpty() ? getString(R.string.project_homepage) + "\n"
                + getString(R.string.project_unpublished) : url);
        homepage.setTextSize(16);
        homepage.setTextColor(Color.WHITE);
        homepage.setLineSpacing(dp(8), 1f);
        homepage.setPadding(dp(6), dp(8), dp(6), dp(8));
        if (!url.isEmpty()) {
            homepage.setFocusable(true);
            homepage.setBackgroundResource(R.drawable.panel_row);
            homepage.setTextColor(getColorStateList(R.color.panel_text));
            homepage.setOnClickListener(view -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        }
        content.addView(homepage);
        setPanelBack(dialog, this::showLauncherSettings);
        showPanel(dialog);
    }

    private String screensaverState() {
        boolean system = SystemScreensaver.enabled(this);
        int minutes = system ? SystemScreensaver.minutes(this)
                : LauncherPreferences.screensaverMinutes(this);
        if (system && minutes == 0) return getString(R.string.screensaver_system_time);
        return getString(minutes == 0 ? R.string.screensaver_never : R.string.screensaver_minutes, minutes);
    }

    private void showScreensaverSettings() {
        boolean system = SystemScreensaver.enabled(this);
        systemScreensaverEnabled = system;
        systemScreensaverMinutes = system ? SystemScreensaver.minutes(this) : 0;
        String[] actions = {getString(R.string.screensaver_start), getString(R.string.screensaver_system),
                getString(R.string.screensaver_preview), getString(R.string.saver_mode_title)};
        String[] values = {screensaverState(), getString(system ? R.string.home_on : R.string.home_off), null, null};
        showMenu(getString(R.string.screensaver_title), getString(system
                        ? R.string.screensaver_system_active : R.string.screensaver_description),
                actions, values, this::showLauncherSettings, index -> {
            if (index == 0) chooseScreensaverTime();
            else if (index == 1) chooseSystemScreensaver();
            else if (index == 3) {
                showMenu(getString(R.string.saver_mode_title), getString(R.string.aerial_description),
                        getResources().getStringArray(R.array.saver_mode_names), null, this::showScreensaverSettings, selected -> {
                            HomeOptions.set(this, "screensaver_mode", new String[]{"wallpaper", "aerial", "photos", "clock"}[selected]);
                            showScreensaverSettings();
                        });
            } else if (SystemScreensaver.enabled(this)) {
                Intent preview = SystemScreensaver.previewIntent();
                if (preview.resolveActivity(getPackageManager()) != null) open(preview);
                else Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
            } else showScreensaver(true);
        });
    }

    private void chooseSystemScreensaver() {
        if (!SystemScreensaver.supported(this)) {
            Toast.makeText(this, R.string.system_preferences_unavailable, Toast.LENGTH_LONG).show();
            showScreensaverSettings();
            return;
        }
        if (!TvSystemPreferences.canWrite(this)) {
            showMenu(getString(R.string.screensaver_system), getString(R.string.system_preferences_permission),
                    new String[]{getString(R.string.back)}, null, this::showScreensaverSettings,
                    ignored -> showScreensaverSettings());
            return;
        }
        boolean enabled = SystemScreensaver.enabled(this);
        String[] labels = {getString(R.string.home_on), getString(R.string.home_off)};
        String[] values = {enabled ? "✓" : "", enabled ? "" : "✓"};
        showMenu(getString(R.string.screensaver_system), getString(R.string.screensaver_system_description),
                labels, values, this::showScreensaverSettings, index -> {
            boolean saved;
            if (index == 0) {
                int minutes = LauncherPreferences.screensaverMinutes(this);
                if (minutes == 0) minutes = 5;
                saved = SystemScreensaver.enable(this, minutes);
                if (saved) LauncherPreferences.setScreensaverMinutes(this, minutes);
            } else {
                saved = !enabled || SystemScreensaver.restore(this);
            }
            refreshScreensaverTimer();
            if (!saved) Toast.makeText(this, R.string.system_preferences_failed, Toast.LENGTH_LONG).show();
            showScreensaverSettings();
        });
    }

    private void chooseScreensaverTime() {
        if (SystemScreensaver.enabled(this) && SystemScreensaver.usesXiaomiTimer()) {
            showMenu(getString(R.string.screensaver_start), getString(R.string.screensaver_native_time),
                    new String[]{getString(R.string.home_open_system), getString(R.string.back)}, null,
                    this::showScreensaverSettings, index -> {
                        if (index == 0) openSystemSettings(false);
                        else showScreensaverSettings();
                    });
            return;
        }
        int[] minutes = {0, 1, 2, 5, 10, 15, 20, 30};
        String[] labels = new String[minutes.length];
        String[] values = new String[minutes.length];
        int current = SystemScreensaver.enabled(this) ? SystemScreensaver.minutes(this)
                : LauncherPreferences.screensaverMinutes(this);
        for (int i = 0; i < minutes.length; i++) {
            labels[i] = getString(minutes[i] == 0 ? R.string.screensaver_never
                    : R.string.screensaver_minutes, minutes[i]);
            values[i] = minutes[i] == current ? "✓" : "";
        }
        showMenu(getString(R.string.screensaver_start), null, labels, values,
                this::showScreensaverSettings, index -> {
            boolean saved = true;
            if (SystemScreensaver.enabled(this)) {
                saved = minutes[index] == 0 ? SystemScreensaver.restore(this)
                        : SystemScreensaver.setMinutes(this, minutes[index]);
            }
            if (saved) LauncherPreferences.setScreensaverMinutes(this, minutes[index]);
            else Toast.makeText(this, R.string.system_preferences_failed, Toast.LENGTH_LONG).show();
            refreshScreensaverTimer();
            showScreensaverSettings();
        });
    }

    private void showScreensaver(boolean preview) {
        Dialog dialog = new Dialog(this, R.style.Theme_TvHome);
        ScreensaverScene scene = new ScreensaverScene(this);
        screensaverScene = scene;
        videoWallpaper.setRunning(false);
        dialog.setContentView(scene);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || keyCode == KeyEvent.KEYCODE_POWER) return false;
            if (scene.handleKey(keyCode, event)) return true;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                dialog.dismiss();
                if (preview) showScreensaverSettings();
            }
            return true;
        });
        scene.setOnClickListener(view -> {
            dialog.dismiss();
            if (preview) showScreensaverSettings();
        });
        dialog.setOnDismissListener(ignored -> {
            scene.setRunning(false);
            screensaverScene = null;
            if (activePanel == dialog) activePanel = null;
            updateVideoWallpaper();
            resetScreensaver();
        });
        activePanel = dialog;
        dialog.show();
        window.setLayout(-1, -1);
        scene.setRunning(true);
    }

    private void showStockConfirmation() {
        if (getPackageManager().getLaunchIntentForPackage("com.mitv.tvhome") == null) {
            showMenu(getString(R.string.stock_title), getString(R.string.stock_restore_description),
                    new String[]{getString(R.string.back)}, null, this::showLauncherSettings,
                    ignored -> showLauncherSettings());
            return;
        }
        String[] actions = {getString(R.string.stock_confirm), getString(R.string.cancel)};
        showMenu(getString(R.string.stock_title), getString(R.string.stock_description),
                actions, null, this::showLauncherSettings, which -> {
            if (which == 0) {
                Intent stock = getPackageManager().getLaunchIntentForPackage("com.mitv.tvhome");
                try {
                    if (stock == null) throw new ActivityNotFoundException();
                    startActivity(stock);
                    HomeIntegration.setEnabled(this, false);
                    finish();
                } catch (ActivityNotFoundException | SecurityException error) {
                    Toast.makeText(this, R.string.stock_restore_description, Toast.LENGTH_LONG).show();
                    showLauncherSettings();
                }
            } else showLauncherSettings();
        });
    }

    private String homeState() {
        if (HomeIntegration.isSystemHome(this)) return getString(R.string.home_system_default);
        if (!HomeIntegration.enabled(this)) return getString(R.string.home_off);
        if (HomeRedirectService.isConnected()) return getString(R.string.home_on);
        return getString(HomeIntegration.serviceEnabled(this)
                ? R.string.home_connecting : R.string.home_permission_needed);
    }

    private void showHomeIntegration() {
        boolean enabled = HomeIntegration.enabled(this);
        boolean allowed = HomeIntegration.serviceEnabled(this);
        boolean nativeHome = HomeIntegration.isSystemHome(this);
        String description = getString(nativeHome ? R.string.home_system_description : R.string.home_description);
        if (!nativeHome && enabled && !allowed) description = getString(R.string.home_permission_description);
        else if (enabled && HomeIntegration.canRecover(this)) {
            description += "\n\n" + getString(R.string.home_recovery_description);
        }
        String[] actions = {getString(nativeHome ? R.string.home_compatibility : R.string.home_toggle),
                getString(R.string.home_accessibility)};
        String[] values = {getString(enabled ? R.string.home_on : R.string.home_off),
                getString(allowed ? R.string.home_allowed : R.string.home_permission_needed)};
        showMenu(getString(R.string.home_title), description, actions, values,
                this::showLauncherSettings, index -> {
            if (index == 0) {
                HomeIntegration.setEnabled(this, !enabled);
                showHomeIntegration();
            } else open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
    }

    private void chooseDockSlot() {
        List<AppAdapter.Entry> entries = adapter.entries();
        if (entries.isEmpty()) { Toast.makeText(this, R.string.apps_empty, Toast.LENGTH_SHORT).show(); return; }
        String[] labels = new String[Math.min(COLUMNS, entries.size())];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(R.string.dock_slot, i + 1, entries.get(i).label);
        showMenu(getString(R.string.choose_slot), getString(R.string.choose_slot_description),
                labels, null, this::showLauncherSettings, this::chooseDockApp);
    }

    private void chooseDockApp(int slot) {
        List<AppAdapter.Entry> entries = adapter.entries();
        String[] labels = new String[entries.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = entries.get(i).label;
        showMenu(getString(R.string.choose_app), null, labels, null, this::chooseDockSlot, index -> {
            if (slot < LauncherPreferences.favourites(this).size()) {
                LauncherPreferences.setFavourite(this, slot, entries.get(index).pkg);
            }
            List<AppAdapter.Entry> current = adapter.entries();
            for (int from = 0; from < current.size(); from++) if (current.get(from).pkg.equals(entries.get(index).pkg)) {
                adapter.move(from, Math.min(slot, current.size() - 1)); break;
            }
            lastFocused = slot;
            adapter.reload(() -> grid.post(() -> focusTile(slot)));
        });
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private void chooseWallpaper() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = panelContent(dialog, getString(R.string.wallpaper_title));
        RecyclerView gallery = new RecyclerView(this);
        int width = Math.min(dp(780), getResources().getDisplayMetrics().widthPixels - dp(36));
        int columns = width >= dp(600) ? 3 : 2;
        GridLayoutManager layout = new GridLayoutManager(this, columns);
        gallery.setLayoutManager(layout);
        gallery.setClipToPadding(false);
        gallery.setPadding(dp(2), dp(2), dp(2), dp(2));
        gallery.setItemAnimator(null);
        int selected = LauncherPreferences.wallpaper(this);
        android.widget.Button phone = new android.widget.Button(this);
        phone.setText(R.string.wallpaper_upload);
        phone.setOnClickListener(v -> { dialog.dismiss(); showPhoneSetup(); });
        content.addView(phone);
        android.widget.Button daily = new android.widget.Button(this);
        daily.setText(R.string.daily_refresh);
        daily.setOnClickListener(v -> { dialog.dismiss(); enableDaily(); });
        content.addView(daily);
        WallpaperAdapter wallpapers = new WallpaperAdapter(this, new WallpaperAdapter.Listener() {
            @Override public void select(int index) {
                dialog.dismiss(); LauncherPreferences.setWallpaper(MainActivity.this, index); loadWallpaper();
            }
            @Override public void selectSource(String id) {
                dialog.dismiss();
                if (id.startsWith("daily:") && !HomeOptions.flag(MainActivity.this, "daily_enabled", false)) { enableDaily(); return; }
                media.select(id); loadWallpaper();
            }
        });
        gallery.setAdapter(wallpapers);
        content.addView(gallery, new LinearLayout.LayoutParams(-1, 0, 1));
        setPanelBack(dialog, this::showLauncherSettings);
        showPanel(dialog);
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(width,
                getResources().getDisplayMetrics().heightPixels - dp(36));
        int position = Math.max(0, Math.min(wallpapers.getItemCount() - 1, selected));
        layout.scrollToPosition(position);
        gallery.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        View tile = layout.findViewByPosition(position);
                        if (tile == null) return;
                        gallery.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        tile.requestFocus();
                    }
                });
    }

    private void loadWallpaper() {
        if (!media.selected().equals("legacy")) { loadManagedWallpaper(); return; }
        if (WallpaperCatalog.mode(this) >= WallpaperCatalog.AMBIENT) {
            wallpaperRequest++;
            updateVideoWallpaper();
            updateAmbientMaterial();
            wallpaperReady = true;
            return;
        }
        ImageView wallpaper = findViewById(R.id.wallpaper);
        android.content.res.TypedArray images = getResources().obtainTypedArray(R.array.wallpaper_images);
        int selected = LauncherPreferences.wallpaper(this);
        if (selected < 0 || selected >= images.length()) selected = 0;
        final int resource = images.getResourceId(selected, 0);
        images.recycle();
        final java.io.File local = resource == R.drawable.mountain_poster
                ? VideoWallpaper.localFile(this, "wallpaper.jpg") : null;
        final int request = ++wallpaperRequest;
        // Decode once off the UI thread. Cap texture size for the 1080p TV output.
        new Thread(() -> {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            if (local == null) BitmapFactory.decodeResource(getResources(), resource, options);
            else BitmapFactory.decodeFile(local.getAbsolutePath(), options);
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 2560 || options.outHeight / options.inSampleSize > 1440) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            Bitmap bitmap = local == null ? BitmapFactory.decodeResource(getResources(), resource, options)
                    : BitmapFactory.decodeFile(local.getAbsolutePath(), options);
            Bitmap material = bitmap == null ? null : WallpaperMaterial.blur(bitmap);
            WallpaperPalette palette = WallpaperMaterial.palette(material);
            handler.post(() -> {
                if (isDestroyed() || request != wallpaperRequest) {
                    if (bitmap != null) bitmap.recycle();
                    if (material != null) material.recycle();
                    return;
                }
                wallpaper.setImageBitmap(bitmap);
                videoWallpaper.setPoster(bitmap);
                wallpaperReady = true;
                updateVideoWallpaper();
                wallpaperPalette = palette;
                updateHeaderColors(material);
                dockDecoration.setWallpaper(material);
                dockDecoration.setPalette(palette);
                grid.invalidateItemDecorations();
            });
        }, "bbtv-wallpaper").start();
    }

    private void updateAmbientMaterial() {
        int[] colors = ambientWallpaper.colors();
        Bitmap material = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        material.setPixels(new int[]{colors[0], colors[2], colors[1], colors[0]}, 0, 2, 0, 0, 2, 2);
        wallpaperPalette = WallpaperPalette.fromPixels(colors);
        updateHeaderColors(material);
        dockDecoration.setWallpaper(material);
        dockDecoration.setPalette(wallpaperPalette);
        grid.invalidateItemDecorations();
    }

    private void updateDockSize() {
        if (adapter == null || grid.getParent() == null) return;
        int available = ((View) grid.getParent()).getWidth();
        if (available <= 0) return;
        int columns = Math.min(COLUMNS, Math.max(1, adapter.getItemCount()));
        GridLayoutManager layout = (GridLayoutManager) grid.getLayoutManager();
        if (layout.getSpanCount() != columns) layout.setSpanCount(columns);
        float scale = Math.min(LauncherPreferences.dockPercent(this) / 100f,
                Math.max(0.4f, (available - dp(48)) / (columns * 184f * density)));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) grid.getLayoutParams();
        int width = Math.min(available, dp(columns * 184f * scale + 48));
        if (params.width != width) {
            params.width = width;
            params.gravity = Gravity.CENTER_HORIZONTAL;
            grid.setLayoutParams(params);
        }
        adapter.setTileScale(scale);
    }

    private void updateHeaderColors(Bitmap material) {
        int foreground = WallpaperMaterial.headerTextColor(material);
        android.content.res.ColorStateList colors = new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{0xFF18212B, foreground});
        btnWeather.setTextColor(colors);
        btnWeather.setCompoundDrawableTintList(colors);
        btnControls.setCompoundDrawableTintList(colors);
        TextView clock = findViewById(R.id.headerClock);
        clock.setTextColor(foreground);
        ((TextView) findViewById(R.id.largeClock)).setTextColor(foreground);
        ((TextView) findViewById(R.id.largeDate)).setTextColor(foreground);
        int shadow = foreground == 0xFF18212B ? 0x30FFFFFF : 0x80000000;
        clock.setShadowLayer(dp(2), 0, dp(1), shadow);
        btnWeather.setShadowLayer(dp(2), 0, dp(1), shadow);
    }

    private void tintPanel(View view) {
        Drawable background = view.getBackground().mutate();
        if (background instanceof LayerDrawable) {
            background = ((LayerDrawable) background).getDrawable(0);
        }
        ((GradientDrawable) background).setColors(new int[]{wallpaperPalette.panelStart, wallpaperPalette.panelEnd});
    }

    private void openSystemSettings(boolean network) {
        Intent intent = new Intent(network ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_SETTINGS);
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(network ? "com.xiaomi.mitv.settings.WIFI_SETTINGS"
                    : "com.xiaomi.mitv.settings.SETTINGS").setPackage("com.xiaomi.mitv.settings");
            if (intent.resolveActivity(getPackageManager()) == null) {
                intent = getPackageManager().getLaunchIntentForPackage("com.xiaomi.mitv.settings");
            }
        }
        if (intent == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
            return;
        }
        open(intent);
    }

    @Override
    public void onBackPressed() {
        if (wallpaperPeek != null && wallpaperPeek.isShowing()) { wallpaperPeek.dismiss(true); return; }
        if (movingTile >= 0) { movingTile = -1; Toast.makeText(this, R.string.move_done, Toast.LENGTH_SHORT).show(); return; }
        if (lastFocused >= COLUMNS) { focusTile(0); return; }
        if (HomeOptions.flag(this, "back_saver", false)) showScreensaver(false);
        else focusTile(0);
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        updateLargeClock();
        if (media.selected().equals("auto")) updateAppBackground(position);
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            int decoratedTop = item.getTop() - dp(10);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
        } else {
            Rect visible = new Rect(0, -dp(16), item.getWidth(), item.getHeight() + dp(8));
            grid.requestChildRectangleOnScreen(item, visible, false);
        }
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        if (movingTile >= 0) {
            int delta = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                    : keyCode == KeyEvent.KEYCODE_DPAD_UP ? -COLUMNS : COLUMNS;
            int target = Math.max(0, Math.min(count - 1, movingTile + delta));
            adapter.move(movingTile, target); movingTile = target;
            grid.post(() -> { if (movingTile == target) focusTile(target); }); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (position % COLUMNS != 0) focusTile(position - 1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (position % COLUMNS != COLUMNS - 1 && position + 1 < count) focusTile(position + 1);
            return true;
        }
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                btnControls.requestFocus();
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private boolean focusVisibleTile(int position) {
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder == null) return false;
        View target = holder.itemView.findViewById(R.id.card);
        if (target.hasFocus()) onTileFocused(position, target);
        else target.requestFocusFromTouch();
        return target.hasFocus();
    }

    private void focusTile(int position) {
        int request = ++focusRequest;
        if (adapter.getItemCount() == 0) return;
        int target = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        if (focusVisibleTile(target)) return;
        RecyclerView.OnScrollListener pending = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (request != focusRequest) { view.removeOnScrollListener(this); return; }
                if (state == RecyclerView.SCROLL_STATE_IDLE && focusVisibleTile(target)) view.removeOnScrollListener(this);
            }
        };
        grid.addOnScrollListener(pending);
        grid.smoothScrollToPosition(target);
        // An already-visible target can be waiting for binding without generating a scroll-idle event.
        grid.postOnAnimation(() -> {
            if (request != focusRequest || focusVisibleTile(target)) grid.removeOnScrollListener(pending);
        });
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                text = getString(R.string.wifi);
            }
        } catch (Exception ignored) {
        }
        networkLabel = text;
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.not_available), Toast.LENGTH_SHORT).show();
        }
    }

    /** Input chooser anchored to Control Center: HDMI ports, AV and Live TV. */
    private void showInputs() {
        View anchor = btnControls;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        tintPanel(panel);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Align the chooser with the right edge of Control Center.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(InputSources.selectable(this));
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                String label = InputSources.label(this, info);
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(java.util.Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = info.getType() == TvInputInfo.TYPE_TUNER ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : info.getType() == TvInputInfo.TYPE_TUNER ? R.drawable.ic_livetv : R.drawable.ic_av, label, state,
                        () -> {
                            Intent intent = TvInputRouting.create(this, info);
                            if (intent != null) open(intent);
                            else Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
                        });
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        activeInputs = popup;
        popup.setOnDismissListener(() -> { if (activeInputs == popup) activeInputs = null; });
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.END);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
    private void loadManagedWallpaper() {
        int request = ++wallpaperRequest;
        String source = media.selected();
        featureWorker.execute(() -> {
            Bitmap bitmap = media.picture(source, 1920, 1080);
            Bitmap material = bitmap == null ? null : WallpaperMaterial.blur(bitmap);
            handler.post(() -> {
                if (isDestroyed() || request != wallpaperRequest) {
                    if (bitmap != null) bitmap.recycle();
                    if (material != null) material.recycle();
                    return;
                }
                ((ImageView) findViewById(R.id.wallpaper)).setImageBitmap(bitmap);
                wallpaperReady = true;
                updateVideoWallpaper();
                wallpaperPalette = WallpaperMaterial.palette(material);
                updateHeaderColors(material);
                dockDecoration.setWallpaper(material); dockDecoration.setPalette(wallpaperPalette);
                grid.invalidateItemDecorations();
                if (source.equals("auto")) updateAppBackground(lastFocused);
            });
        });
    }

    private void updateAppBackground(int position) {
        List<AppAdapter.Entry> entries = adapter.entries();
        if (position < 0 || position >= entries.size()) return;
        Drawable artwork = entries.get(position).banner;
        if (artwork == null) return;
        // The tile is already cached. Sample a tiny raster only when focus changes.
        Bitmap sample = Bitmap.createBitmap(32, 20, Bitmap.Config.ARGB_8888);
        Rect old = artwork.copyBounds(); artwork.setBounds(0, 0, 32, 20);
        artwork.draw(new android.graphics.Canvas(sample)); artwork.setBounds(old);
        int color = AppArtwork.dominant(sample); sample.recycle();
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, 0xFF101724});
        ((ImageView) findViewById(R.id.wallpaper)).setImageDrawable(background);
        Bitmap material = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        material.eraseColor(color);
        wallpaperPalette = WallpaperPalette.fromPixels(new int[]{color, 0xFF101724});
        updateHeaderColors(material); dockDecoration.setWallpaper(material); dockDecoration.setPalette(wallpaperPalette);
        grid.invalidateItemDecorations();
    }

    private void enableDaily() {
        boolean enabled = HomeOptions.flag(this, "daily_enabled", false);
        String[] actions = enabled ? new String[]{getString(R.string.daily_refresh), getString(R.string.home_off), getString(R.string.cancel)}
                : new String[]{getString(R.string.home_on), getString(R.string.cancel)};
        showMenu(getString(R.string.daily_latest), getString(R.string.daily_description),
                actions, null, this::chooseWallpaper, index -> {
                    if (enabled && index == 1) { HomeOptions.set(this, "daily_enabled", false); chooseWallpaper(); return; }
                    if (index != 0) { chooseWallpaper(); return; }
                    HomeOptions.set(this, "daily_enabled", true);
                    media.select("daily:latest"); loadWallpaper(); chooseWallpaper();
                    Dialog picker = activePanel;
                    Toast.makeText(this, R.string.daily_loading, Toast.LENGTH_SHORT).show();
                    featureWorker.execute(() -> {
                        boolean loaded;
                        try { media.refreshDaily(true); loaded = true; } catch (Exception ignored) { loaded = false; }
                        boolean result = loaded;
                        handler.post(() -> {
                            if (isDestroyed() || !HomeOptions.flag(this, "daily_enabled", false)) return;
                            if (result && media.selected().startsWith("daily:")) loadWallpaper();
                            else if (!result) Toast.makeText(this, R.string.daily_failed, Toast.LENGTH_LONG).show();
                            if (activePanel == picker) { picker.dismiss(); chooseWallpaper(); }
                        });
                    });
                });
    }

    @Override public boolean onTileActivate(int position) {
        if (movingTile < 0) return false;
        // Remote confirmation arrives through dispatchKeyEvent; clicks can choose another destination.
        if (position >= 0 && position != movingTile) { adapter.move(movingTile, position); movingTile = position; }
        int finished = movingTile;
        movingTile = -1;
        grid.post(() -> focusTile(finished));
        Toast.makeText(this, R.string.move_done, Toast.LENGTH_SHORT).show();
        return true;
    }

    @Override public void onTileAction(int position) {
        protect(() -> {
            List<AppAdapter.Entry> entries = adapter.entries();
            if (position < 0 || position >= entries.size()) return;
            AppAdapter.Entry entry = entries.get(position);
            if (entry.input != null) {
                showMenu(entry.label, null, new String[]{getString(R.string.move_app), getString(R.string.input_pin)},
                        null, null, index -> {
                            if (index == 0) startMove(position);
                            else { HomeOptions.pinInput(this, entry.input.getId(), false); adapter.reload(() -> focusTile(0)); }
                        });
                return;
            }
            showMenu(entry.label, null, new String[]{getString(R.string.move_app), getString(R.string.hide_app),
                    getString(R.string.app_info), getString(R.string.uninstall_app), getString(R.string.reset_icon)},
                    null, null, index -> {
                        if (index == 0) startMove(position);
                        else if (index == 1) {
                            LauncherPreferences.setAppHidden(this, entry.pkg, true);
                            adapter.reload(() -> focusTile(Math.max(0, position - 1)));
                        } else if (index == 2) open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + entry.pkg)));
                        else if (index == 3) open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + entry.pkg)));
                        else { media.resetIcon(entry.pkg); adapter.reload(() -> focusTile(position)); }
                    });
        });
    }

    private void startMove(int position) {
        movingTile = position;
        grid.postOnAnimation(() -> { if (movingTile >= 0) focusTile(movingTile); });
        Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show();
    }

    private void showInputSettings() {
        List<TvInputInfo> inputs = InputSources.selectable(this);
        String[] labels = new String[inputs.size() + 2];
        String[] values = new String[labels.length];
        labels[0] = getString(R.string.input_boot); labels[1] = getString(R.string.input_wake);
        values[1] = onOff(HomeOptions.flag(this, "wake_input", false));
        for (int i = 0; i < inputs.size(); i++) {
            labels[i + 2] = InputSources.label(this, inputs.get(i));
            values[i + 2] = onOff(HomeOptions.pinnedInputs(this).contains(inputs.get(i).getId()));
        }
        showMenu(getString(R.string.input_settings), null, labels, values, this::showLauncherSettings, index -> {
            if (index == 0) {
                List<String> sources = new ArrayList<>(); sources.add(getString(R.string.input_home));
                for (TvInputInfo input : inputs) sources.add(InputSources.label(this, input));
                showMenu(getString(R.string.input_boot), null, sources.toArray(new String[0]), null, this::showInputSettings, choice -> {
                    HomeOptions.set(this, "boot_source", choice == 0 ? "" : inputs.get(choice - 1).getId());
                    HomeOptions.prefs(this).edit().putInt("input_boot_count", Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1)).apply();
                    showInputSettings();
                });
            } else if (index == 1) { HomeOptions.set(this, "wake_input", !HomeOptions.flag(this, "wake_input", false)); showInputSettings(); }
            else {
                String id = inputs.get(index - 2).getId();
                HomeOptions.pinInput(this, id, !HomeOptions.pinnedInputs(this).contains(id));
                adapter.reload(() -> updateDockSize()); showInputSettings();
            }
        });
    }

    private void showVolume() {
        android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        if (audio == null || audio.isVolumeFixed()) {
            showMenu(getString(R.string.volume_title), getString(R.string.volume_fixed_description),
                    new String[]{getString(R.string.settings)}, null, this::showControlCenter,
                    ignored -> openSystemSettings(false));
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout content = panelContent(dialog, getString(R.string.volume_title));
        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setContentDescription(getString(R.string.volume_title));
        slider.setMax(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        slider.setProgress(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
        content.addView(slider, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView value = new TextView(this); value.setTextColor(Color.WHITE); value.setTextSize(20);
        content.addView(value);
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar view, int progress, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, progress, 0);
                value.setText(String.valueOf(progress));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar view) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar view) { }
        });
        Runnable sync = new Runnable() {
            @Override public void run() {
                if (activePanel != dialog || !dialog.isShowing()) return;
                int current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                slider.setProgress(current); value.setText(String.valueOf(current));
                handler.postDelayed(this, 250);
            }
        };
        setPanelBack(dialog, this::showControlCenter); showPanel(dialog); slider.requestFocus(); handler.post(sync);
    }

    private void showBrightness() {
        // No direct panel-backlight profile has passed physical-device acceptance yet.
        showMenu(getString(R.string.brightness_title), getString(R.string.brightness_fallback),
                new String[]{getString(R.string.picture_settings)}, null, this::showControlCenter,
                ignored -> {
                    Intent intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
                    if (intent.resolveActivity(getPackageManager()) != null) open(intent);
                    else openSystemSettings(false);
                });
    }

    private void protect(Runnable action) {
        if (!ParentalLock.locked(this)) { action.run(); return; }
        showPin(getString(R.string.pin_enter), pin -> {
            int request = ++pinRequest;
            featureWorker.execute(() -> {
                boolean accepted = ParentalLock.verify(this, pin);
                handler.post(() -> {
                    if (isDestroyed() || !resumed || request != pinRequest) return;
                    if (accepted) { ParentalLock.unlock(); action.run(); }
                    else { Toast.makeText(this, R.string.pin_error, Toast.LENGTH_SHORT).show(); protect(action); }
                });
            });
        });
    }

    private void showPin(String title, java.util.function.Consumer<String> submit) {
        Dialog dialog = new Dialog(this);
        LinearLayout content = panelContent(dialog, title);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        input.setTextColor(Color.WHITE);
        content.addView(input, new LinearLayout.LayoutParams(-1, dp(48)));
        android.widget.GridLayout pad = new android.widget.GridLayout(this); pad.setColumnCount(3);
        String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", getString(R.string.pin_delete), "0", getString(R.string.pin_submit)};
        for (int i = 0; i < digits.length; i++) {
            final int index = i;
            android.widget.Button button = new android.widget.Button(this); button.setText(digits[i]);
            button.setOnClickListener(v -> {
                if (index == 9) {
                    if (input.length() > 0) input.getText().delete(input.length() - 1, input.length());
                } else if (index == 11) {
                    String pin = input.getText().toString();
                    if (!ParentalLock.valid(pin)) { input.setError(getString(R.string.pin_invalid)); return; }
                    dialog.dismiss(); submit.accept(pin);
                } else input.append(digits[index]);
            });
            android.widget.GridLayout.LayoutParams params = new android.widget.GridLayout.LayoutParams();
            params.width = dp(88); params.height = dp(48); pad.addView(button, params);
        }
        content.addView(pad);
        setPanelBack(dialog, null); showPanel(dialog); pad.getChildAt(0).requestFocus();
    }

    private void showPinSettings() {
        boolean enabled = ParentalLock.enabled(this);
        showMenu(getString(R.string.pin_title), getString(R.string.pin_description),
                new String[]{getString(enabled ? R.string.home_off : R.string.home_on)}, null, this::showLauncherSettings, ignored -> {
                    if (enabled) { ParentalLock.disable(this); showLauncherSettings(); return; }
                    showPin(getString(R.string.pin_create), first -> showPin(getString(R.string.pin_confirm), second -> {
                        if (!first.equals(second)) { Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show(); showPinSettings(); return; }
                        int request = ++pinRequest;
                        featureWorker.execute(() -> {
                            boolean saved;
                            try { ParentalLock.set(this, first); saved = true; }
                            catch (Exception error) { saved = false; }
                            final boolean success = saved;
                            handler.post(() -> {
                                if (isDestroyed() || !resumed || request != pinRequest) return;
                                if (success) { ParentalLock.unlock(); showLauncherSettings(); }
                                else Toast.makeText(this, R.string.pin_save_failed, Toast.LENGTH_LONG).show();
                            });
                        });
                    }));
                });
    }

    private void showRecoveryOptions() {
        showMenu(getString(R.string.recovery_title), null,
                new String[]{getString(R.string.revival_title), getString(R.string.keep_adb)},
                new String[]{onOff(HomeOptions.flag(this, "notification_revival", false)), onOff(RecoveryOptions.keepAdb(this))},
                this::showLauncherSettings, index -> {
                    if (index == 0) showMenu(getString(R.string.revival_title), getString(R.string.revival_description),
                            new String[]{getString(R.string.home_on), getString(R.string.home_off)}, null, this::showRecoveryOptions, choice -> {
                                RevivalListener.setEnabled(this, choice == 0);
                                if (choice == 0) open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); else showRecoveryOptions();
                            });
                    else showMenu(getString(R.string.keep_adb), getString(R.string.keep_adb_description),
                            new String[]{getString(R.string.home_on), getString(R.string.home_off)}, null, this::showRecoveryOptions, choice -> {
                                if (!RecoveryOptions.setKeepAdb(this, choice == 0)) Toast.makeText(this,
                                        HomeIntegration.canRecover(this) ? R.string.system_preferences_failed : R.string.system_preferences_permission,
                                        Toast.LENGTH_LONG).show();
                                showRecoveryOptions();
                            });
                });
    }

    private String lanAddress() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return null;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            android.net.LinkProperties links = manager.getLinkProperties(network);
            if (links == null) continue;
            for (android.net.LinkAddress link : links.getLinkAddresses()) {
                java.net.InetAddress address = link.getAddress();
                if (address instanceof java.net.Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
            }
        }
        return null;
    }

    private void stopPhone() {
        if (phoneServer == null) return;
        phoneServer.stop(); phoneServer = null;
    }

    private void showPhoneSetup() {
        stopPhone();
        String address = lanAddress();
        if (address == null) { Toast.makeText(this, R.string.phone_unavailable, Toast.LENGTH_LONG).show(); return; }
        phoneApps = adapter.entries();
        PhoneServer server = new PhoneServer(this, address, new PhoneServer.Host() {
            @Override public List<AppAdapter.Entry> apps() { return phoneApps; }
            @Override public void changed() { if (!isDestroyed()) applyOptions(); }
            @Override public WeatherController weather() { return weather; }
            @Override public void main(Runnable action) { handler.post(action); }
        });
        try { server.start(5000, true); phoneServer = server; }
        catch (java.io.IOException error) { Toast.makeText(this, R.string.phone_start_failed, Toast.LENGTH_LONG).show(); return; }
        Dialog dialog = new Dialog(this);
        LinearLayout content = panelContent(dialog, getString(R.string.phone_setup));
        TextView description = new TextView(this); description.setText(R.string.phone_description);
        description.setTextColor(Color.WHITE); description.setTextSize(14); content.addView(description);
        try {
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(server.url(),
                    com.google.zxing.BarcodeFormat.QR_CODE, 320, 320);
            Bitmap qr = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) qr.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView image = new ImageView(this); image.setImageBitmap(qr);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(192), dp(192));
            imageParams.gravity = Gravity.CENTER_HORIZONTAL; imageParams.topMargin = dp(12);
            content.addView(image, imageParams);
        } catch (com.google.zxing.WriterException ignored) { }
        TextView link = new TextView(this); link.setText(server.url()); link.setTextColor(Color.WHITE); link.setTextSize(12);
        link.setPadding(0, dp(12), 0, 0); content.addView(link);
        setPanelBack(dialog, this::showControlCenter); showPanel(dialog);
        dialog.setOnDismissListener(ignored -> { if (activePanel == dialog) activePanel = null; stopPhone(); });
        handler.postDelayed(() -> { if (phoneServer == server) { dialog.dismiss(); stopPhone(); } }, 10 * 60_000L);
    }

    private void showUpdates() {
        showMenu(getString(R.string.update_title), getString(R.string.update_checking), new String[0], null,
                this::showLauncherSettings, ignored -> { });
        Dialog pending = activePanel;
        featureWorker.execute(() -> {
            String result = ReleaseUpdates.fetch(this, true);
            handler.post(() -> {
                if (isDestroyed() || activePanel != pending) return;
                pending.dismiss();
                String current;
                try { current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
                catch (android.content.pm.PackageManager.NameNotFoundException ignored) { current = ""; }
                String text = getString(R.string.update_current, current) + "\n" + (result == null ? getString(R.string.update_failed) : getString(R.string.update_latest, result));
                showMenu(getString(R.string.update_title), text + "\n\n" + getString(R.string.update_description),
                        new String[]{getString(R.string.update_open)}, null, this::showLauncherSettings,
                        ignored -> open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sogud/banana-tv/releases"))));
            });
        });
    }

}
