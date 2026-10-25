package com.sogud.bananatv.media;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Resolve an ordinary web player without exposing Android methods or accepting invalid TLS. */
final class MediaWebResolver {
    private MediaWebResolver() { }

    static JSONObject resolve(Context context, String address, JSONObject headers) throws Exception {
        return resolve(context, address, headers, 12000);
    }
    static JSONObject resolve(Context context, String address, JSONObject headers, long timeoutMillis) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Web resolution must run off the UI thread");
        if (!http(address)) throw new IOException("Unsupported web-player address");
        Handler main = new Handler(Looper.getMainLooper());
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        WebView[] browser = {null};
        main.post(() -> {
            if (result.isDone()) return;
            try {
                WebView view = new WebView(context); browser[0] = view;
                WebSettings settings = view.getSettings();
                settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
                settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
                settings.setMediaPlaybackRequiresUserGesture(true);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                Map<String, String> requestHeaders = new HashMap<>();
                if (headers != null) for (Iterator<String> keys = headers.keys(); keys.hasNext();) {
                    String key = keys.next(); requestHeaders.put(key, headers.optString(key));
                    if (key.equalsIgnoreCase("User-Agent")) settings.setUserAgentString(headers.optString(key));
                }
                String userAgent = settings.getUserAgentString();
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
                view.setWebViewClient(new WebViewClient() {
                    private void found(String url, Map<String, String> request) {
                        if (!http(url) || result.isDone()) return;
                        try {
                            JSONObject mediaHeaders = new JSONObject(request);
                            if (!contains(request, "User-Agent")) mediaHeaders.put("User-Agent", userAgent);
                            if (!contains(request, "Referer")) mediaHeaders.put("Referer", address);
                            String cookie = CookieManager.getInstance().getCookie(url);
                            if (cookie != null && !contains(request, "Cookie")) mediaHeaders.put("Cookie", cookie);
                            result.complete(new JSONObject().put("url", url).put("parse", 0).put("header", mediaHeaders));
                        } catch (Exception error) { result.completeExceptionally(error); }
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        return !http(request.getUrl().toString());
                    }
                    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        if (direct(request.getUrl().toString())) found(request.getUrl().toString(), request.getRequestHeaders());
                        return null;
                    }
                    @Override public void onPageFinished(WebView view, String url) {
                        view.evaluateJavascript("(function(){var v=document.querySelector('video,audio');return v?(v.currentSrc||v.src):'';})()", value -> {
                            try {
                                Object source = new JSONTokener(value).nextValue();
                                if (source instanceof String) found((String) source, requestHeaders);
                            } catch (Exception error) { result.completeExceptionally(error); }
                        });
                    }
                    @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                        if (request.isForMainFrame()) result.completeExceptionally(new IOException("Web player failed: " + error.getErrorCode()));
                    }
                    @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                        if (request.isForMainFrame()) result.completeExceptionally(new IOException("Web player HTTP " + response.getStatusCode()));
                    }
                    @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                        result.completeExceptionally(new IOException("Web renderer stopped"));
                        return true;
                    }
                });
                view.loadUrl(address, requestHeaders);
            } catch (RuntimeException | LinkageError error) { result.completeExceptionally(error); }
        });
        try { return result.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        finally {
            result.cancel(false);
            main.post(() -> {
                WebView view = browser[0];
                if (view != null) { view.stopLoading(); view.setWebViewClient(new WebViewClient()); view.destroy(); }
            });
        }
    }

    static boolean direct(String address) {
        String path = Uri.parse(address).getPath();
        return http(address) && path != null && path.toLowerCase(java.util.Locale.ROOT)
                .matches(".*\\.(m3u8|mpd|mp4|m4v|webm|mkv|flv|mp3|m4a|aac|ogg|wav)$");
    }
    private static boolean http(String address) {
        Uri uri = Uri.parse(address);
        return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null;
    }
    private static boolean contains(Map<String, String> headers, String wanted) {
        for (String key : headers.keySet()) if (key.equalsIgnoreCase(wanted)) return true;
        return false;
    }
}
