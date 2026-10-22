package com.sogud.bbtv;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent app/input order, initialized from legacy favourites. Artwork loads off the main thread.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);
        void onTileAction(int position);
        boolean onTileActivate(int position);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        android.media.tv.TvInputInfo input;
        long stamp;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    private List<String> order() {
        List<String> result = new ArrayList<>();
        try {
            org.json.JSONArray array = new org.json.JSONArray(HomeOptions.text(ctx, "tile_order", "[]"));
            for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        } catch (org.json.JSONException ignored) { }
        return result;
    }

    void move(int from, int to) {
        if (from < 0 || to < 0 || from >= items.size() || to >= items.size() || from == to) return;
        List<String> next = TileOrder.move(order(), items.get(from).pkg, items.get(to).pkg);
        HomeOptions.set(ctx, "tile_order", new org.json.JSONArray(next).toString());
        Entry item = items.remove(from);
        items.add(to, item);
        notifyItemMoved(from, to);
    }

    static final float FOCUS_SCALE = 1.08f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Entry> artworkCache = new HashMap<>();
    private int scanVersion;
    private float tileScale = 0.9f;

    float tileScale() { return tileScale; }

    void setTileScale(float value) {
        if (Math.abs(tileScale - value) < 0.001f) return;
        tileScale = value;
        notifyDataSetChanged();
    }

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = LauncherPreferences.favourites(ctx);
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
    }

    void reload(Runnable onLoaded) {
        final int version = ++scanVersion;
        favourites = LauncherPreferences.favourites(ctx);
        final List<String> selected = new ArrayList<>(favourites);
        final Set<String> excluded = new HashSet<>(hidden);
        excluded.addAll(LauncherPreferences.hiddenApps(ctx));
        scanner.execute(() -> {
            List<Entry> fresh = scan(selected, excluded);
            main.post(() -> {
                if (version != scanVersion) return;
                boolean changed = items.size() != fresh.size();
                for (int i = 0; !changed && i < items.size(); i++) {
                    changed = !items.get(i).pkg.equals(fresh.get(i).pkg) || items.get(i).stamp != fresh.get(i).stamp;
                }
                if (changed) {
                    items.clear();
                    items.addAll(fresh);
                    notifyDataSetChanged();
                }
                onLoaded.run();
            });
        });
    }

    private List<Entry> scan(List<String> selected, Set<String> excluded) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.APPSTORE_LAUNCHER"), 0));
        String[] extra = {"com.xiaomi.mitv.mediaexplorer/.NewScraperMainEntryActivity",
                "com.xiaomi.smarthome.tv/com.xiaomi.smarthome.tv.ui.MainActivity"};
        for (String key : extra) {
            try {
                android.content.pm.ActivityInfo info = pm.getActivityInfo(ComponentName.unflattenFromString(key), 0);
                if (!info.exported || !info.enabled || !info.applicationInfo.enabled) continue;
                ResolveInfo resolved = new ResolveInfo(); resolved.activityInfo = info; found.add(resolved);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        MediaLibrary media = new MediaLibrary(ctx);
        List<Entry> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || !ri.activityInfo.exported || !ri.activityInfo.enabled) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || excluded.contains(pkg)) continue;
            if (!seen.add(pkg)) continue;
            long stamp = 0;
            java.io.File custom = media.customIcon(pkg);
            try { stamp = pm.getPackageInfo(pkg, 0).lastUpdateTime; } catch (PackageManager.NameNotFoundException ignored) { }
            if (custom != null && custom.isFile()) stamp += custom.lastModified() + custom.length();
            Entry cached = artworkCache.get(pkg);
            if (cached != null && cached.stamp == stamp) {
                fresh.add(cached);
                continue;
            }
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            Drawable branded = brandedArtwork(pkg, icon);
            if (branded != null) banner = branded;
            banner = AppArtwork.tile(ctx, banner, icon, custom);
            String label = String.valueOf(ri.loadLabel(pm));
            Entry entry = new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon);
            entry.stamp = stamp;
            artworkCache.put(pkg, entry);
            fresh.add(entry);
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = selected.indexOf(a.pkg), fb = selected.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        android.media.tv.TvInputManager manager = ctx.getSystemService(android.media.tv.TvInputManager.class);
        if (manager != null) for (android.media.tv.TvInputInfo input : InputSources.selectable(ctx)) {
            if (!HomeOptions.pinnedInputs(ctx).contains(input.getId())) continue;
            String key = "input:" + input.getId();
            Entry entry = new Entry(InputSources.label(ctx, input),
                    new ComponentName(key, "input"), null, ctx.getDrawable(R.drawable.ic_input));
            entry.input = input;
            fresh.add(entry);
        }
        List<String> discovered = new ArrayList<>();
        for (Entry entry : fresh) discovered.add(entry.pkg);
        List<String> saved = TileOrder.reconcile(order(), discovered);
        HomeOptions.set(ctx, "tile_order", new org.json.JSONArray(saved).toString());
        Collections.sort(fresh, (a, b) -> Integer.compare(saved.indexOf(a.pkg), saved.indexOf(b.pkg)));
        return fresh;
    }

    /** Build the four familiar tiles from installed icons; never ship copies of their artwork. */
    private Drawable brandedArtwork(String pkg, Drawable icon) {
        int top, bottom;
        boolean removeBackground = true;
        switch (pkg) {
            case "com.ktcp.video": top = 0xFFFFFFFF; bottom = 0xFFF2F3F6; break;
            case "com.xiaodianshi.tv.yst": top = 0xFFFF7099; bottom = 0xFFF63379; break;
            case "com.netease.cloudmusic.tv": top = 0xFFF62748; bottom = 0xFFCF0526; break;
            case "com.github.tvbox.osc.tk":
                top = 0xFF293449; bottom = 0xFF161D2A; removeBackground = false; break;
            default: return null;
        }
        Bitmap logo = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 256, 256);
        icon.draw(new Canvas(logo));
        if (removeBackground) {
            int[] pixels = new int[256 * 256];
            logo.getPixels(pixels, 0, 256, 0, 0, 256, 256);
            // These three icons have plain backgrounds. Compare each row with its edge color
            // so the original white/colored glyph can sit on a full-width tile.
            for (int y = 0; y < 256; y++) {
                int edge = pixels[y * 256];
                if (Color.alpha(edge) < 250) continue;
                for (int x = 0; x < 256; x++) {
                    int offset = y * 256 + x;
                    int pixel = pixels[offset];
                    int difference = Math.max(Math.abs(Color.red(pixel) - Color.red(edge)),
                            Math.max(Math.abs(Color.green(pixel) - Color.green(edge)),
                                    Math.abs(Color.blue(pixel) - Color.blue(edge))));
                    if (difference < 36) {
                        int alpha = Color.alpha(pixel) * Math.max(0, difference - 12) / 24;
                        pixels[offset] = (pixel & 0x00FFFFFF) | (alpha << 24);
                    }
                }
            }
            logo.setPixels(pixels, 0, 256, 0, 0, 256, 256);
        }
        Bitmap tile = Bitmap.createBitmap(320, 192, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tile);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, 192, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 320, 192, paint);
        paint.setShader(null);
        canvas.drawBitmap(logo, null, new Rect(84, 20, 236, 172), paint);
        logo.recycle();
        return new BitmapDrawable(ctx.getResources(), tile);
    }

    List<Entry> entries() {
        return new ArrayList<>(items);
    }

    void close() {
        scanVersion++;
        scanner.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(View.VISIBLE);
        positionName(h, tileScale);
        ViewGroup.LayoutParams cardParams = h.card.getLayoutParams();
        cardParams.width = Math.round(160 * tileScale * h.density);
        cardParams.height = Math.round(96 * tileScale * h.density);
        h.card.setLayoutParams(cardParams);
        ViewGroup.LayoutParams itemParams = h.itemView.getLayoutParams();
        itemParams.height = Math.round((96 * tileScale + (HomeOptions.text(ctx, "name_style", "hidden").equals("hidden") ? 28 : 52)) * h.density);
        h.itemView.setLayoutParams(itemParams);
        h.setFocusedState(h.card.hasFocus(), false);
        h.card.setContentDescription(e.label);
        if (e.banner != null) {
            boolean wide = e.banner.getIntrinsicWidth() >= e.banner.getIntrinsicHeight() * 1.4f;
            h.banner.setScaleType(wide ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> {
            int index = h.getBindingAdapterPosition();
            if (!listener.onTileActivate(index)) launch(e);
        });
        h.card.setOnLongClickListener(v -> {
            int index = h.getBindingAdapterPosition();
            if (index != RecyclerView.NO_POSITION) listener.onTileAction(index);
            return true;
        });
    }

    private void positionName(Holder holder, float scale) {
        android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) holder.name.getLayoutParams();
        params.topMargin = Math.round((96 * scale + 16) * holder.density);
        holder.name.setLayoutParams(params);
    }

    private void launch(Entry e) {
        if (e.input != null) {
            Intent source = TvInputRouting.create(ctx, e.input);
            if (source == null) { Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show(); return; }
            try { ctx.startActivity(source.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (ActivityNotFoundException | SecurityException ignored) {
                Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cannot_open_app, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView name;
        final View sheen;
        final float density;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            name = v.findViewById(R.id.name);
            sheen = v.findViewById(R.id.tileSheen);

            density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 14f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            card.setCameraDistance(8000 * density);
            card.setOnHoverListener((view, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_ENTER) card.requestFocusFromTouch();
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_EXIT) {
                    card.animate().rotationX(0).rotationY(0).setDuration(160).start();
                } else if (LauncherPreferences.motionEnabled(view.getContext())) {
                    card.setRotationY((event.getX() / Math.max(1, card.getWidth()) - .5f) * 6);
                    card.setRotationX((.5f - event.getY() / Math.max(1, card.getHeight())) * 6);
                }
                return false;
            });

            card.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (LauncherPreferences.motionEnabled(view.getContext())) {
                        float scale = event.getAction() == KeyEvent.ACTION_DOWN ? 1.02f : FOCUS_SCALE;
                        card.animate().scaleX(scale).scaleY(scale).setDuration(100).start();
                    }
                    return false;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                setFocusedState(focused, true);
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }

        void setFocusedState(boolean focused, boolean animate) {
            animate = animate && LauncherPreferences.motionEnabled(card.getContext());
            card.animate().cancel();
            banner.animate().cancel();
            icon.animate().cancel();
            name.animate().cancel();
            sheen.animate().cancel();
            String names = HomeOptions.text(card.getContext(), "name_style", "hidden");
            name.setAlpha(names.equals("all") || names.equals("focused") && focused ? 1 : 0);
            android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
            outline.setColor(Color.TRANSPARENT);
            outline.setCornerRadius(14 * density);
            if (focused && HomeOptions.text(card.getContext(), "focus_style", "standard").equals("contrast")) {
                outline.setStroke(Math.round(3 * density), Color.WHITE);
            }
            ((android.widget.FrameLayout) card).setForeground(outline);
            float scale = focused ? FOCUS_SCALE : 1f;
            float rise = focused ? -3f * density : 0f;
            if (!focused) { card.setRotationX(0); card.setRotationY(0); }
            card.setElevation((focused ? 18f : 2f) * density);
            itemView.setTranslationZ((focused ? 20f : 0f) * density);
            if (animate) {
                card.animate().scaleX(scale).scaleY(scale).translationY(rise).setDuration(focused ? 240 : 180)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(focused ? 0.8f : 0f)).start();
                sheen.animate().alpha(focused ? 1f : 0f).setDuration(180).start();
                banner.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(240).start();
                icon.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(240).start();
            } else {
                card.setScaleX(scale);
                card.setScaleY(scale);
                card.setTranslationY(rise);
                sheen.setAlpha(focused ? 1f : 0f);
                banner.animate().cancel();
                icon.animate().cancel();
                banner.setScaleX(focused ? 1.035f : 1f);
                banner.setScaleY(focused ? 1.035f : 1f);
                icon.setScaleX(focused ? 1.035f : 1f);
                icon.setScaleY(focused ? 1.035f : 1f);
            }
        }
    }
}
