package com.sogud.bbtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exists only during an explicitly opened phone-setup session; no persistent web service. */
final class PhoneServer extends NanoHTTPD {
    interface Host {
        List<AppAdapter.Entry> apps();
        void changed();
        WeatherController weather();
        void main(Runnable action);
    }
    private final Context context;
    private final MediaLibrary media;
    private final Host host;
    private final String prefix;
    private final long expiresAt = SystemClock.elapsedRealtime() + 10 * 60_000L;
    private final String address;

    PhoneServer(Context context, String address, Host host) {
        super("0.0.0.0", 8790);
        this.context = context.getApplicationContext();
        this.address = address;
        this.host = host;
        media = new MediaLibrary(context);
        byte[] token = new byte[24];
        new SecureRandom().nextBytes(token);
        prefix = "/" + android.util.Base64.encodeToString(token,
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING);
    }

    String url() { return "http://" + address + ":8790" + prefix + "/"; }

    @Override public Response serve(IHTTPSession session) {
        String path = session.getUri();
        try {
            if (SystemClock.elapsedRealtime() >= expiresAt || ParentalLock.locked(context)) return error(Response.Status.GONE);
            String remote = session.getRemoteIpAddress();
            InetAddress peer = InetAddress.getByName(remote);
            if (!(peer.isSiteLocalAddress() || peer.isLoopbackAddress() || peer.isLinkLocalAddress())) return error(Response.Status.FORBIDDEN);
            if (!path.startsWith(prefix + "/")) return error(Response.Status.NOT_FOUND);
            String origin = session.getHeaders().get("origin");
            if (origin != null && !origin.equals("http://" + address + ":8790")) return error(Response.Status.FORBIDDEN);
            if ("cross-site".equals(session.getHeaders().get("sec-fetch-site"))) return error(Response.Status.FORBIDDEN);
            String route = path.substring(prefix.length());
            if (session.getMethod() == Method.GET) return get(route, session);
            if (session.getMethod() != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED);
            String rawLength = session.getHeaders().get("content-length");
            if (rawLength == null) return error(Response.Status.LENGTH_REQUIRED);
            long length = Long.parseLong(rawLength);
            long limit = route.equals("/api/upload") || route.equals("/api/icon") ? MediaLibrary.MAX_UPLOAD + 65536 : 8192;
            if (length < 0 || length > limit || session.getHeaders().containsKey("transfer-encoding")) return error(Response.Status.PAYLOAD_TOO_LARGE);
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            return post(route, session.getParms(), files);
        } catch (IllegalArgumentException error) { return error(Response.Status.BAD_REQUEST); }
        catch (Exception error) { return error(Response.Status.INTERNAL_ERROR); }
    }

    private Response get(String route, IHTTPSession session) throws Exception {
        if (route.equals("/")) {
            try (InputStream in = context.getAssets().open("phone.html"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
                return secure(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", out.toString("UTF-8")));
            }
        }
        if (route.equals("/api/state")) {
            JSONObject state = new JSONObject();
            state.put("selected", media.selected());
            state.put("screensaver", HomeOptions.text(context, "screensaver_mode", "wallpaper"));
            state.put("layout", HomeOptions.text(context, "layout", "dock"));
            state.put("focus", HomeOptions.text(context, "focus_style", "standard"));
            state.put("names", HomeOptions.text(context, "name_style", "hidden"));
            JSONArray wallpapers = new JSONArray();
            for (MediaLibrary.Item item : media.items()) wallpapers.put(new JSONObject()
                    .put("id", item.id).put("title", item.title).put("caption", item.caption));
            state.put("wallpapers", wallpapers);
            JSONArray apps = new JSONArray();
            for (AppAdapter.Entry entry : host.apps()) if (entry.input == null) {
                File icon = media.customIcon(entry.pkg);
                apps.put(new JSONObject().put("key", entry.pkg).put("label", entry.label)
                        .put("custom", icon != null && icon.isFile()));
            }
            state.put("apps", apps);
            state.put("weather", host.weather().mode());
            return json(state);
        }
        if (route.equals("/api/thumb")) {
            String id = session.getParms().getOrDefault("id", "legacy");
            boolean found = false;
            for (MediaLibrary.Item item : media.items()) if (item.id.equals(id)) found = true;
            if (!found) return error(Response.Status.NOT_FOUND);
            Bitmap bitmap = media.picture(id, 384, 216);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output); }
            finally { bitmap.recycle(); }
            byte[] bytes = output.toByteArray();
            return secure(newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(bytes), bytes.length));
        }
        return error(Response.Status.NOT_FOUND);
    }

    private Response post(String route, Map<String, String> params, Map<String, String> files) throws Exception {
        String value = params.getOrDefault("value", "");
        switch (route) {
            case "/api/wallpaper":
                if (!media.select(value)) return error(Response.Status.BAD_REQUEST);
                break;
            case "/api/setting":
                String key = params.getOrDefault("key", "");
                if (!HomeOptions.validChoice(key, value)) return error(Response.Status.BAD_REQUEST);
                HomeOptions.set(context, key, value);
                break;
            case "/api/daily":
                HomeOptions.set(context, "daily_enabled", true);
                media.refreshDaily(true);
                media.select("daily:latest");
                break;
            case "/api/delete":
                if (!media.delete(value)) return error(Response.Status.BAD_REQUEST);
                break;
            case "/api/upload":
                String upload = files.get("file");
                if (upload == null) return error(Response.Status.BAD_REQUEST);
                value = media.addPhoto(new File(upload));
                media.select(value);
                break;
            case "/api/icon":
            case "/api/icon/reset":
                String pkg = params.getOrDefault("app", "");
                boolean installed = false;
                for (AppAdapter.Entry app : host.apps()) if (app.input == null && app.pkg.equals(pkg)) installed = true;
                if (!installed) return error(Response.Status.BAD_REQUEST);
                if (route.endsWith("/reset")) media.resetIcon(pkg);
                else {
                    String icon = files.get("file");
                    if (icon == null) return error(Response.Status.BAD_REQUEST);
                    media.saveIcon(pkg, new File(icon));
                }
                break;
            case "/api/weather/auto": host.main(() -> host.weather().setAutomatic()); break;
            case "/api/weather/off": host.main(() -> host.weather().disable()); break;
            case "/api/weather/city":
                if (value.trim().isEmpty() || value.length() > 100) return error(Response.Status.BAD_REQUEST);
                CountDownLatch found = new CountDownLatch(1);
                String city = value;
                final boolean[] success = {false};
                host.main(() -> host.weather().search(city, places -> {
                    if (!places.isEmpty()) { host.weather().setPlace(places.get(0)); success[0] = true; }
                    found.countDown();
                }));
                if (!found.await(20, TimeUnit.SECONDS) || !success[0]) return error(Response.Status.BAD_REQUEST);
                break;
            default: return error(Response.Status.NOT_FOUND);
        }
        host.main(host::changed);
        return json(new JSONObject().put("ok", true));
    }

    private Response json(JSONObject data) {
        return secure(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", data.toString()));
    }

    private Response error(Response.Status status) {
        return secure(newFixedLengthResponse(status, "application/json", "{\"ok\":false}"));
    }

    private Response secure(Response response) {
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        return response;
    }
}
