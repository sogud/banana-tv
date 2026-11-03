package com.sogud.bananatv.source.script;

import android.util.Base64;
import com.sogud.bananatv.source.SourceHttp;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One source session owns its cancellation; clients retain their shared connection pool. */
public final class ScriptHttp implements AutoCloseable {
    private final SourceHttp following;
    private final SourceHttp direct;
    private final SourceHttp.Scope scope = new SourceHttp.Scope();
    private final int maximumBytes;

    public ScriptHttp(OkHttpClient client, int maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("Positive response limit required");
        this.maximumBytes = maximumBytes;
        following = new SourceHttp(client.newBuilder().followRedirects(true).followSslRedirects(true).build());
        direct = new SourceHttp(client.newBuilder().followRedirects(false).followSslRedirects(false).build());
    }

    public JSONObject request(String url, JSONObject options) throws IOException, JSONException {
        String method = options.optString("method", "GET").toUpperCase(Locale.ROOT);
        int buffer = options.optInt("buffer", 0);
        if (buffer != 0 && buffer != 2) throw new IllegalArgumentException("Unsupported response buffer mode");
        long timeout = options.optLong("timeout", 10000);
        if (timeout <= 0 || timeout > 30000) throw new IllegalArgumentException("Invalid request timeout");
        Request.Builder request = new Request.Builder().url(url);
        JSONObject headers = options.optJSONObject("headers");
        if (headers != null) {
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                request.header(key, headers.getString(key));
            }
        }
        RequestBody body = null;
        boolean hasBody = options.has("data") || options.has("body");
        if (hasBody && (method.equals("GET") || method.equals("HEAD"))) {
            throw new IllegalArgumentException("Request method cannot have a body");
        }
        if (hasBody || method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            String payload = options.has("data") ? options.getString("data") : options.optString("body", "");
            String contentType = headers == null ? null : request.build().header("Content-Type");
            body = RequestBody.create(payload, MediaType.parse(contentType == null ? "text/plain; charset=utf-8" : contentType));
        }
        request.method(method, body);
        SourceHttp transport = options.optInt("redirect", 1) == 0 ? direct : following;
        SourceHttp.Reply reply = transport.exchange(request.build(), maximumBytes, timeout, scope);
        JSONObject responseHeaders = new JSONObject();
        for (String name : reply.headers.names()) {
            java.util.List<String> values = reply.headers.values(name);
            responseHeaders.put(name.toLowerCase(Locale.ROOT), values.size() == 1 ? values.get(0) : new JSONArray(values));
        }
        String content;
        if (buffer == 2) {
            content = Base64.encodeToString(reply.body, Base64.NO_WRAP);
        } else {
            Charset charset = StandardCharsets.UTF_8;
            String type = reply.headers.get("Content-Type");
            MediaType parsed = type == null ? null : MediaType.parse(type);
            if (parsed != null) charset = parsed.charset(StandardCharsets.UTF_8);
            content = new String(reply.body, charset);
        }
        return new JSONObject().put("code", reply.status).put("headers", responseHeaders).put("content", content);
    }

    @Override public void close() { scope.close(); }
}
