package com.sogud.bbtv;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Catalogue and caption data from Aerial, copyright 2026 pengchujin (MIT). */
final class AerialCatalog {
    static final class Film {
        final String id;
        final String label;
        final String url;
        final JSONArray captions;
        Film(JSONObject item) throws Exception {
            id = item.getString("id"); label = item.getString("label");
            url = item.getString("url"); captions = item.getJSONArray("captions");
            if (!url.startsWith("https://sylvan.apple.com/")) throw new IllegalArgumentException("Untrusted film host");
        }
        String caption(long millis) {
            String text = label;
            for (int i = 0; i < captions.length(); i++) {
                JSONArray entry = captions.optJSONArray(i);
                if (entry == null || entry.optInt(0) > millis / 1000) break;
                text = entry.optString(1, label);
            }
            return text.replace('\u00a0', ' ');
        }
    }
    static List<Film> load(Context context) {
        List<Film> films = new ArrayList<>();
        try (InputStream in = context.getAssets().open("aerials.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            JSONArray videos = new JSONObject(out.toString(StandardCharsets.UTF_8.name())).getJSONArray("videos");
            for (int i = 0; i < videos.length(); i++) films.add(new Film(videos.getJSONObject(i)));
        } catch (Exception ignored) { films.clear(); }
        return films;
    }
}
