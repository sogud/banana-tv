package fixture.source;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import com.github.catvod.net.NetSocket;

/** Original disposable adapter. Compiled to a separate DEX, never bundled in the host AAR. */
public final class CompatibilitySource extends Spider {
    private boolean initialized;
    private String extension;

    @Override public void init(Context context, String extension) throws Exception {
        com.github.catvod.crawler.SpiderDebug.log("private-fixture-secret");
        com.github.catvod.crawler.SpiderDebug.log(new IllegalArgumentException("private-fixture-secret"));
        com.github.catvod.crawler.SpiderDebug.log("fixture", "%s", "private-fixture-secret");
        initialized = context == com.github.catvod.Runtime.context();
        this.extension = extension;
        if (extension.equals("init-failure")) throw new IllegalArgumentException("private-fixture-secret");
        if (extension.equals("init-assertion")) throw new AssertionError("private-fixture-secret");
    }

    @Override public String homeContent(boolean filter) throws Exception {
        return new JSONObject().put("initialized", initialized).put("extension", extension)
                .put("activity", getActivity() == null ? 0 : System.identityHashCode(getActivity()))
                .put("source", siteKey).put("address", api.getAddress(true))
                .put("callback", api.webParse("fixture-url", "fixture-headers")).toString();
    }

    @Override public String categoryContent(String id, String page, boolean filter, HashMap<String, String> options) throws Exception {
        return new JSONObject().put("id", id).put("page", page).put("filter", filter)
                .put("sort", options.get("sort")).toString();
    }

    @Override public String detailContent(List<String> ids) { return ids.get(0); }
    @Override public String searchContent(String word, boolean quick) { return word; }
    @Override public String playerContent(String flag, String id, List<String> flags) { return flag + ":" + id; }
    @Override public Object[] proxy(Map<String, String> params) { return new Object[] {200, "text/plain", params.get("id")}; }
    @Override public void destroy() {
        api.log("fixture-destroy");
        if (extension.equals("close-failure")) throw new IllegalStateException("private-fixture-secret");
        if (extension.equals("close-assertion")) throw new AssertionError("private-fixture-secret");
    }

    public static final class Fallback extends Spider {
        private boolean initialized;
        @Override public void init(Context context) { initialized = true; }
        @Override public String searchContent(String word, boolean quick) { return initialized + ":" + word; }
    }

    public static final class Network extends Spider {
        private String base;
        @Override public void init(Context context, String extension) { base = extension; }
        @Override public String homeContent(boolean filter) { return net.get(base + "home"); }
        @Override public String categoryContent(String id, String page, boolean filter, HashMap<String, String> options) {
            return net.get(base + id, Integer.parseInt(options.get("timeout")));
        }
        @Override public String searchContent(String value, boolean quick) {
            local.set("fixture", value); return local.get("fixture");
        }
        @Override public String detailContent(List<String> keys) { return local.get(keys.get(0)); }
        @Override public String playerContent(String flag, String id, List<String> flags) {
            if (flag.equals("post")) return net.post(base + "post", id);
            return net.req(base + "request", id);
        }
    }

    public static final class Socket extends Spider implements NetSocket.Listener {
        private final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        private boolean binary;
        private byte[] bytes;
        private String error;
        @Override public void init(Context context, String extension) throws Exception { net.connect(extension, "{}", this); }
        @Override public void accept(NetSocket socket, NetSocket.Event event) {
            if (event.type.equals("open")) socket.send(new byte[] {4, 5, -3});
            else if (event.type.equals("message")) {
                binary = event.binary; bytes = (byte[]) event.content; error = event.error; ready.countDown();
            } else if (event.type.equals("error")) { error = event.error; ready.countDown(); }
        }
        @Override public String homeContent(boolean filter) throws Exception {
            if (!ready.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException();
            return new JSONObject().put("binary", binary).put("bytes", new org.json.JSONArray(bytes))
                    .put("error", error == null ? JSONObject.NULL : error).toString();
        }
    }
}
