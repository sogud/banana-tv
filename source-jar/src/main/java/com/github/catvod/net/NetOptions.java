package com.github.catvod.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.Collections;
import java.util.Map;

/** JSON fields and defaults observed through the legacy public interface. */
public final class NetOptions {
    private static final Gson JSON = new Gson();
    String method = "get";
    String postType = "json";
    String body;
    JsonElement data;
    JsonElement params;
    Map<String, String> headers = Collections.emptyMap();
    String charset = "UTF-8";
    int buffer;
    int redirect = 1;
    int timeout = 10000;
    int callTimeout;
    int threads;
    int ping;
    int idle;
    long maxBytes = Long.MAX_VALUE;
    long size = -1;
    boolean cookie;
    boolean reconnect;

    public static NetOptions from(String text) {
        NetOptions result = JSON.fromJson(text, NetOptions.class);
        return result == null ? new NetOptions() : result;
    }

    public String getMethod() { return method; }
    public String getPostType() { return postType; }
    public String getBody() { return body; }
    public JsonElement getData() { return data; }
    public JsonElement getParams() { return params; }
    public Map<String, String> getHeader() { return headers == null ? Collections.emptyMap() : headers; }
    public String getCharset() { return charset; }
    public int getBuffer() { return buffer; }
    public int getRedirect() { return redirect; }
    public boolean isRedirect() { return redirect != 0; }
    public int getTimeout() { return timeout; }
    public int getCallTimeout() { return callTimeout; }
    public int getThreads() { return threads; }
    public int getPing() { return ping; }
    public int getIdle() { return idle; }
    public long getMaxBytes() { return maxBytes; }
    public long getSize() { return size; }
    public boolean isCookie() { return cookie; }
    public boolean isReconnect() { return reconnect; }
}
