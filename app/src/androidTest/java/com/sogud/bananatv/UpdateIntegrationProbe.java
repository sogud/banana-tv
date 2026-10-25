package com.sogud.bananatv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Local HTTPS fixtures only; never runs against the owner's launcher or physical TV. */
public final class UpdateIntegrationProbe extends Instrumentation {
    private Bundle arguments;
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        SSLSocketFactory original = HttpsURLConnection.getDefaultSSLSocketFactory();
        Activity activity = null;
        try {
            require("1".equals(shellProperty()) && context.getPackageName().equals("com.sogud.bananatv.updatetest"), "Disposable emulator required");
            if ("true".equals(arguments.getString("install_only"))) {
                installAfterRestart(context);
                result.putString("result", "PASS"); result.putInt("checks", checks);
                result.putString("scope", "Restored verified APK after process restart; Android confirmation left open");
                finish(Activity.RESULT_OK, result);
                return;
            }
            KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
            keys.load(null);
            byte[] certificate = Base64.decode(arguments.getString("certificate"), Base64.DEFAULT);
            keys.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate)));
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keys);
            SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, trust.getTrustManagers(), null);
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            String base = arguments.getString("base");
            require(base.startsWith("https://localhost:"), "Loopback fixture required");
            HomeOptions.prefs(context).edit().remove("update_manifest").remove("latest_version").commit();
            ReleaseUpdates.Check check = ReleaseUpdates.fetchFeeds(context, Arrays.asList(new URL(base + "/failed"), new URL(base + "/valid.json")));
            require(check != null && check.fresh && check.release.code == 14, "Feed failover");
            require(!ReleaseUpdates.available(context).isEmpty(), "Increasing versionCode detected even with same versionName");
            require(ReleaseUpdates.fetchFeeds(context, Arrays.asList(new URL(base + "/malformed"))) == null, "Malformed feed rejected");
            require(ReleaseUpdates.cached(context, false).release.code == 14, "Failed refresh preserves metadata");
            reject(() -> ReleaseUpdates.https("http://localhost/file"), "Cleartext URL rejected");
            reject(() -> ReleaseUpdates.https("https://user:password@example.com/file"), "Credential URL rejected");
            reject(() -> new ReleaseUpdates.Release(new JSONObject(check.release.json.toString()).put("packageName", "other.app"), context), "Foreign package metadata rejected");
            reject(() -> new ReleaseUpdates.Release(new JSONObject(check.release.json.toString()).put("variant", "guard"), context), "Guard/standard mixing rejected");
            reject(() -> new ReleaseUpdates.Release(new JSONObject(check.release.json.toString()).put("schemaVersion", 2), context), "Unknown schema rejected");
            File apk = ReleaseUpdates.download(context, check.release, new ReleaseUpdates.Transfer(), (bytes, total) -> { });
            require(apk.isFile(), "Download mirror failover, redirect, SHA256 and signer verification");
            ReleaseUpdates.verify(context, check.release, apk);
            require(true, "Candidate reverified before install");
            reject(() -> ReleaseUpdates.download(context, manifest(context, base + "/bad-hash.json"), new ReleaseUpdates.Transfer(), (bytes, total) -> { }), "Hash mismatch rejected");
            reject(() -> ReleaseUpdates.download(context, manifest(context, base + "/wrong-signer.json"), new ReleaseUpdates.Transfer(), (bytes, total) -> { }), "Different signing certificate rejected");
            reject(() -> ReleaseUpdates.download(context, manifest(context, base + "/downgrade.json"), new ReleaseUpdates.Transfer(), (bytes, total) -> { }), "Same/older versionCode rejected");
            reject(() -> ReleaseUpdates.download(context, manifest(context, base + "/wrong-version.json"), new ReleaseUpdates.Transfer(), (bytes, total) -> { }), "APK version must match manifest");
            reject(() -> ReleaseUpdates.download(context, manifest(context, base + "/https-downgrade.json"), new ReleaseUpdates.Transfer(), (bytes, total) -> { }), "HTTPS to HTTP redirect rejected");
            reject(() -> ReleaseUpdates.download(context, manifest(context, base + "/truncated.json"), new ReleaseUpdates.Transfer(), (bytes, total) -> { }), "Truncated download rejected");
            ReleaseUpdates.Transfer cancelled = new ReleaseUpdates.Transfer();
            reject(() -> ReleaseUpdates.download(context, check.release, cancelled, (bytes, total) -> cancelled.cancel()), "Cancel stops transfer");
            File[] files = new File(context.getCacheDir(), "updates").listFiles();
            if (files != null) for (File file : files) require(!file.getName().endsWith(".part"), "Partial files removed");
            ReleaseUpdates.verify(context, check.release, apk);
            require(true, "Bad downloads preserve the previous verified APK");
            Intent install = ReleaseUpdates.installIntent(context, apk);
            require("content".equals(install.getData().getScheme()) && (install.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0, "Installer receives a temporary content URI");
            context.getSharedPreferences("update-probe", 0).edit().putString("sentinel", "preserved").commit();
            require(apk.delete(), "Remove helper download before exercising the actual download UI");
            // Render the real update panel, then download through its public action.
            activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity home = activity;
            runOnMainSync(() -> {
                try {
                    java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("showUpdateResult", ReleaseUpdates.Check.class);
                    method.setAccessible(true); method.invoke(home, check);
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            Thread.sleep(800);
            File capture = new File(context.getFilesDir(), "update-panel.png");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(capture)) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
            require(!context.getPackageManager().canRequestPackageInstalls(), "Permission starts disabled for fixture");
            click(home, context.getString(R.string.update_download));
            boolean ready = false;
            for (int i = 0; i < 100 && !ready; i++) {
                Thread.sleep(200);
                ready = hasAction(home, context.getString(R.string.update_allow));
            }
            require(ready, "Download UI reaches verified APK and permission prompt");
            require(ReleaseUpdates.downloaded(context, check.release).isFile(), "Verified APK survives the permission step");
            result.putString("result", "PASS"); result.putInt("checks", checks);
            result.putString("scope", "Disposable emulator; permission prompt checked before the external process restart");
        } catch (Exception error) {
            result.putString("result", "FAIL"); result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
        }
        finish("PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void installAfterRestart(Context context) throws Exception {
        ReleaseUpdates.Check check = ReleaseUpdates.cached(context, false);
        require(check.release != null && check.release.code == 14, "Manifest restored after restart");
        File apk = ReleaseUpdates.downloaded(context, check.release);
        ReleaseUpdates.verify(context, check.release, apk);
        require(context.getPackageManager().canRequestPackageInstalls(), "Permission restored after restart");
        Activity home = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        showUpdatePanel(home, check);
        require(hasAction(home, context.getString(R.string.update_install)), "Cached APK offers install without another download");
        click(home, context.getString(R.string.update_install));
        click(home, context.getString(R.string.update_install));
        Thread.sleep(1500);
        shell("input keyevent 4");
        Thread.sleep(800);
        require(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode() == 13,
                "Cancelling installer preserves the installed version");
        showUpdatePanel(home, check);
        click(home, context.getString(R.string.update_install));
        click(home, context.getString(R.string.update_install));
        Thread.sleep(1500);
    }

    private void showUpdatePanel(Activity home, ReleaseUpdates.Check check) {
        runOnMainSync(() -> {
            try {
                java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("showUpdateResult", ReleaseUpdates.Check.class);
                method.setAccessible(true); method.invoke(home, check);
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
    }

    private String shellProperty() throws Exception {
        try (InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("getprop ro.kernel.qemu"))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private void shell(String command) throws Exception {
        try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            input.readAllBytes();
        }
    }

    private android.view.View action(Activity home, String label) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("activePanel");
            field.setAccessible(true);
            android.app.Dialog dialog = (android.app.Dialog) field.get(home);
            return dialog == null ? null : actionView(dialog.getWindow().getDecorView(), label);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private android.view.View actionView(android.view.View view, String label) {
        if (view.isClickable() && label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = actionView(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean hasAction(Activity home, String label) {
        boolean[] found = {false};
        runOnMainSync(() -> found[0] = action(home, label) != null);
        return found[0];
    }

    private void click(Activity home, String label) {
        runOnMainSync(() -> {
            android.view.View view = action(home, label);
            if (view == null) throw new IllegalStateException("Missing action: " + label);
            view.performClick();
        });
    }

    private ReleaseUpdates.Release manifest(Context context, String address) throws Exception {
        try (InputStream input = new URL(address).openStream()) {
            return new ReleaseUpdates.Release(new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)), context);
        }
    }

    interface Attempt { void run() throws Exception; }
    private void reject(Attempt attempt, String message) throws Exception {
        try { attempt.run(); } catch (Exception expected) { checks++; return; }
        throw new IllegalStateException(message);
    }
    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
        checks++;
    }
}
