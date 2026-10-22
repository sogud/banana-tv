package com.sogud.bbtv;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owned, disposable emulator fixtures. No real app sessions or personal images. */
final class FeatureParityProbe {
    static Bundle run(Context context) {
        Bundle result = new Bundle();
        SharedPreferences prefs = HomeOptions.prefs(context);
        Map<String, ?> original = prefs.getAll();
        MediaLibrary media = new MediaLibrary(context);
        File image = new File(context.getCacheDir(), "parity-fixture.png");
        File invalid = new File(context.getCacheDir(), "parity-invalid.txt");
        File icon = media.customIcon(context.getPackageName());
        byte[] iconBefore = read(icon);
        List<String> created = new ArrayList<>();
        PhoneServer server = null;
        WeatherController weather = new WeatherController(context, (snapshot, failed) -> { });
        int checks = 0;
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) {
            weather.close(); result.putString("result", "FAIL"); result.putString("error", "Emulator only"); return result;
        }
        try {
            prefs.edit().clear().commit();
            require(LauncherPreferences.dockPercent(context) == 90, "Legacy dock default"); checks++;
            require(HomeOptions.columns(context) == 4 && media.selected().equals("legacy"), "Legacy layout and wallpaper"); checks++;
            require(TileOrder.reconcile(Arrays.asList("missing", "b"), Arrays.asList("a", "b")).equals(Arrays.asList("missing", "b", "a")), "Missing app order"); checks++;
            require(TileOrder.move(Arrays.asList("a", "b", "c"), "a", "c").equals(Arrays.asList("b", "c", "a")), "Shift order"); checks++;
            require(!HomeOptions.validChoice("boot_ad_switch", "0") && !HomeOptions.validChoice("layout", "anything"), "Setting allowlist"); checks++;
            require(AerialCatalog.load(context).size() == 114, "Full aerial catalogue"); checks++;
            require(ReleaseUpdates.newer("v0.2.0", "0.1.11") && !ReleaseUpdates.newer("v0.1.11", "0.1.11-guard")
                    && !ReleaseUpdates.newer("invalid", "0.1.11"), "Version comparison"); checks++;
            for (int i = 0; i < GradientWallpapers.KEYS.length; i++) {
                Bitmap gradient = GradientWallpapers.render(i, 96, 54);
                require(gradient.getPixel(12, 12) != gradient.getPixel(80, 40), "Gradient variation"); gradient.recycle();
            } checks++;
            ParentalLock.set(context, "4726"); require(!ParentalLock.locked(context), "PIN unlock");
            ParentalLock.relock(); require(ParentalLock.locked(context), "Home relock");
            require(!ParentalLock.verify(context, "0000") && ParentalLock.verify(context, "4726"), "PIN verification");
            ParentalLock.disable(context); checks++;
            Bitmap fixture = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888); fixture.eraseColor(0xFF507090);
            try (FileOutputStream output = new FileOutputStream(image)) { fixture.compress(Bitmap.CompressFormat.PNG, 100, output); }
            fixture.recycle();
            String photo = media.addPhoto(image); created.add(photo);
            require(media.select(photo) && media.picture(photo, 80, 50) != null, "Photo import and selection"); checks++;
            try (FileOutputStream output = new FileOutputStream(invalid)) { output.write("invalid image".getBytes(StandardCharsets.UTF_8)); }
            boolean rejected = false;
            try { media.addPhoto(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected && !media.delete("photo:../../outside") && !media.select("daily:../../outside"), "Malformed image and traversal"); checks++;
            PhoneServer.Host host = new PhoneServer.Host() {
                @Override public List<AppAdapter.Entry> apps() { return Arrays.asList(new AppAdapter.Entry("Fixture",
                        new ComponentName(context, MainActivity.class), null, null)); }
                @Override public void changed() { }
                @Override public WeatherController weather() { return weather; }
                @Override public void main(Runnable action) { action.run(); }
            };
            server = new PhoneServer(context, "127.0.0.1", host); server.start(3000, true);
            String prefix = URI.create(server.url()).getPath().replaceAll("/$", "");
            require(call("GET", "/bad/api/state", "", null).startsWith("HTTP/1.1 404"), "Invalid pairing token"); checks++;
            require(call("GET", prefix + "x/api/state", "", null).startsWith("HTTP/1.1 404"), "Exact token prefix"); checks++;
            String state = call("GET", prefix + "/api/state", "", null);
            require(state.startsWith("HTTP/1.1 200") && state.contains("no-store") && state.contains("wallpapers"), "Phone state"); checks++;
            require(call("PUT", prefix + "/api/state", "", null).startsWith("HTTP/1.1 405"), "HTTP method"); checks++;
            require(call("POST", prefix + "/api/setting", "key=layout&value=grid", "Origin: https://invalid.example\r\n").startsWith("HTTP/1.1 403"), "Cross-origin request"); checks++;
            require(call("POST", prefix + "/api/setting", "key=layout&value=grid", null).startsWith("HTTP/1.1 200")
                    && HomeOptions.columns(context) == 5, "Phone layout persistence"); checks++;
            require(call("POST", prefix + "/api/setting", "key=boot_ad_switch&value=0", null).startsWith("HTTP/1.1 400"), "No arbitrary system settings"); checks++;
            require(call("POST", prefix + "/api/upload", "", "Content-Length: 22000000\r\n").startsWith("HTTP/1.1 413"), "Oversized upload"); checks++;
            require(call("POST", prefix + "/api/icon/reset", "app=com.missing.fixture", null).startsWith("HTTP/1.1 400"), "Installed app validation"); checks++;
            media.saveIcon(context.getPackageName(), image);
            require(icon.isFile() && media.resetIcon(context.getPackageName()) && !icon.isFile(), "Custom icon and reset"); checks++;
            PhoneServer second = new PhoneServer(context, "127.0.0.1", host);
            require(!second.url().equals(server.url()), "Rotating pairing token"); checks++;
            java.lang.reflect.Field expiry = PhoneServer.class.getDeclaredField("expiresAt"); expiry.setAccessible(true); expiry.setLong(server, 0L);
            require(call("GET", prefix + "/api/state", "", null).startsWith("HTTP/1.1 410"), "Pairing expiry"); checks++;
            require(media.delete(photo), "Photo removal"); created.remove(photo); checks++;
            result.putString("result", "PASS");
            result.putInt("checks", checks);
            result.putString("scope", "Disposable emulator fixtures; excludes physical Home, sources, backlight and boot recovery.");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); result.putInt("checks", checks);
        } finally {
            if (server != null) server.stop(); weather.close();
            for (String id : created) media.delete(id);
            image.delete(); invalid.delete();
            if (iconBefore == null) icon.delete();
            else try (FileOutputStream output = new FileOutputStream(icon)) { output.write(iconBefore); } catch (Exception ignored) { }
            restore(prefs, original); ParentalLock.relock();
        }
        return result;
    }

    private static void require(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
    private static byte[] read(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream input = new java.io.FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        } catch (Exception ignored) { return null; }
    }
    private static String call(String method, String path, String body, String headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", 8790)) {
            socket.setSoTimeout(5000);
            String length = headers != null && headers.contains("Content-Length:") ? "" : "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n";
            String request = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8790\r\nConnection: close\r\n"
                    + "Content-Type: application/x-www-form-urlencoded\r\n" + length + (headers == null ? "" : headers) + "\r\n" + body;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream response = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
            while ((count = socket.getInputStream().read(bytes)) != -1) response.write(bytes, 0, count);
            return response.toString("UTF-8");
        }
    }
    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences prefs, Map<String, ?> original) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
        }
        edit.commit();
    }
}
