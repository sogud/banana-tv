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
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
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
                    changed = !items.get(i).pkg.equals(fresh.get(i).pkg);
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
        List<Entry> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || excluded.contains(pkg)) continue;
            if (!seen.add(pkg)) continue;
            Entry cached = artworkCache.get(pkg);
            if (cached != null) {
                fresh.add(cached);
                continue;
            }
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            Drawable branded = brandedArtwork(pkg, icon);
            if (branded != null) banner = branded;
            String label = String.valueOf(ri.loadLabel(pm));
            Entry entry = new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon);
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
        h.name.setVisibility(View.GONE);
        ViewGroup.LayoutParams cardParams = h.card.getLayoutParams();
        cardParams.width = Math.round(160 * tileScale * h.density);
        cardParams.height = Math.round(96 * tileScale * h.density);
        h.card.setLayoutParams(cardParams);
        ViewGroup.LayoutParams itemParams = h.itemView.getLayoutParams();
        itemParams.height = Math.round((96 * tileScale + 28) * h.density);
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
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
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
            final float radius = density * 18f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            card.setCameraDistance(8000 * density);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (LauncherPreferences.motionEnabled(view.getContext())) {
                        float scale = event.getAction() == KeyEvent.ACTION_DOWN ? 1.02f : FOCUS_SCALE;
                        card.animate().scaleX(scale).scaleY(scale).setDuration(100).start();
                    }
                    return false;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
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
            name.animate().cancel();
            sheen.animate().cancel();
            float scale = focused ? FOCUS_SCALE : 1f;
            float rise = focused ? -3f * density : 0f;
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
