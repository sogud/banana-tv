package com.github.catvod.crawler;

import android.app.Activity;
import android.content.Context;
import com.github.catvod.Runtime;
import com.github.catvod.net.Net;
import com.github.catvod.utils.Local;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import okhttp3.Dns;

/** Replacement ABI under development. The legacy JS-object field is still pending. */
public abstract class Spider {
    public String siteKey;
    public Net net;
    public Local local;
    protected SpiderApi api;
    private Supplier<Activity> activity;

    public static Dns safeDns() { return Dns.SYSTEM; }
    public Activity getActivity() { return activity == null ? Runtime.activity() : activity.get(); }
    public void initApi(SpiderApi value) { api = value; }

    public final void attachHost(Supplier<Activity> provider, SpiderApi callbacks) {
        activity = provider;
        initApi(callbacks);
    }

    public final void detachHost() {
        activity = () -> null;
        api = null;
        net = null;
        local = null;
    }

    public void init(Context context) throws Exception { }
    public void init(Context context, String extension) throws Exception { init(context); }
    public String homeContent(boolean filter) throws Exception { throw unsupported(); }
    public String homeVideoContent() throws Exception { return ""; }
    public String categoryContent(String id, String page, boolean filter, HashMap<String, String> options) throws Exception {
        throw unsupported();
    }
    public String detailContent(List<String> ids) throws Exception { throw unsupported(); }
    public String searchContent(String word, boolean quick) throws Exception { throw unsupported(); }
    public String searchContent(String word, boolean quick, String page) throws Exception { return searchContent(word, quick); }
    public String playerContent(String flag, String id, List<String> flags) throws Exception { throw unsupported(); }
    public Object[] proxy(Map<String, String> params) throws Exception { return null; }
    public void destroy() { }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Source operation is not implemented");
    }
}
