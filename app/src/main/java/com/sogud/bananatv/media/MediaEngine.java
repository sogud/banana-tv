package com.sogud.bananatv.media;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.fongmi.quickjs.host.Host;
import com.github.catvod.Proxy;
import com.github.catvod.Runtime;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.Net;
import com.github.catvod.runtime.PythonRuntime;
import com.github.catvod.runtime.QuickJsRuntime;
import com.github.catvod.utils.Local;
import com.github.catvod.utils.RuntimeModule;
import com.sogud.bananatv.R;
import dalvik.system.DexClassLoader;
import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import static com.sogud.bananatv.media.MediaModels.*;

/** In-process CatVod host. No FongMi app, LAN remote or system service is required. */
public final class MediaEngine {
    private static final String BUILTIN_ORIGIN = "asset://media-sources.json";
    private static final long SOURCE_UPDATE_INTERVAL = TimeUnit.HOURS.toMillis(24);
    private static final long SOURCE_RETRY_INTERVAL = TimeUnit.HOURS.toMillis(1);
    private static MediaEngine instance;
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private final Context context;
    private final SharedPreferences preferences;
    private final File directory;
    final MediaCache catalogCache;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build();
    private final List<Site> sites = new ArrayList<>();
    private final Map<String, Spider> spiders = new ConcurrentHashMap<>();
    private final Map<String, DexClassLoader> jars = new HashMap<>();
    private final java.util.Set<String> initializedJars = new java.util.HashSet<>();
    private final Map<String, java.lang.reflect.Method> jarProxies = new ConcurrentHashMap<>();
    private final Map<String, String> siteJars = new ConcurrentHashMap<>();
    private QuickJsRuntime.Session javascript;
    private DexClassLoader hostBridge;
    private NanoHTTPD proxy;
    private String configId = "", configRevision = "", recent = "";
    private List<String> flags = new ArrayList<>();
    private JSONArray parsers = new JSONArray();
    private static final Host HOST = new Host() {
        @Override public long getCallTimeout() { return 15000; }
    };

    public static synchronized MediaEngine get(Context context) {
        if (instance == null) instance = new MediaEngine(context.getApplicationContext());
        return instance;
    }
    public static void foreground(Activity current) {
        activity = new WeakReference<>(current);
    }
    private MediaEngine(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("media", Context.MODE_PRIVATE);
        directory = new File(context.getFilesDir(), "media");
        directory.mkdirs();
        catalogCache = new MediaCache(new File(directory, "catalog-cache"), 16 * 1024 * 1024);
        Runtime.set(context);
        Runtime.setActivity(() -> activity.get());
        Runtime.setToast(text -> { /* Sources must not display unsolicited messages over Home. */ });
    }
    public static final class Failure extends IOException {
        public final int message;
        Failure(int message) { this.message = message; }
    }
    /** A page/search owns its HTTP calls; leaving it cancels sockets as well as queued work. */
    public static final class Cancellation {
        private final List<okhttp3.Call> calls = new ArrayList<>();
        private boolean cancelled;
        synchronized void check() throws java.io.InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        }
        synchronized void add(okhttp3.Call call) throws IOException { check(); calls.add(call); }
        synchronized void remove(okhttp3.Call call) { calls.remove(call); }
        public synchronized void cancel() {
            cancelled = true;
            for (okhttp3.Call call : calls) call.cancel();
            calls.clear();
        }
    }
    public String error(Throwable error) {
        if (error instanceof Failure) return context.getString(((Failure) error).message);
        RuntimeModule.MissingException missing = RuntimeModule.MissingException.find(error);
        if (missing != null) return context.getString(R.string.media_runtime_missing);
        return context.getString(R.string.media_request_failed);
    }
    public boolean hasCustomSource() { return new File(directory, "config.json").isFile(); }
    public boolean usingBuiltIn() { return preferences.getBoolean("built_in", !hasCustomSource()); }
    String catalogKey(Site site, String mode, String query) {
        return configId + ":" + configRevision + ":" + site.key + ":" + mode + ":" + query;
    }
    public boolean autoRefresh() { return preferences.getBoolean("auto_refresh", true); }
    public void autoRefresh(boolean enabled) { preferences.edit().putBoolean("auto_refresh", enabled).apply(); }
    public String selected() { return preferences.getString("site", ""); }
    public void select(String key) { preferences.edit().putString("site", key).apply(); }
    public List<Site> sites() { return new ArrayList<>(sites); }
    public boolean enabled(Site site) {
        return !preferences.getStringSet("disabled:" + configId, Collections.emptySet()).contains(site.key);
    }
    public void enableSites(java.util.Set<String> enabled) {
        java.util.Set<String> disabled = new java.util.HashSet<>();
        for (Site site : sites) if (!enabled.contains(site.key)) disabled.add(site.key);
        preferences.edit().putStringSet("disabled:" + configId, disabled).apply();
    }
    public List<Site> enabledSites() {
        List<Site> result = new ArrayList<>();
        for (Site site : sites) if (enabled(site)) result.add(site);
        return result;
    }
    public Site site(String key) throws Failure {
        for (Site site : sites) if (site.key.equals(key)) return site;
        throw new Failure(R.string.media_source_missing);
    }
    public void load() throws Exception {
        if (!sites.isEmpty()) return;
        JSONObject envelope = configuration(usingBuiltIn());
        apply(envelope.getJSONObject("config"), envelope.getString("origin"));
    }
    private JSONObject configuration(boolean builtIn) throws Exception {
        if (builtIn) {
            try (InputStream input = context.getAssets().open("media-sources.json")) {
                JSONObject json = new JSONObject(new String(read(input, 2 * 1024 * 1024), StandardCharsets.UTF_8));
                return new JSONObject().put("origin", BUILTIN_ORIGIN).put("config", json);
            }
        }
        return new JSONObject(new String(Files.readAllBytes(new File(directory, "config.json").toPath()), StandardCharsets.UTF_8));
    }
    public void useBuiltIn(boolean enabled) throws Exception {
        JSONObject envelope = configuration(enabled);
        activate(envelope.getJSONObject("config"), envelope.getString("origin"), enabled);
    }
    public void refreshSource() throws Exception {
        refreshSource(new Cancellation());
    }
    public void refreshSource(Cancellation cancellation) throws Exception {
        JSONObject saved = configuration(false);
        importSource(saved.getString("origin"), cancellation);
    }
    public boolean refreshSourceIfDue(Cancellation cancellation) throws Exception {
        return refreshSourceIfDue(cancellation, System.currentTimeMillis());
    }
    boolean refreshSourceIfDue(Cancellation cancellation, long now) throws Exception {
        return refreshSourceIfDue(cancellation, now, false);
    }
    public void prefetchSourceUpdate(Cancellation cancellation) throws Exception {
        refreshSourceIfDue(cancellation, System.currentTimeMillis(), true);
    }
    public void applyPendingSourceUpdate() throws Exception {
        File pending = new File(directory, "pending-config.json");
        if (!pending.isFile()) return;
        try {
            if (usingBuiltIn()) return;
            JSONObject saved = configuration(false);
            JSONObject update = new JSONObject(new String(Files.readAllBytes(pending.toPath()), StandardCharsets.UTF_8));
            String previous = digest("SHA-256", saved.toString().getBytes(StandardCharsets.UTF_8));
            if (!previous.equals(update.optString("previous"))) return;
            saveSource(update.getJSONObject("config"), update.getString("origin"));
        } catch (Exception ignored) {
            // A partial download must not prevent the valid local library from opening.
        } finally { pending.delete(); }
    }
    private boolean refreshSourceIfDue(Cancellation cancellation, long now, boolean stage) throws Exception {
        if (usingBuiltIn() || !autoRefresh()) return false;
        JSONObject saved = configuration(false);
        String origin = saved.getString("origin");
        String identity = digest("SHA-256", origin.getBytes(StandardCharsets.UTF_8));
        long checked = preferences.getLong("source_checked:" + identity, 0);
        long updated = preferences.getLong("source_updated:" + identity, 0);
        if ((checked > 0 && now >= checked && now - checked < SOURCE_RETRY_INTERVAL)
                || (updated > 0 && now >= updated && now - updated < SOURCE_UPDATE_INTERVAL)) return false;
        cancellation.check();
        preferences.edit().putLong("source_checked:" + identity, now).apply();
        try {
            JSONObject json = fetchSource(origin, cancellation, 3000);
            cancellation.check();
            if (stage) {
                // A background download cannot replace the sources used by a live player or search.
                String previous = digest("SHA-256", saved.toString().getBytes(StandardCharsets.UTF_8));
                JSONObject pending = new JSONObject().put("previous", previous).put("origin", origin).put("config", json);
                write(new File(directory, "pending-config.json"), pending.toString().getBytes(StandardCharsets.UTF_8));
            } else saveSource(json, origin);
            preferences.edit().putLong("source_updated:" + identity, now).apply();
            return true;
        } catch (Exception error) {
            cancellation.check();
            return false; // The last valid local configuration remains available offline.
        }
    }
    private void activate(JSONObject json, String origin, boolean builtIn) throws Exception {
        validate(json);
        clear();
        apply(json, origin);
        boolean selectedExists = false;
        for (Site site : sites) if (site.key.equals(selected())) selectedExists = true;
        SharedPreferences.Editor editor = preferences.edit().putBoolean("built_in", builtIn);
        if (!selectedExists) editor.putString("site", sites.get(0).key);
        editor.apply();
    }
    public void importSource(String url) throws Exception {
        importSource(url, new Cancellation());
    }
    public void importSource(String url, Cancellation cancellation) throws Exception {
        String origin = url.trim();
        JSONObject json = fetchSource(origin, cancellation, 25000);
        cancellation.check();
        saveSource(json, origin);
        String identity = digest("SHA-256", origin.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        preferences.edit().putLong("source_checked:" + identity, now).putLong("source_updated:" + identity, now).apply();
    }
    private JSONObject fetchSource(String origin, Cancellation cancellation, long timeout) throws Exception {
        Uri parsed = Uri.parse(origin);
        if (!("https".equals(parsed.getScheme()) || "http".equals(parsed.getScheme())) || parsed.getHost() == null)
            throw new Failure(R.string.media_invalid_address);
        JSONObject json = new JSONObject(new String(download(new Request.Builder().url(origin).build(),
                2 * 1024 * 1024, timeout, cancellation), StandardCharsets.UTF_8));
        absolutize(json, new URI(origin));
        validate(json);
        return json;
    }
    private void saveSource(JSONObject json, String origin) throws Exception {
        JSONObject envelope = new JSONObject().put("origin", origin).put("config", json);
        write(new File(directory, "config.json"), envelope.toString().getBytes(StandardCharsets.UTF_8));
        activate(json, origin, false);
    }
    private void validate(JSONObject json) throws Exception {
        validateJarHost(json.optString("jarHost"));
        JSONArray list = json.optJSONArray("sites");
        if (list == null || list.length() == 0 || list.length() > 500) throw new Failure(R.string.media_invalid_config);
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject site = list.getJSONObject(i);
            validateJarHost(site.optString("jarHost"));
            String key = site.optString("key");
            if (key.isEmpty() || !keys.add(key) || site.optString("api").isEmpty()) throw new Failure(R.string.media_invalid_config);
            JSONArray mirrors = site.optJSONArray("mirrors");
            if (mirrors != null) {
                int type = site.optInt("type", 1);
                if ((type != 0 && type != 1 && type != 4) || mirrors.length() > 2) throw new Failure(R.string.media_invalid_config);
                for (int j = 0; j < mirrors.length(); j++) {
                    Uri endpoint = Uri.parse(mirrors.getString(j));
                    if (!("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())) || endpoint.getHost() == null)
                        throw new Failure(R.string.media_invalid_config);
                }
            }
        }
    }
    private void validateJarHost(String profile) throws Failure {
        if (!profile.isEmpty() && !profile.equals("native") && !profile.equals("catvod-v1")) throw new Failure(R.string.media_invalid_config);
    }
    private void apply(JSONObject json, String origin) throws Exception {
        validate(json);
        configId = digest("SHA-256", origin.getBytes(StandardCharsets.UTF_8));
        configRevision = digest("SHA-256", json.toString().getBytes(StandardCharsets.UTF_8));
        JSONArray list = json.getJSONArray("sites");
        for (int i = 0; i < list.length(); i++) sites.add(new Site(list.getJSONObject(i), json.optString("spider"), json.optString("jarHost")));
        JSONArray values = json.optJSONArray("flags");
        if (values != null) for (int i = 0; i < values.length(); i++) flags.add(values.optString(i));
        parsers = json.optJSONArray("parses") == null ? new JSONArray() : json.getJSONArray("parses");
        if (selected().isEmpty()) select(sites.get(0).key);
    }
    private static void absolutize(Object object, URI base) throws Exception {
        if (object instanceof JSONObject) {
            JSONObject json = (JSONObject) object;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next(); Object value = json.get(key);
                if (value instanceof String && (((String) value).startsWith("./") || ((String) value).startsWith("../")))
                    json.put(key, base.resolve((String) value).toString());
                else absolutize(value, base);
            }
        } else if (object instanceof JSONArray) {
            JSONArray list = (JSONArray) object;
            for (int i = 0; i < list.length(); i++) {
                Object value = list.get(i);
                if (value instanceof String && (((String) value).startsWith("./") || ((String) value).startsWith("../")))
                    list.put(i, base.resolve((String) value).toString());
                else absolutize(value, base);
            }
        }
    }
    private void clear() {
        for (Spider spider : spiders.values()) {
            try { spider.destroy(); } catch (RuntimeException ignored) { }
            spider.js.close(); if (spider.net != null) spider.net.close();
        }
        spiders.clear(); jars.clear(); initializedJars.clear(); jarProxies.clear(); siteJars.clear();
        sites.clear(); flags.clear();
        if (javascript != null) javascript.clear();
        javascript = null;
    }
    private void startProxy() throws IOException {
        if (proxy != null) return;
        // Legacy JARs discover their host in this range using /proxy?do=ck.
        for (int port = 9978; port < 10000; port++) {
            NanoHTTPD candidate = createProxy(port);
            try {
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
                proxy = candidate; Proxy.set(port); return;
            } catch (IOException occupied) { candidate.stop(); }
        }
        throw new IOException("No source proxy port is available");
    }
    private NanoHTTPD createProxy(int port) {
        return new NanoHTTPD("127.0.0.1", port) {
            @Override public Response serve(IHTTPSession session) {
                if (!session.getUri().equals("/proxy")) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "");
                Map<String, String> params = new HashMap<>(session.getHeaders());
                params.putAll(session.getParms());
                if ("ck".equals(params.get("do"))) return newFixedLengthResponse("ok");
                String key = params.getOrDefault("siteKey", recent);
                Spider owner = spiders.get(key);
                try {
                    Object[] result = owner == null ? null : owner.proxy(params);
                    java.lang.reflect.Method legacy = jarProxies.get(siteJars.getOrDefault(key, ""));
                    if (result == null && legacy != null) result = (Object[]) legacy.invoke(null, params);
                    return MediaProxy.response(result);
                } catch (Exception | LinkageError error) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Source failed"); }
            }
        };
    }
    private static String jarKey(Site site) { return site.jar + "@@" + site.jarHost; }
    private DexClassLoader jar(Site site, boolean initialize) throws Exception {
        String address = site.jar, key = jarKey(site);
        if (address.isEmpty()) return null;
        DexClassLoader loader = jars.get(key);
        if (loader == null) loader = loadJar(address, site.jarHost, key);
        if (initialize && !initializedJars.contains(key)) {
            try { loader.loadClass("com.github.catvod.spider.Init").getMethod("init", Context.class).invoke(null, context); }
            catch (ClassNotFoundException | NoSuchMethodException ignored) { }
            initializedJars.add(key);
        }
        return loader;
    }
    private DexClassLoader loadJar(String address, String profile, String key) throws Exception {
        String[] parts = address.split(";md5;", 2);
        File file = new File(directory, digest("SHA-256", address.getBytes(StandardCharsets.UTF_8)) + ".jar");
        if (!file.isFile()) {
            byte[] bytes = download(parts[0], 32 * 1024 * 1024);
            if (parts.length == 2 && !digest("MD5", bytes).equalsIgnoreCase(parts[1].trim())) throw new Failure(R.string.media_integrity_failed);
            write(file, bytes);
        }
        if (!file.setReadOnly()) throw new IOException("Cannot protect source JAR");
        if (parts.length == 2 && !digest("MD5", Files.readAllBytes(file.toPath())).equalsIgnoreCase(parts[1].trim()))
            throw new Failure(R.string.media_integrity_failed);
        ClassLoader parent = RuntimeModule.getSpiderClassLoader();
        // This snapshot mixes source code with application hooks. Supply only the host lifecycle ABI.
        // The original JAR remains intact and hash-checked; other JARs retain their own Init implementation.
        String fingerprint = digest("SHA-256", Files.readAllBytes(file.toPath()));
        boolean known = fingerprint.equals("102fc7657c2cc78dc4ceaf02d442359455bf530d2bc07d10009149dc90853687")
                || fingerprint.equals("5fd276c06ca4152f3136374b82fc0a2161d7659981841846d2fbb5326d3fef47");
        if (profile.equals("catvod-v1") || (profile.isEmpty() && known)) {
            if (hostBridge == null) {
                File bridge = new File(directory, "catvod-host.jar");
                try (InputStream input = context.getAssets().open("catvod-host.jar")) { write(bridge, read(input, 256 * 1024)); }
                if (!bridge.setReadOnly()) throw new IOException("Cannot protect host bridge");
                hostBridge = new DexClassLoader(bridge.getPath(), context.getCodeCacheDir().getPath(), null, parent);
                hostBridge.loadClass("com.github.catvod.spider.Init").getMethod("configure", Context.class, java.util.function.Supplier.class)
                        .invoke(null, context, (java.util.function.Supplier<Activity>) () -> activity.get());
            }
            parent = hostBridge;
        }
        DexClassLoader loader = new DexClassLoader(file.getPath(), context.getCodeCacheDir().getPath(), null, parent);
        try {
            Class<?> legacy = loader.loadClass("com.github.catvod.spider.Proxy");
            if (known) {
                // Both reviewed DEX files declare this exact static port field. Avoid discovering another app's server.
                java.lang.reflect.Field port = legacy.getDeclaredField("a");
                if (port.getType() != int.class || !java.lang.reflect.Modifier.isStatic(port.getModifiers()))
                    throw new IOException("Source proxy ABI changed");
                port.setAccessible(true); port.setInt(null, proxy.getListeningPort());
            } else {
                try { legacy.getMethod("setPort", int.class).invoke(null, proxy.getListeningPort()); }
                catch (NoSuchMethodException ignored) { }
            }
            java.lang.reflect.Method method = legacy.getDeclaredMethod("proxy", Map.class);
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) jarProxies.put(key, method);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) { }
        jars.put(key, loader);
        return loader;
    }
    private Spider spider(Site site) throws Exception {
        recent = site.key;
        if (spiders.containsKey(site.key)) return spiders.get(site.key);
        startProxy();
        siteJars.put(site.key, jarKey(site));
        Spider spider;
        if (site.api.startsWith("csp_")) {
            DexClassLoader loader = jar(site, true);
            if (loader == null) throw new Failure(R.string.media_invalid_config);
            spider = (Spider) loader.loadClass("com.github.catvod.spider." + site.api.substring(4)).getDeclaredConstructor().newInstance();
            spider.net = new Net(HOST, site.key); spider.local = new Local(site.key);
        } else if (site.api.contains(".js")) {
            if (javascript == null) javascript = RuntimeModule.QUICKJS.entry(QuickJsRuntime.class).session();
            spider = javascript.spider(site.api, jar(site, false), HOST);
        } else if (site.api.contains(".py")) {
            PythonRuntime python = RuntimeModule.PYTHON.entry(PythonRuntime.class);
            python.initialize(); spider = python.spider(site.api, site.key, HOST, HOST::getUserAgent);
        } else throw new Failure(R.string.media_unsupported_source);
        spider.siteKey = site.key;
        try { spider.init(context, localExtension(site.ext)); }
        catch (Exception error) { spider.destroy(); if (spider.net != null) spider.net.close(); throw error; }
        spiders.put(site.key, spider);
        return spider;
    }
    private String localExtension(String text) throws Exception {
        Object value = text;
        try {
            if (text.trim().startsWith("{")) value = new JSONObject(text);
            else if (text.trim().startsWith("[")) value = new JSONArray(text);
        } catch (org.json.JSONException ignored) { /* Some adapters accept a non-JSON rule string. */ }
        return localReferences(value).toString();
    }
    private Object localReferences(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next(); object.put(key, localReferences(object.get(key)));
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) array.put(i, localReferences(array.get(i)));
        } else if (value instanceof String) {
            Uri uri = Uri.parse((String) value);
            boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
            String path = uri.getPath();
            boolean legacyPath = path != null && (path.startsWith("/file/") || path.equals("/proxy"));
            if ("http".equals(uri.getScheme()) && loopback && legacyPath && uri.getPort() >= 9978 && uri.getPort() < 10000)
                return uri.buildUpon().encodedAuthority("127.0.0.1:" + proxy.getListeningPort()).build().toString();
        }
        return value;
    }
    private String call(Site site, Map<String, String> params) throws Exception {
        return call(site, params, new Cancellation());
    }
    private String call(Site site, Map<String, String> params, Cancellation cancellation) throws Exception {
        if (site.type != 0 && site.type != 1 && site.type != 4) throw new Failure(R.string.media_unsupported_source);
        List<String> endpoints = new ArrayList<>();
        endpoints.add(site.activeApi);
        if (!endpoints.contains(site.api)) endpoints.add(site.api);
        for (String mirror : site.mirrors) if (!endpoints.contains(mirror)) endpoints.add(mirror);
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        Exception failure = new IOException("Source is unavailable");
        for (String endpoint : endpoints) {
            cancellation.check();
            long remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) break;
            Uri original = Uri.parse(endpoint);
            Uri.Builder url = original.buildUpon().clearQuery();
            for (String key : original.getQueryParameterNames()) {
                if (!params.containsKey(key)) for (String value : original.getQueryParameters(key)) url.appendQueryParameter(key, value);
            }
            for (Map.Entry<String, String> entry : params.entrySet()) url.appendQueryParameter(entry.getKey(), entry.getValue());
            if (!site.ext.isEmpty()) url.appendQueryParameter("extend", site.ext);
            Request.Builder request = new Request.Builder().url(url.build().toString());
            addHeaders(request, site.headers);
            try {
                String text = new String(download(request.build(), 8 * 1024 * 1024, Math.min(7000, remaining), cancellation), StandardCharsets.UTF_8);
                JSONObject response = site.type == 0 ? MediaXml.parse(text) : object(text);
                boolean catalogue = response.optJSONArray("list") != null || response.optJSONArray("class") != null;
                if (!catalogue && !(params.containsKey("play") && response.has("url"))) throw new IOException("Invalid source response");
                site.activeApi = endpoint;
                return response.toString();
            } catch (IOException | org.json.JSONException | org.xmlpull.v1.XmlPullParserException error) { failure = error; }
        }
        throw failure;
    }
    public Page home(Site site) throws Exception {
        return home(site, new Cancellation());
    }
    public Page home(Site site, Cancellation cancellation) throws Exception {
        if (site.type == 0 || site.type == 1) {
            // MacCMS separates category metadata from the poster-bearing detail list.
            JSONObject home = object(call(site, Collections.emptyMap(), cancellation));
            JSONObject video = object(call(site, Map.of("ac", detailAction(site)), cancellation));
            if (video.optJSONArray("list") != null) home.put("list", video.getJSONArray("list"));
            return new Page(home);
        }
        if (site.type != 3) return page(call(site, Collections.emptyMap(), cancellation));
        Spider spider = spider(site);
        JSONObject home = object(spider.homeContent(true));
        JSONObject video = object(spider.homeVideoContent());
        if (video.optJSONArray("list") != null && video.optJSONArray("list").length() > 0) home.put("list", video.getJSONArray("list"));
        return new Page(home);
    }
    public Page category(Site site, String id, int page) throws Exception {
        return category(site, id, page, new Cancellation());
    }
    public List<Category> categories(Site site, Cancellation cancellation) throws Exception {
        cancellation.check();
        return page(site.type == 3 ? spider(site).homeContent(true) : call(site, Collections.emptyMap(), cancellation)).categories;
    }
    public Page latest(Site site, int number, Cancellation cancellation) throws Exception {
        return page(call(site, Map.of("ac", detailAction(site), "pg", String.valueOf(number)), cancellation));
    }
    public Page category(Site site, String id, int page, Cancellation cancellation) throws Exception {
        return page(site.type == 3 ? spider(site).categoryContent(id, String.valueOf(page), true, new HashMap<>())
                : call(site, Map.of("ac", detailAction(site), "t", id, "pg", String.valueOf(page)), cancellation));
    }
    public Page search(Site site, String word, int page) throws Exception {
        return search(site, word, page, new Cancellation());
    }
    public Page search(Site site, String word, int page, Cancellation cancellation) throws Exception {
        cancellation.check();
        return page(site.type == 3 ? (page == 1 ? spider(site).searchContent(word, false)
                : spider(site).searchContent(word, false, String.valueOf(page)))
                : call(site, Map.of("wd", word, "ac", detailAction(site), "pg", String.valueOf(page)), cancellation));
    }
    public Movie detail(Site site, String id) throws Exception {
        Page result = page(site.type == 3 ? spider(site).detailContent(Collections.singletonList(id)) : call(site, Map.of("ac", detailAction(site), "ids", id)));
        if (result.movies.isEmpty()) throw new Failure(R.string.media_empty_detail);
        return result.movies.get(0);
    }
    public JSONObject resolve(Site site, Movie movie, int lineIndex, int episodeIndex) throws Exception {
        Line line = movie.lines.get(lineIndex); Episode episode = line.episodes.get(episodeIndex);
        JSONObject result = site.type == 3 ? object(spider(site).playerContent(line.name, episode.id, flags))
                : site.type == 4 ? object(call(site, Map.of("play", episode.id, "flag", line.name)))
                : new JSONObject().put("url", episode.id).put("parse", 0);
        String url = streamUrl(result.opt("url"));
        if (url.startsWith("video://")) { url = url.substring(8); result.put("parse", 1); }
        if (!(url.startsWith("https://") || url.startsWith("http://"))) throw new Failure(R.string.media_unplayable);
        JSONObject headers = result.optJSONObject("header");
        if (headers == null && result.opt("header") instanceof String) headers = object(result.getString("header"));
        if (headers == null) headers = site.headers == null ? new JSONObject() : site.headers;
        String prefix = result.optString("playUrl");
        boolean needsParser = result.optInt("parse", 0) != 0 || result.optInt("jx", 0) == 1 || flags.contains(line.name);
        if (!prefix.isEmpty() || (needsParser && !MediaWebResolver.direct(url))) {
            JSONObject parsed = null;
            if (prefix.isEmpty() && (result.optInt("jx", 0) == 1 || flags.contains(line.name))) parsed = configuredParser(url);
            result = parsed == null ? MediaWebResolver.resolve(context, prefix + url, headers) : parsed;
        } else result.put("url", url).put("parse", 0).put("header", headers);
        inferFormat(result);
        return result;
    }
    private JSONObject configuredParser(String target) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 14000;
        // JSON parsers avoid starting a renderer. Then try two explicitly configured web parsers.
        for (int kind : new int[]{1, 0}) {
            int attempts = 0;
            for (int i = 0; i < parsers.length() && attempts < (kind == 1 ? 3 : 2); i++) {
                JSONObject parser = parsers.optJSONObject(i);
                if (parser == null || parser.optInt("type", -1) != kind) continue;
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) return null;
                String address = parser.optString("url");
                if (!(address.startsWith("https://") || address.startsWith("http://"))) continue;
                attempts++;
                JSONObject parserHeaders = parser.optJSONObject("header");
                JSONObject ext = parser.optJSONObject("ext");
                if (ext != null && ext.optJSONObject("header") != null) parserHeaders = ext.optJSONObject("header");
                try {
                    if (kind == 0) return MediaWebResolver.resolve(context, address + target, parserHeaders, Math.min(4000, remaining));
                    Request.Builder request = new Request.Builder().url(address + target);
                    addHeaders(request, parserHeaders);
                    JSONObject value = object(new String(download(request.build(), 1024 * 1024, Math.min(2000, remaining)), StandardCharsets.UTF_8));
                    if (!value.has("url") && value.optJSONObject("data") != null) value = value.getJSONObject("data");
                    String stream = streamUrl(value.opt("url"));
                    if (!(stream.startsWith("https://") || stream.startsWith("http://"))) continue;
                    JSONObject headers = value.optJSONObject("header");
                    if (headers == null && value.opt("header") instanceof String) headers = object(value.getString("header"));
                    if (headers == null) headers = new JSONObject();
                    if (value.optInt("parse", 0) != 0 && !MediaWebResolver.direct(stream)) continue;
                    return value.put("url", stream).put("parse", 0).put("header", headers);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw error;
                } catch (Exception error) {
                    if (Thread.currentThread().isInterrupted()) throw error;
                    // A failed configured parser does not prevent the next one or the original page.
                }
            }
        }
        return null;
    }
    private static String streamUrl(Object value) {
        if (value instanceof JSONArray) return ((JSONArray) value).optString(1);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray values = object.optJSONArray("values");
            JSONObject selected = values == null ? null : values.optJSONObject(object.optInt("position", 0));
            return selected == null ? "" : selected.optString("v");
        }
        return value instanceof String ? (String) value : "";
    }
    private void inferFormat(JSONObject result) throws Exception {
        if (!result.optString("format").isEmpty() || MediaWebResolver.direct(result.optString("url"))) return;
        Request.Builder request = new Request.Builder().url(result.getString("url"));
        addHeaders(request, result.optJSONObject("header")); request.header("Range", "bytes=0-4095");
        okhttp3.Call call = http.newCall(request.build()); call.timeout().timeout(4, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) return;
            byte[] bytes = new byte[4096];
            int count = response.body().byteStream().read(bytes);
            if (count <= 0) return;
            String prefix = new String(bytes, 0, count, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
            if (prefix.startsWith("#EXTM3U")) result.put("format", "hls");
            else if (prefix.startsWith("<MPD") || (prefix.startsWith("<?xml") && prefix.contains("<MPD"))) result.put("format", "dash");
        } catch (IOException ignored) { /* Let the player's request report the actual network failure. */ }
    }
    public JSONArray history() {
        JSONArray all;
        try { all = new JSONArray(preferences.getString("history", "[]")); } catch (Exception error) { all = new JSONArray(); }
        JSONArray selected = new JSONArray();
        for (int i = 0; i < all.length(); i++) if (all.optJSONObject(i) != null && configId.equals(all.optJSONObject(i).optString("config"))) selected.put(all.optJSONObject(i));
        return selected;
    }
    public void remember(Site site, Movie movie, int line, int episode, long position, long duration) {
        if (position < 1000) return;
        try {
            JSONArray old;
            try { old = new JSONArray(preferences.getString("history", "[]")); }
            catch (org.json.JSONException damaged) { old = new JSONArray(); }
            JSONArray next = new JSONArray();
            next.put(new JSONObject().put("config", configId).put("site", site.key).put("id", movie.id)
                    .put("title", movie.title).put("poster", movie.poster).put("line", line).put("episode", episode)
                    .put("position", position).put("duration", duration));
            for (int i = 0; i < old.length() && next.length() < 40; i++) {
                JSONObject item = old.optJSONObject(i); if (item == null) continue;
                boolean same = configId.equals(item.optString("config")) && site.key.equals(item.optString("site")) && movie.id.equals(item.optString("id"));
                if (!same) next.put(item);
            }
            preferences.edit().putString("history", next.toString()).apply();
        } catch (Exception ignored) { /* A damaged history does not prevent playback. */ }
    }
    private static JSONObject object(String text) throws Exception {
        // Python's optional homeVideoContent can return None, serialized as JSON null.
        return text == null || text.isBlank() || text.trim().equals("null") ? new JSONObject() : new JSONObject(text);
    }
    private static String detailAction(Site site) { return site.type == 0 ? "videolist" : "detail"; }
    private static Page page(String text) throws Exception { return new Page(object(text)); }
    private byte[] download(String url, int maximum) throws Exception { return download(new Request.Builder().url(url).build(), maximum); }
    private byte[] download(Request request, int maximum) throws Exception {
        return download(request, maximum, 25000);
    }
    private byte[] download(Request request, int maximum, long timeout) throws Exception {
        return download(request, maximum, timeout, new Cancellation());
    }
    private byte[] download(Request request, int maximum, long timeout, Cancellation cancellation) throws Exception {
        okhttp3.Call call = http.newCall(request);
        call.timeout().timeout(timeout, TimeUnit.MILLISECONDS);
        cancellation.add(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Source HTTP failure");
            try (InputStream input = response.body().byteStream()) { return read(input, maximum); }
        } finally { cancellation.remove(call); }
    }
    private static byte[] read(InputStream input, int maximum) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > maximum) throw new IOException("Source response is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    private static void addHeaders(Request.Builder request, JSONObject headers) {
        if (headers == null) return;
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) { String key = keys.next(); request.header(key, headers.optString(key)); }
    }
    private static void write(File destination, byte[] bytes) throws Exception {
        File pending = new File(destination.getParentFile(), destination.getName() + ".pending");
        try (FileOutputStream output = new FileOutputStream(pending)) { output.write(bytes); output.getFD().sync(); }
        Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static String digest(String algorithm, byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance(algorithm).digest(bytes)) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}
