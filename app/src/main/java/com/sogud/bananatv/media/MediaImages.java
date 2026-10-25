package com.sogud.bananatv.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class MediaImages {
    private static MediaCache disk;
    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private static final java.util.Set<okhttp3.Call> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile int generation;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final OkHttpClient http = new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build();
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private MediaImages() { }
    static void cancelPending() {
        generation++; worker.getQueue().clear();
        for (okhttp3.Call call : active) call.cancel();
        active.clear();
    }
    private static synchronized MediaCache disk(ImageView view) {
        if (disk == null) disk = new MediaCache(new java.io.File(view.getContext().getCacheDir(), "media-posters"), 16 * 1024 * 1024);
        return disk;
    }
    static void load(ImageView view, String url) {
        view.setTag(url); Bitmap ready = cache.get(url);
        if (ready != null) { view.setImageBitmap(ready); return; }
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return;
        MediaCache files = disk(view);
        int ticket = generation;
        worker.execute(() -> {
            if (ticket != generation) return;
            byte[] saved = files.get(url);
            Bitmap stored = saved == null ? null : BitmapFactory.decodeByteArray(saved, 0, saved.length);
            if (stored != null) {
                cache.put(url, stored);
                main.post(() -> { if (ticket == generation && url.equals(view.getTag())) view.setImageBitmap(stored); });
                return;
            }
            okhttp3.Call call;
            try { call = http.newCall(new Request.Builder().url(url).build()); }
            catch (IllegalArgumentException ignored) { return; }
            active.add(call);
            if (ticket != generation) call.cancel();
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null || response.body().contentLength() > 2 * 1024 * 1024) return;
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream input = response.body().byteStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (output.size() + count > 2 * 1024 * 1024) return;
                        output.write(buffer, 0, count);
                    }
                }
                byte[] bytes = output.toByteArray(); BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 600 || options.outHeight / options.inSampleSize > 900) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false; Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) return;
                cache.put(url, bitmap);
                java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 85, compressed)) files.put(url, compressed.toByteArray());
                main.post(() -> { if (ticket == generation && url.equals(view.getTag())) view.setImageBitmap(bitmap); });
            } catch (Exception ignored) { /* The title remains usable when a poster is unavailable. */ }
            finally { active.remove(call); }
        });
    }
}
