package com.sogud.bbtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Managed images only. Downloads and full-size decoding run on a worker thread. */
final class MediaLibrary {
    static final long MAX_UPLOAD = 20L * 1024 * 1024;
    private static final Object FILE_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean DAILY_BUSY = new java.util.concurrent.atomic.AtomicBoolean();
    private final Context context;
    private final File photos;
    private final File daily;
    private final File icons;

    static final class Item {
        final String id;
        final String title;
        final String caption;
        Item(String id, String title, String caption) { this.id = id; this.title = title; this.caption = caption; }
    }

    MediaLibrary(Context context) {
        this.context = context.getApplicationContext();
        photos = new File(context.getFilesDir(), "photos");
        daily = new File(context.getFilesDir(), "daily");
        icons = new File(context.getFilesDir(), "icons");
    }

    String selected() { return HomeOptions.text(context, "wallpaper_source", "legacy"); }

    List<Item> items() {
        List<Item> result = new ArrayList<>();
        result.add(new Item("legacy", context.getString(R.string.builtin_wallpapers), ""));
        result.add(new Item("daily:latest", context.getString(R.string.daily_latest), "Bing"));
        result.add(new Item("photos:rotate", context.getString(R.string.photo_rotation), ""));
        result.add(new Item("auto", context.getString(R.string.follow_app), ""));
        String[] names = context.getResources().getStringArray(R.array.gradient_names);
        for (int i = 0; i < GradientWallpapers.KEYS.length; i++) {
            result.add(new Item("grad:" + GradientWallpapers.KEYS[i], names[i], ""));
        }
        synchronized (FILE_LOCK) {
            JSONArray index = readDaily();
            for (int i = 0; i < index.length(); i++) {
                JSONObject entry = index.optJSONObject(i);
                if (entry == null || !entry.optString("date").matches("[0-9]{8}")) continue;
                String id = "daily:" + entry.optString("date");
                if (file(id).isFile()) result.add(new Item(id, entry.optString("title"), entry.optString("caption")));
            }
            File[] files = photos.listFiles((dir, name) -> name.matches("[a-f0-9-]{36}\\.jpg"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                for (File photo : files) result.add(new Item("photo:" + photo.getName().replace(".jpg", ""),
                        context.getString(R.string.personal_photo), ""));
            }
        }
        return result;
    }

    boolean select(String id) {
        for (Item item : items()) {
            if (item.id.equals(id)) { HomeOptions.set(context, "wallpaper_source", id); return true; }
        }
        return false;
    }

    Item resolve(String id) {
        List<Item> all = items();
        if (id.equals("daily:latest")) {
            for (Item item : all) if (item.id.matches("daily:[0-9]{8}")) return item;
        } else if (id.equals("photos:rotate")) {
            List<Item> personal = new ArrayList<>();
            for (Item item : all) if (item.id.startsWith("photo:")) personal.add(item);
            if (!personal.isEmpty()) return personal.get((int) Math.floorMod(LocalDate.now().toEpochDay(), personal.size()));
        } else {
            for (Item item : all) if (item.id.equals(id)) return item;
        }
        return new Item("grad:aurora", context.getString(R.string.offline_background), "");
    }

    Bitmap picture(String id, int width, int height) {
        Item item = resolve(id);
        if (item.id.startsWith("grad:")) return GradientWallpapers.render(GradientWallpapers.index(item.id.substring(5)), width, height);
        File image = file(item.id);
        if (image != null && image.isFile()) {
            Bitmap bitmap = decode(image, width, height);
            if (bitmap != null) return bitmap;
        }
        if (item.id.equals("legacy")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = width < 600 ? 4 : 1;
            return BitmapFactory.decodeResource(context.getResources(), WallpaperCatalog.image(context, width < 600), options);
        }
        return GradientWallpapers.render(0, width, height);
    }

    private File file(String id) {
        if (id.matches("daily:[0-9]{8}")) return new File(daily, id.substring(6) + ".jpg");
        if (id.matches("photo:[a-f0-9-]{36}")) return new File(photos, id.substring(6) + ".jpg");
        return null;
    }

    String addPhoto(File upload) throws Exception {
        String id = UUID.randomUUID().toString();
        saveImage(upload, new File(photos, id + ".jpg"), 2560, 1440, true);
        return "photo:" + id;
    }

    boolean delete(String id) {
        synchronized (FILE_LOCK) {
            if (!id.startsWith("photo:")) return false;
            File file = file(id);
            if (file == null || !file.isFile() || !file.delete()) return false;
            if (selected().equals(id)) HomeOptions.set(context, "wallpaper_source", "legacy");
            return true;
        }
    }

    File customIcon(String pkg) {
        if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return null;
        return new File(icons, pkg + ".jpg");
    }

    void saveIcon(String pkg, File upload) throws Exception {
        File target = customIcon(pkg);
        if (target == null) throw new IllegalArgumentException("Invalid package");
        context.getPackageManager().getApplicationInfo(pkg, 0);
        saveImage(upload, target, 640, 384, false);
    }

    boolean resetIcon(String pkg) {
        File target = customIcon(pkg);
        return target != null && (!target.exists() || target.delete());
    }

    private static void saveImage(File upload, File target, int width, int height, boolean personal) throws Exception {
        if (upload.length() == 0 || upload.length() > MAX_UPLOAD) throw new IllegalArgumentException("Image too large");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(upload.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 16384 || bounds.outHeight > 16384
                || (long) bounds.outWidth * bounds.outHeight > 40_000_000L) throw new IllegalArgumentException("Unsupported image");
        Bitmap bitmap = decode(upload, width, height);
        if (bitmap == null) throw new IllegalArgumentException("Unsupported image");
        float scale = Math.min(1f, Math.min((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight()));
        if (scale < 1f) {
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (resized != bitmap) bitmap.recycle();
            bitmap = resized;
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded)) throw new IllegalStateException("Image write failed");
        } finally { bitmap.recycle(); }
        // Decode and compression stay outside the directory lock; opening a picker never waits for a network request.
        synchronized (FILE_LOCK) {
            target.getParentFile().mkdirs();
            File[] current = target.getParentFile().listFiles((dir, name) -> name.endsWith(".jpg"));
            if (personal && !target.exists() && current != null && current.length >= 50) throw new IllegalArgumentException("Photo limit reached");
            AtomicFile atomic = new AtomicFile(target);
            FileOutputStream output = null;
            try {
                output = atomic.startWrite(); encoded.writeTo(output); atomic.finishWrite(output);
            } catch (Exception error) { atomic.failWrite(output); throw error; }
        }
    }

    static Bitmap decode(File file, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > width * 2 || options.outHeight / options.inSampleSize > height * 2) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /** Explicitly enabled daily pictures are cached locally, never bundled in the APK. */
    void refreshDaily(boolean force) throws Exception {
        if (!HomeOptions.flag(context, "daily_enabled", false)) return;
        long last = HomeOptions.prefs(context).getLong("daily_checked", 0);
        if (!force && System.currentTimeMillis() - last < 6 * 3600_000L) return;
        if (!DAILY_BUSY.compareAndSet(false, true)) return;
        try {
            JSONObject response = new JSONObject(new String(download(new URL(
                    "https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=zh-CN"), 256 * 1024), StandardCharsets.UTF_8));
            JSONArray images = response.getJSONArray("images");
            JSONArray index = new JSONArray();
            daily.mkdirs();
            for (int i = 0; i < Math.min(8, images.length()); i++) {
                JSONObject item = images.getJSONObject(i);
                String date = item.getString("startdate");
                if (!date.matches("[0-9]{8}")) continue;
                String path = item.getString("url");
                if (!path.startsWith("/th?")) continue;
                File destination = new File(daily, date + ".jpg");
                if (!destination.isFile()) {
                    byte[] data = download(new URL("https://cn.bing.com" + path), 8 * 1024 * 1024);
                    File temp = File.createTempFile("daily-", ".image", context.getCacheDir());
                    try {
                        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(data); }
                        saveImage(temp, destination, 2560, 1440, false);
                    } finally { temp.delete(); }
                }
                index.put(new JSONObject().put("date", date).put("title", item.optString("title", date))
                        .put("caption", item.optString("copyright", "Bing")));
            }
            if (index.length() == 0) throw new IllegalStateException("No daily pictures");
            synchronized (FILE_LOCK) {
                AtomicFile atomic = new AtomicFile(new File(daily, "index.json"));
                FileOutputStream out = atomic.startWrite();
                try { out.write(index.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(out); }
                catch (Exception error) { atomic.failWrite(out); throw error; }
                File[] stored = daily.listFiles((dir, name) -> name.matches("[0-9]{8}\\.jpg"));
                if (stored != null) for (File image : stored) {
                    if (!index.toString().contains(image.getName().substring(0, 8))) image.delete();
                }
            }
            HomeOptions.prefs(context).edit().putLong("daily_checked", System.currentTimeMillis()).apply();
        } finally { DAILY_BUSY.set(false); }
    }

    private JSONArray readDaily() {
        try (InputStream input = new FileInputStream(new File(daily, "index.json"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) { if (out.size() + count > 256 * 1024) return new JSONArray(); out.write(buffer, 0, count); }
            return new JSONArray(out.toString("UTF-8"));
        } catch (Exception ignored) { return new JSONArray(); }
    }

    static byte[] download(URL address, int maxBytes) throws Exception {
        if (!address.getProtocol().equals("https")) throw new IllegalArgumentException("HTTPS required");
        HttpURLConnection connection = (HttpURLConnection) address.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Request failed");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > maxBytes) throw new IllegalArgumentException("Response too large");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
}
