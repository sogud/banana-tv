package com.sogud.bbtv;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.Calendar;

/** Small packaged previews keep the remote-controlled gallery independent of full-size textures. */
final class WallpaperAdapter extends RecyclerView.Adapter<WallpaperAdapter.Tile> {
    interface Listener {
        void select(int index);
        void selectSource(String id);
    }

    private final Context context;
    private final Listener listener;
    private final String[] names;
    private final int[] modes;
    private final int[] thumbnails;
    private final int selected;
    private final MediaLibrary media;
    private final java.util.List<MediaLibrary.Item> extra;
    private static final java.util.concurrent.ExecutorService DECODER = java.util.concurrent.Executors.newFixedThreadPool(2);

    WallpaperAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        media = new MediaLibrary(context);
        extra = media.items();
        extra.removeIf(item -> item.id.equals("legacy"));
        names = context.getResources().getStringArray(R.array.wallpaper_names);
        modes = context.getResources().getIntArray(R.array.wallpaper_modes);
        selected = LauncherPreferences.wallpaper(context);
        TypedArray resources = context.getResources().obtainTypedArray(R.array.wallpaper_thumbnails);
        thumbnails = new int[resources.length()];
        for (int i = 0; i < thumbnails.length; i++) thumbnails[i] = resources.getResourceId(i, 0);
        resources.recycle();
    }

    @NonNull
    @Override
    public Tile onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackgroundResource(R.drawable.wallpaper_tile);
        card.setFocusable(true);
        card.setClickable(true);
        RecyclerView.LayoutParams item = new RecyclerView.LayoutParams(-1, -2);
        item.setMargins(dp(4), dp(4), dp(4), dp(6));
        card.setLayoutParams(item);
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.BLACK);
        outline.setCornerRadius(dp(7));
        image.setBackground(outline);
        image.setClipToOutline(true);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(image, new LinearLayout.LayoutParams(-1, dp(104)));
        TextView label = new TextView(context);
        label.setTextSize(14);
        label.setTextColor(Color.WHITE);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        label.setPadding(dp(4), 0, dp(4), 0);
        card.addView(label, new LinearLayout.LayoutParams(-1, dp(32)));
        return new Tile(card, image, label);
    }

    @Override
    public void onBindViewHolder(@NonNull Tile tile, int position) {
        if (position >= names.length) {
            MediaLibrary.Item item = extra.get(position - names.length);
            tile.itemView.setTag(item.id);
            tile.label.setText(item.title);
            tile.itemView.setContentDescription(item.title);
            tile.itemView.setSelected(item.id.equals(media.selected()));
            tile.image.setImageDrawable(null);
            DECODER.execute(() -> {
                android.graphics.Bitmap bitmap = media.picture(item.id, 384, 216);
                context.getMainExecutor().execute(() -> {
                    if (!item.id.equals(tile.itemView.getTag())) { if (bitmap != null) bitmap.recycle(); return; }
                    tile.image.setImageBitmap(bitmap);
                });
            });
            tile.itemView.setOnClickListener(view -> listener.selectSource(item.id));
            return;
        }
        tile.itemView.setTag(null);
        int mode = modes[position];
        String name = names[position];
        String detail = "";
        if (mode >= WallpaperCatalog.AMBIENT) {
            Calendar now = Calendar.getInstance();
            int[] colors = AmbientColors.at(mode - WallpaperCatalog.AMBIENT,
                    now.get(Calendar.DAY_OF_YEAR), now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
            tile.image.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors));
            detail = context.getString(R.string.wallpaper_adaptive);
        } else {
            tile.image.setImageResource(thumbnails[position]);
            if (mode == WallpaperCatalog.VIDEO) {
                detail = context.getString(R.string.wallpaper_dynamic);
                if (VideoWallpaper.localFile(context, "wallpaper.mp4") != null) {
                    name = context.getString(R.string.wallpaper_local);
                }
            }
        }
        boolean checked = position == selected && media.selected().equals("legacy");
        tile.itemView.setSelected(checked);
        tile.label.setText(checked ? context.getString(R.string.wallpaper_checked_name, name) : name);
        tile.itemView.setContentDescription(name + (detail.isEmpty() ? "" : "，" + detail)
                + (checked ? "，" + context.getString(R.string.wallpaper_selected) : ""));
        tile.itemView.setOnClickListener(view -> listener.select(position));
    }

    @Override
    public int getItemCount() {
        return names.length + extra.size();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class Tile extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label;

        Tile(View card, ImageView image, TextView label) {
            super(card);
            this.image = image;
            this.label = label;
        }
    }
}
