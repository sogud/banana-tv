package com.sogud.bananatv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maintainer-controlled feeds, verified APK downloads and user-confirmed upgrades. */
final class ReleaseUpdates {
    static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    private static final String GITHUB = "https://api.github.com/repos/sogud/banana-tv/releases/latest";

    static final class Release {
        final JSONObject json;
        final String name;
        final long code;
        final long size;
        final int minSdk;
        final String sha256;
        final String notes;
        final List<URL> urls = new ArrayList<>();

        Release(JSONObject json, Context context) throws Exception {
            this.json = json;
            name = json.getString("versionName");
            code = json.getLong("versionCode");
            size = json.getLong("size");
            minSdk = json.getInt("minSdk");
            sha256 = json.getString("sha256");
            notes = json.optString("notes", "");
            String current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            String variant = current.endsWith("-guard") ? "guard" : "standard";
            if (json.getInt("schemaVersion") != 1 || !context.getPackageName().equals(json.getString("packageName"))
                    || !variant.equals(json.getString("variant")) || code <= 0 || size <= 0 || size > MAX_APK_BYTES
                    || minSdk < 28 || !sha256.matches("[a-fA-F0-9]{64}")
                    || !name.matches("[0-9]+(\\.[0-9]+){1,3}([-.][A-Za-z0-9]+)*") || notes.length() > 4000) {
                throw new IOException("Invalid release metadata");
            }
            JSONArray mirrors = json.getJSONArray("urls");
            if (mirrors.length() == 0 || mirrors.length() > 4) throw new IOException("Invalid mirrors");
            for (int i = 0; i < mirrors.length(); i++) urls.add(https(mirrors.getString(i)));
        }
    }

    static final class Check {
        final Release release;
        final String name;
        final boolean fresh;
        Check(Release release, String name, boolean fresh) { this.release = release; this.name = name; this.fresh = fresh; }
    }

    interface Progress { void changed(long bytes, long total); }

    static final class Transfer {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        void cancel() {
            cancelled = true;
            HttpURLConnection active = connection;
            if (active != null) active.disconnect();
        }
        void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
        }
    }

    static void refresh(Context context) {
        clearInstalledDownloads(context);
        if (HomeOptions.flag(context, "update_auto", true)) fetch(context, false);
    }

    private static void clearInstalledDownloads(Context context) {
        try {
            long current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
            File[] files = new File(context.getCacheDir(), "updates").listFiles();
            if (files == null) return;
            for (File file : files) {
                String name = file.getName();
                if (name.matches("update-[0-9]+-[a-f0-9]{64}\\.apk") && Long.parseLong(name.split("-")[1]) <= current) file.delete();
            }
        } catch (Exception ignored) { }
    }

    static synchronized Check fetch(Context context, boolean force) {
        long now = System.currentTimeMillis();
        long attempt = HomeOptions.prefs(context).getLong("update_attempt", 0);
        long checked = HomeOptions.prefs(context).getLong("update_checked", 0);
        if (!force && (now - attempt < 3600_000L || now - checked < 24 * 3600_000L)) return cached(context, false);
        HomeOptions.prefs(context).edit().putLong("update_attempt", now).apply();
        try {
            JSONArray feeds;
            try (InputStream input = context.getAssets().open("update-feeds.json")) {
                feeds = new JSONArray(new String(read(input, 16 * 1024), StandardCharsets.UTF_8));
            }
            if (feeds.length() > 4) throw new IOException("Too many feeds");
            List<URL> addresses = new ArrayList<>();
            for (int i = 0; i < feeds.length(); i++) addresses.add(https(feeds.getString(i)));
            Check result = fetchFeeds(context, addresses);
            if (result != null) return result;
            return fetchGitHub(context);
        } catch (Exception ignored) { return cached(context, false); }
    }

    static Check fetchFeeds(Context context, List<URL> feeds) {
        Check previous = cached(context, false);
        for (URL feed : feeds) {
            try {
                Release release = new Release(new JSONObject(metadata(feed)), context);
                if (previous.release != null && release.code < previous.release.code) continue;
                save(context, release, release.name);
                return new Check(release, release.name, true);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static Check fetchGitHub(Context context) {
        Check previous = cached(context, false);
        try {
            JSONObject latest = new JSONObject(metadata(https(GITHUB)));
            String tag = latest.getString("tag_name");
            if (!tag.matches("v?[0-9]+(\\.[0-9]+){1,3}([-.][A-Za-z0-9]+)*")) throw new IOException("Invalid tag");
            JSONArray assets = latest.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!"update.json".equals(asset.optString("name"))) continue;
                Release release = new Release(new JSONObject(metadata(https(asset.getString("browser_download_url")))), context);
                if (previous.release != null && release.code < previous.release.code) return previous;
                save(context, release, release.name);
                return new Check(release, release.name, true);
            }
            // Older releases remain visible but cannot be installed without verified metadata.
            if (previous.release != null && !newer(tag, previous.name)) return previous;
            save(context, null, tag);
            return new Check(null, tag, true);
        } catch (Exception ignored) { return cached(context, false); }
    }

    private static void save(Context context, Release release, String name) {
        HomeOptions.prefs(context).edit().putString("update_manifest", release == null ? "" : release.json.toString())
                .putString("latest_version", name).putLong("update_checked", System.currentTimeMillis()).apply();
    }

    static Check cached(Context context, boolean fresh) {
        Release release = null;
        try { release = new Release(new JSONObject(HomeOptions.text(context, "update_manifest", "")), context); }
        catch (Exception ignored) { }
        return new Check(release, HomeOptions.text(context, "latest_version", ""), fresh);
    }

    static String available(Context context) {
        Check check = cached(context, false);
        try {
            PackageInfo current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            boolean newer = check.release == null ? newer(check.name, current.versionName) : check.release.code > current.getLongVersionCode();
            return newer ? check.name : "";
        } catch (Exception ignored) { return ""; }
    }

    static File download(Context context, Release release, Transfer transfer, Progress progress) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No update storage");
        File pending = File.createTempFile("update-", ".part", directory);
        File ready = downloaded(context, release);
        try {
            for (URL url : release.urls) {
                transfer.check();
                try {
                    HttpURLConnection connection = open(url, transfer);
                    try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(pending)) {
                        if (directory.getUsableSpace() < release.size + 16L * 1024 * 1024) throw new IOException("Insufficient storage");
                        long total = 0;
                        byte[] buffer = new byte[64 * 1024]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            transfer.check();
                            total += count;
                            if (total > release.size) throw new IOException("APK exceeds expected size");
                            output.write(buffer, 0, count);
                            progress.changed(total, release.size);
                        }
                        if (total != release.size) throw new IOException("Incomplete APK");
                    } finally { connection.disconnect(); transfer.connection = null; }
                    verify(context, release, pending);
                    transfer.check();
                    if (!pending.renameTo(ready)) throw new IOException("Cannot save update");
                    return ready;
                } catch (Exception error) {
                    pending.delete();
                    transfer.check();
                }
            }
        } finally { pending.delete(); }
        throw new IOException("No verified download available");
    }

    static File downloaded(Context context, Release release) {
        return new File(new File(context.getCacheDir(), "updates"),
                "update-" + release.code + "-" + release.sha256.toLowerCase(java.util.Locale.ROOT) + ".apk");
    }

    static void verify(Context context, Release release, File apk) throws Exception {
        if (release.minSdk > Build.VERSION.SDK_INT || apk.length() != release.size) throw new IOException("Incompatible APK size or SDK");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(apk)) {
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!actual.toString().equalsIgnoreCase(release.sha256)) throw new IOException("APK checksum mismatch");
        PackageManager manager = context.getPackageManager();
        PackageInfo current = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || !current.packageName.equals(archive.packageName) || archive.getLongVersionCode() != release.code
                || archive.getLongVersionCode() <= current.getLongVersionCode() || !release.name.equals(archive.versionName)
                || archive.applicationInfo == null || archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT
                || archive.applicationInfo.minSdkVersion != release.minSdk
                || !signers(current).equals(signers(archive)) || signers(current).isEmpty()) {
            throw new IOException("APK identity, version or signing certificate mismatch");
        }
    }

    private static Set<Signature> signers(PackageInfo info) {
        Set<Signature> result = new HashSet<>();
        if (info.signingInfo != null) java.util.Collections.addAll(result, info.signingInfo.getApkContentsSigners());
        return result;
    }

    static Intent installIntent(Context context, File apk) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context,
                context.getPackageName() + ".updates", apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static URL https(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo() != null
                || url.getRef() != null || value.length() > 4096) throw new IOException("HTTPS required");
        return url;
    }

    private static String metadata(URL url) throws Exception {
        Transfer transfer = new Transfer();
        HttpURLConnection connection = open(url, transfer);
        try (InputStream input = connection.getInputStream()) { return new String(read(input, 256 * 1024), StandardCharsets.UTF_8); }
        finally { connection.disconnect(); }
    }

    private static HttpURLConnection open(URL url, Transfer transfer) throws Exception {
        for (int i = 0; i < 6; i++) {
            transfer.check();
            HttpURLConnection connection = (HttpURLConnection) https(url.toString()).openConnection();
            transfer.connection = connection;
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "BananaTV-Updater");
            try {
                transfer.check();
                int status = connection.getResponseCode();
                if (status == 200) return connection;
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) throw new IOException("Request failed");
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Missing redirect");
                url = https(new URL(url, location).toString());
            } catch (Exception error) { connection.disconnect(); throw error; }
            connection.disconnect();
        }
        throw new IOException("Too many redirects");
    }

    private static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Response too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static boolean newer(String latest, String current) {
        String[] left = latest.replaceFirst("^v", "").split("[-+]", 2)[0].split("\\.");
        String[] right = current.replaceFirst("^v", "").split("[-+]", 2)[0].split("\\.");
        try {
            for (int i = 0; i < Math.max(left.length, right.length); i++) {
                int a = i < left.length ? Integer.parseInt(left[i]) : 0;
                int b = i < right.length ? Integer.parseInt(right[i]) : 0;
                if (a != b) return a > b;
            }
        } catch (NumberFormatException ignored) { return false; }
        return false;
    }
}
