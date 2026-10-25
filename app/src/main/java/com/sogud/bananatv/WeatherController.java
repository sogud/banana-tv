package com.sogud.bananatv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional weather: off until enabled, bounded HTTPS requests and local coarse-location cache. */
final class WeatherController {
    interface Listener { void changed(Snapshot snapshot, boolean failed); }
    interface Cities { void loaded(List<Place> places); }

    static final class Place {
        final String name;
        final double latitude;
        final double longitude;
        Place(String name, double latitude, double longitude) {
            if (name == null || name.trim().isEmpty() || !WeatherModel.coordinates(latitude, longitude)) {
                throw new IllegalArgumentException("Invalid weather location");
            }
            this.name = name;
            this.latitude = Math.round(latitude * 100) / 100.0;
            this.longitude = Math.round(longitude * 100) / 100.0;
        }
    }

    static final class Snapshot {
        final String city;
        final double temperature;
        final int code;
        final boolean day;
        final long observedAt;
        Snapshot(String city, double temperature, int code, boolean day, long observedAt) {
            if (city == null || city.isEmpty() || !WeatherModel.usable(temperature, observedAt, System.currentTimeMillis())) {
                throw new IllegalArgumentException("Invalid or expired weather");
            }
            this.city = city; this.temperature = temperature; this.code = code;
            this.day = day; this.observedAt = observedAt;
        }
    }

    private final SharedPreferences prefs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Snapshot cached;
    private boolean busy;
    private boolean closed;
    private int version;
    private long lastAttempt;
    private boolean failed;

    WeatherController(Context context, Listener listener) {
        prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE);
        this.listener = listener;
        try { cached = parseSnapshot(new JSONObject(prefs.getString("cache", "{}"))); }
        catch (Exception ignored) { cached = null; }
    }

    String mode() { return prefs.getString("mode", "off"); }
    Snapshot snapshot() { return cached; }
    boolean failed() { return failed; }

    void setAutomatic() {
        version++;
        prefs.edit().putString("mode", "auto").remove("located_at").apply();
        cached = null; failed = false; lastAttempt = 0;
        refresh(true);
    }

    void setPlace(Place place) {
        version++;
        savePlace(place, "manual");
        cached = null; failed = false; lastAttempt = 0;
        refresh(true);
    }

    void disable() {
        version++;
        prefs.edit().clear().apply();
        cached = null; failed = false;
        listener.changed(null, false);
    }

    void refresh(boolean force) {
        if (closed || mode().equals("off")) return;
        listener.changed(cached, failed);
        long now = System.currentTimeMillis();
        if (!force && cached != null && now - cached.observedAt < WeatherModel.FRESH_MS) return;
        if (busy || !force && lastAttempt != 0 && SystemClock.elapsedRealtime() - lastAttempt < 5 * 60_000L) return;
        busy = true;
        lastAttempt = SystemClock.elapsedRealtime();
        int request = version;
        String mode = mode();
        Place stored = storedPlace();
        long locatedAt = prefs.getLong("located_at", 0);
        worker.execute(() -> {
            Snapshot result = null;
            Place place = stored;
            boolean located = false;
            try {
                if (mode.equals("auto") && (place == null || now < locatedAt || now - locatedAt > 24 * 60 * 60_000L)) {
                    JSONObject geo = get("https://ipwho.is/?lang=zh-CN&fields=success,city,latitude,longitude");
                    if (!geo.optBoolean("success", false)) throw new IllegalStateException("Location unavailable");
                    place = new Place(geo.getString("city"), geo.getDouble("latitude"), geo.getDouble("longitude"));
                    located = true;
                }
                if (place == null) throw new IllegalStateException("No city selected");
                JSONObject data = get("https://api.open-meteo.com/v1/forecast?latitude=" + place.latitude
                        + "&longitude=" + place.longitude + "&current=temperature_2m,weather_code,is_day&timezone=auto");
                if (!"°C".equals(data.getJSONObject("current_units").getString("temperature_2m"))) {
                    throw new IllegalStateException("Unexpected weather unit");
                }
                JSONObject current = data.getJSONObject("current");
                long observedAt = LocalDateTime.parse(current.getString("time"))
                        .toInstant(ZoneOffset.ofTotalSeconds(data.getInt("utc_offset_seconds"))).toEpochMilli();
                result = new Snapshot(place.name, current.getDouble("temperature_2m"),
                        current.getInt("weather_code"), current.getInt("is_day") == 1, observedAt);
            } catch (Exception ignored) {
                // The UI keeps a marked, age-limited cache; coordinates and response bodies are not logged.
            }
            Snapshot update = result;
            Place selected = place;
            boolean saveLocation = located;
            main.post(() -> {
                busy = false;
                if (closed) return;
                if (request != version) { refresh(true); return; }
                failed = update == null;
                if (update != null) {
                    cached = update;
                    if (saveLocation) savePlace(selected, "auto");
                    saveSnapshot(update);
                }
                listener.changed(cached, failed);
            });
        });
    }

    void search(String query, Cities callback) {
        if (closed || query.trim().isEmpty()) return;
        worker.execute(() -> {
            List<Place> places = new ArrayList<>();
            try {
                JSONObject data = get("https://geocoding-api.open-meteo.com/v1/search?count=5&language=zh&format=json&name="
                        + URLEncoder.encode(query.trim(), "UTF-8"));
                JSONArray results = data.optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    String name = item.getString("name");
                    String region = item.optString("admin1", "");
                    if (!region.isEmpty() && !name.equals(region)) name += " · " + region;
                    String country = item.optString("country", "");
                    if (!country.isEmpty()) name += " · " + country;
                    places.add(new Place(name, item.getDouble("latitude"), item.getDouble("longitude")));
                }
            } catch (Exception ignored) { places.clear(); }
            main.post(() -> { if (!closed) callback.loaded(places); });
        });
    }

    private Place storedPlace() {
        try {
            return new Place(prefs.getString("city", ""),
                    Double.parseDouble(prefs.getString("latitude", "")),
                    Double.parseDouble(prefs.getString("longitude", "")));
        } catch (Exception ignored) { return null; }
    }

    private void savePlace(Place place, String mode) {
        prefs.edit().putString("mode", mode).putString("city", place.name)
                .putString("latitude", Double.toString(place.latitude))
                .putString("longitude", Double.toString(place.longitude))
                .putLong("located_at", System.currentTimeMillis()).apply();
    }

    private void saveSnapshot(Snapshot snapshot) {
        try {
            JSONObject data = new JSONObject();
            data.put("city", snapshot.city).put("temperature", snapshot.temperature)
                    .put("code", snapshot.code).put("day", snapshot.day).put("observed_at", snapshot.observedAt);
            prefs.edit().putString("cache", data.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static Snapshot parseSnapshot(JSONObject data) throws Exception {
        return new Snapshot(data.getString("city"), data.getDouble("temperature"),
                data.getInt("code"), data.getBoolean("day"), data.getLong("observed_at"));
    }

    private static JSONObject get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Weather request failed");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 256 * 1024) throw new IllegalStateException("Response too large");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    void close() {
        closed = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
