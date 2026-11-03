package com.sogud.bananatv.source.script;

import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.QuickJSContext;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.JSCallFunction;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Thread-owned ES-module session, owned by the source worker. */
public final class ScriptSession implements WorkerSession {
    public enum Operation {
        INIT("init"), HOME("home"), HOME_VIDEO("homeVod"), CATEGORY("category"),
        SEARCH("search"), DETAIL("detail"), PLAY("play"), PROXY("proxy");

        private final String export;
        Operation(String export) { this.export = export; }
    }

    private static final java.util.regex.Pattern URI_SCHEME = java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private final Thread owner = Thread.currentThread();
    private final QuickJSContext context;
    private final int maximumResultBytes;
    private final ScriptHttp http;
    private boolean closed;

    /** Modules are already fetched and keyed by canonical relative names, e.g. source.js. */
    public ScriptSession(Map<String, String> modules, String entry, int memoryBytes, int maximumResultBytes) {
        this(modules, entry, memoryBytes, maximumResultBytes, null);
    }

    public ScriptSession(Map<String, String> modules, String entry, int memoryBytes,
                         int maximumResultBytes, ScriptStorage storage) {
        this(modules, entry, memoryBytes, maximumResultBytes, storage, null);
    }

    public ScriptSession(Map<String, String> modules, String entry, int memoryBytes,
                         int maximumResultBytes, ScriptStorage storage, ScriptHttp http) {
        if (memoryBytes <= 0 || maximumResultBytes <= 0) {
            throw new IllegalArgumentException("Positive script limits required");
        }
        Map<String, String> snapshot = new HashMap<>(modules);
        if (!snapshot.containsKey(entry)) throw new IllegalArgumentException("Missing entry module");
        this.maximumResultBytes = maximumResultBytes;
        this.http = http;
        QuickJSLoader.init();
        context = QuickJSContext.create();
        try {
            context.setMemoryLimit(memoryBytes);
            context.setMaxStackSize(512 * 1024);
            // Source logs can contain cookies and URLs. A host logger must explicitly opt in later.
            context.setConsole(new QuickJSContext.Console() {
                @Override public void log(String info) { }
                @Override public void info(String info) { }
                @Override public void warn(String info) { }
                @Override public void error(String info) { }
            });
            context.evaluate("globalThis.__bananaUnwrap=data=>{const reply=JSON.parse(data);"
                    + "if(reply.error)throw new Error(reply.error);return reply.value;};");
            if (storage != null) installStorage(storage);
            if (http != null) installHttp(http);
            context.setModuleLoader(new QuickJSContext.DefaultModuleLoader() {
                @Override public String moduleNormalizeName(String base, String name) {
                    if (snapshot.containsKey(name)) return name;
                    return URI.create(base).resolve(name).normalize().toString();
                }

                @Override public String getModuleStringCode(String name) {
                    String code = snapshot.get(name);
                    if (code == null) throw new IllegalArgumentException("Unknown script module");
                    return code;
                }
            });
            if (snapshot.containsKey(ScriptLibraries.MODULE)) installLibraries();
            context.evaluateModule("import source from " + JSONObject.quote(entry)
                    + "; globalThis.__bananaSource = source;", "banana-entry.js");
            context.evaluate("if (!globalThis.__bananaSource || typeof __bananaSource !== 'object')"
                    + " throw new Error('Source must export an object');");
        } catch (RuntimeException | Error failure) {
            if (http != null) http.close();
            context.close();
            throw failure;
        }
    }

    private void installLibraries() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        context.getGlobalObject().setProperty("__bananaRandomBytes", (JSCallFunction) args -> {
            if (args.length != 1 || !(args[0] instanceof Number)) throw new IllegalArgumentException("Invalid random byte count");
            double number = ((Number) args[0]).doubleValue();
            if (number < 0 || number > 65536 || number != Math.floor(number)) throw new IllegalArgumentException("Invalid random byte count");
            byte[] bytes = new byte[(int) number];
            random.nextBytes(bytes);
            JSONArray values = new JSONArray();
            for (byte value : bytes) values.put(value & 255);
            return values.toString();
        });
        context.getGlobalObject().setProperty("__bananaResolveUrl", (JSCallFunction) args -> {
            String base = text(args, 0), relative = text(args, 1);
            // Keep absolute non-HTTP references intact for the source to filter itself.
            // HTTP resolution still uses OkHttp for Unicode paths and canonicalization.
            java.util.regex.Matcher scheme = URI_SCHEME.matcher(relative);
            if (scheme.find() && !scheme.group(1).equalsIgnoreCase("http") && !scheme.group(1).equalsIgnoreCase("https")) return relative;
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(base);
            if (parsed == null) return URI.create(base).resolve(relative).toString();
            okhttp3.HttpUrl resolved = parsed.resolve(relative);
            if (resolved == null) throw new IllegalArgumentException("Invalid relative URL");
            return resolved.toString();
        });
        context.getGlobalObject().setProperty("__bananaGbkEncode", (JSCallFunction) args ->
                hostResult(() -> java.net.URLEncoder.encode(text(args, 0), "GBK")));
        context.getGlobalObject().setProperty("__bananaGbkDecode", (JSCallFunction) args ->
                hostResult(() -> java.net.URLDecoder.decode(text(args, 0), "GBK")));
        context.evaluate("globalThis.gbkTool={"
                + "encode:value=>__bananaUnwrap(__bananaGbkEncode(String(value))),"
                + "decode:value=>__bananaUnwrap(__bananaGbkDecode(String(value)))};");
        context.evaluate("globalThis.crypto={getRandomValues(view){"
                + "if(!ArrayBuffer.isView(view)||view instanceof DataView||view instanceof Float32Array||view instanceof Float64Array)"
                + "throw new TypeError('Integer typed array required');"
                + "if(view.byteLength>65536)throw new RangeError('Random byte limit');"
                + "const data=JSON.parse(__bananaRandomBytes(view.byteLength));"
                + "const bytes=new Uint8Array(view.buffer,view.byteOffset,view.byteLength);"
                + "for(let i=0;i<bytes.length;i++)bytes[i]=data[i];return view;}};");
        context.evaluateModule("import {CryptoJS,cheerio,pdfh,pdfa,pd,urljoin,installStringHelpers} from '@banana/libraries';"
                + "installStringHelpers();"
                + "globalThis.CryptoJS=CryptoJS; globalThis.cheerio=cheerio;"
                + "globalThis.pdfh=pdfh;globalThis.pdfa=pdfa;globalThis.pd=pd;globalThis.urljoin=urljoin;globalThis.joinUrl=urljoin;"
                + "globalThis.md5=value=>CryptoJS.MD5(value).toString();"
                + "globalThis.base64Encode=value=>CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse(value));"
                + "globalThis.base64Decode=value=>CryptoJS.enc.Utf8.stringify(CryptoJS.enc.Base64.parse(value));"
                + "globalThis.log=()=>{};", "banana-libraries-entry.js");
    }

    private void installHttp(ScriptHttp http) {
        context.getGlobalObject().setProperty("__bananaRequest", (JSCallFunction) args ->
                hostResult(() -> http.request(text(args, 0), new JSONObject(text(args, 1)))));
        context.evaluate("globalThis.req=(url,options={})=>__bananaUnwrap(__bananaRequest(url,JSON.stringify(options)));");
    }

    private void installStorage(ScriptStorage storage) {
        JSObject local = context.createNewJSObject();
        try {
            local.setProperty("get", (JSCallFunction) args -> hostResult(() -> storage.get(text(args, 0), text(args, 1))));
            local.setProperty("set", (JSCallFunction) args -> hostResult(() -> {
                storage.set(text(args, 0), text(args, 1), text(args, 2));
                return null;
            }));
            local.setProperty("delete", (JSCallFunction) args -> hostResult(() -> {
                storage.delete(text(args, 0), text(args, 1));
                return null;
            }));
            // The wrapper caches this borrowed handle and uses it during context destruction.
            context.getGlobalObject().setProperty("local", local);
        } finally { local.release(); }
        context.evaluate("(()=>{const native=globalThis.local;globalThis.local={"
                + "get:(namespace,key)=>__bananaUnwrap(native.get(String(namespace),String(key))),"
                + "set:(namespace,key,value)=>__bananaUnwrap(native.set(String(namespace),String(key),String(value))),"
                + "delete:(namespace,key)=>__bananaUnwrap(native.delete(String(namespace),String(key)))};})();");
    }

    private static String hostResult(java.util.concurrent.Callable<Object> action) {
        try {
            Object value = action.call();
            return new JSONObject().put("value", value == null ? JSONObject.NULL : value).toString();
        } catch (Exception failure) {
            // Raise a JavaScript Error in the wrapper, so source-level try/catch can recover.
            return "{\"error\":\"Source host call failed\"}";
        }
    }

    void captureFixtureConsole(QuickJSContext.Console console) {
        checkOwner();
        if (!BuildConfig.SOURCE_REFERENCE_FIXTURE) throw new IllegalStateException("Reference diagnostics disabled");
        context.setConsole(console);
    }

    private static String text(Object[] arguments, int index) {
        if (index >= arguments.length || !(arguments[index] instanceof String)) {
            throw new IllegalArgumentException("Host argument " + index + " must be a string");
        }
        return (String) arguments[index];
    }

    /** Returns source wire JSON, a source-provided string, or null for an absent optional export. */
    public String call(Operation operation, JSONArray arguments) {
        checkOwner();
        if (closed) throw new IllegalStateException("Script session closed");
        String invocation = "(() => { const reply = {done:false}; globalThis.__bananaReply = reply;"
                + " const fail = () => { if(!reply.done){reply.done=true;reply.error=true;} };"
                + " const finish = result => { if(reply.done)return;"
                + " try { const text = result == null ? null : typeof result === 'string' ? result : JSON.stringify(result);"
                + " if(result != null && typeof text !== 'string') {fail();return;}"
                + " reply.limit = text != null && text.length > " + maximumResultBytes + ";"
                + " reply.text = reply.limit ? null : text; reply.done=true; } catch(error){fail();} };"
                + " try { const source = globalThis.__bananaSource;"
                + " const fn = source[" + JSONObject.quote(operation.export) + "];"
                + " if (fn == null) {finish(null);return;}"
                + " if (typeof fn !== 'function') throw new Error('Invalid source operation');"
                + " const result = fn.apply(source," + arguments.toString() + ");"
                + " if (result && typeof result.then === 'function')"
                + " Promise.resolve(result).then(finish,fail); else finish(result);"
                + " } catch(error){fail();} })()";
        // This pinned wrapper drains QuickJS's queued Promise jobs after evaluation.
        // Host events/timers which need a later turn still require a worker event loop.
        context.evaluate(invocation, "banana-call.js");
        JSONObject reply;
        try {
            reply = new JSONObject((String) context.evaluate("JSON.stringify(globalThis.__bananaReply)"));
        } catch (org.json.JSONException invalid) {
            throw new IllegalStateException("Invalid script completion", invalid);
        }
        if (!reply.optBoolean("done")) throw new IllegalStateException("Source promise needs a pending host event");
        if (reply.optBoolean("error")) throw new IllegalStateException("Source operation failed");
        if (reply.optBoolean("limit")) throw new IllegalStateException("Source result exceeds limit");
        if (reply.isNull("text")) return null;
        String text = reply.optString("text");
        if (text.getBytes(StandardCharsets.UTF_8).length > maximumResultBytes) {
            throw new IllegalStateException("Source result exceeds limit");
        }
        return text;
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Script session belongs to another thread");
    }

    @Override public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        try {
            context.evaluate("if (typeof __bananaSource.destroy === 'function') __bananaSource.destroy();");
        } finally {
            if (http != null) http.close();
            context.close();
        }
    }
}
