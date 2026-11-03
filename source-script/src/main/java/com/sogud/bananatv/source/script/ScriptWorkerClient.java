package com.sogud.bananatv.source.script;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent worker owner. Open/call/close run off the UI thread; cancel is nonblocking. */
public final class ScriptWorkerClient implements AutoCloseable {
    public enum Profile { NONE, LIBRARIES, DRPY, PYTHON }
    public static final class Source {
        private final String entry, sourceId, initialization;
        private final Map<String, String> modules;
        private final Profile profile;
        public Source(String entry, Map<String, String> modules, String sourceId, Profile profile, JSONArray initialization) {
            this.entry = Objects.requireNonNull(entry); this.modules = new HashMap<>(modules);
            this.sourceId = Objects.requireNonNull(sourceId); this.profile = Objects.requireNonNull(profile);
            this.initialization = Objects.requireNonNull(initialization).toString();
            if (!this.modules.containsKey(entry) || sourceId.isEmpty()) throw new IllegalArgumentException("Source entry and owner are required");
        }
        private JSONObject request(String generation) throws Exception {
            JSONObject request = new JSONObject().put("generation", generation).put("entry", entry).put("sourceId", sourceId)
                    .put("modules", new JSONObject(modules)).put("initialization", new JSONArray(initialization));
            if (profile == Profile.PYTHON) request.put("profile", "python");
            if (profile == Profile.DRPY) request.put("profile", "drpy");
            if (profile == Profile.LIBRARIES) request.put("libraries", true);
            return request;
        }
    }
    private static final Class<?>[] COMPONENTS = {ScriptWorkerService.Slot0.class, ScriptWorkerService.Slot1.class,
            ScriptWorkerService.Slot2.class, ScriptWorkerService.Slot3.class};
    private static final Lease[] LEASES = new Lease[COMPONENTS.length];
    private static final Lease[] PYTHON_LEASES = new Lease[COMPONENTS.length];
    private static final class Lease {
        final int index;
        final Lease[] pool;
        Lease(int index, Lease[] pool) { this.index = index; this.pool = pool; }
        void release() { synchronized (pool) { if (pool[index] == this) pool[index] = null; } }
    }
    private static Lease reserve(Source source) throws IOException {
        Lease[] pool = source.profile == Profile.PYTHON ? PYTHON_LEASES : LEASES;
        synchronized (pool) {
            for (int index = 0; index < pool.length; index++) if (pool[index] == null) return pool[index] = new Lease(index, pool);
        }
        throw new IOException("No source worker is available");
    }
    private final Context context;
    private final Source source;
    private final Lease lease;
    private final Object serial = new Object(), lifecycle = new Object();
    private volatile ScriptProcess process;
    private boolean closed, inFlight, quarantined;

    private ScriptWorkerClient(Context context, Source source, Lease lease) {
        this.context = context.getApplicationContext(); this.source = source; this.lease = lease;
    }
    public static ScriptWorkerClient open(Context context, Source source, long timeoutMillis) throws IOException {
        checkThread(); checkTimeout(timeoutMillis);
        Objects.requireNonNull(source);
        ScriptWorkerClient client = new ScriptWorkerClient(context, source, reserve(source));
        try {
            synchronized (client.serial) { client.connect(deadline(timeoutMillis)); }
            return client;
        } catch (IOException error) { client.close(); throw error; }
    }
    private void connect(long deadline) throws IOException {
        Class<? extends ScriptWorkerService> component;
        if (source.profile == Profile.PYTHON) {
            try {
                component = Class.forName("com.sogud.bananatv.source.python.PythonWorkerService$Slot" + lease.index)
                        .asSubclass(ScriptWorkerService.class);
            } catch (ClassNotFoundException missing) { throw new IOException("Python runtime is not installed"); }
        } else {
            component = COMPONENTS[lease.index].asSubclass(ScriptWorkerService.class);
        }
        ScriptProcess opening = new ScriptProcess(context, component, UUID.randomUUID().toString()); process = opening;
        try {
            JSONObject response = opening.exchange(ScriptWorkerService.OPEN, source.request(opening.generation), deadline);
            if (!response.optBoolean("opened")) throw new IOException("Source initialization failed");
        } catch (Exception error) { invalidate(opening); throw failure(error); }
    }
    public String call(ScriptSession.Operation operation, JSONArray arguments, long timeoutMillis) throws IOException {
        checkThread(); checkTimeout(timeoutMillis);
        synchronized (serial) {
            synchronized (lifecycle) {
                if (closed || quarantined) throw new IOException("Source worker is closed");
                inFlight = true;
            }
            try {
                long deadline = deadline(timeoutMillis);
                ScriptProcess current = process;
                if (current == null || !current.usable()) {
                    if (current != null) invalidate(current);
                    if (quarantined) throw new IOException("Source worker exit was not confirmed");
                    connect(deadline); current = process;
                }
                JSONObject request = new JSONObject().put("generation", current.generation).put("operation", operation.name())
                        .put("arguments", new JSONArray(arguments.toString()));
                JSONObject response;
                try { response = current.exchange(ScriptWorkerService.CALL, request, deadline); }
                catch (Exception error) { invalidate(current); throw failure(error); }
                if (response.has("error")) throw new IOException("Source operation failed");
                return response.isNull("value") ? null : response.getString("value");
            } catch (org.json.JSONException invalid) { throw new IOException("Invalid source request"); }
            finally { synchronized (lifecycle) { inFlight = false; } }
        }
    }
    /** Cancels only this source process; the next call can initialize a fresh generation. */
    public void cancel() { ScriptProcess current = process; if (current != null) current.abort(); }
    private void invalidate(ScriptProcess current) {
        if (!current.terminateAndWait()) quarantined = true;
        current.close(); if (process == current) process = null;
    }
    int workerPid() { ScriptProcess current = process; return current == null ? 0 : current.pid(); }
    @Override public void close() {
        checkThread();
        boolean cancel;
        synchronized (lifecycle) { if (closed) return; closed = true; cancel = inFlight; }
        ScriptProcess active = process; if (cancel && active != null) active.abort();
        synchronized (serial) {
            active = process;
            if (active != null) {
                boolean released = false;
                if (active.usable()) {
                    try {
                        JSONObject request = new JSONObject().put("generation", active.generation);
                        released = active.exchange(ScriptWorkerService.RELEASE, request, deadline(1000)).optBoolean("closed");
                    } catch (Exception ignored) { }
                }
                // CPython caches imported module globals. Do not reuse them for a different source.
                if ((!released || source.profile == Profile.PYTHON) && !active.terminateAndWait()) quarantined = true;
                active.close(); process = null;
            }
            if (!quarantined) lease.release();
        }
    }
    private static long deadline(long timeout) { return SystemClock.elapsedRealtime() + timeout; }
    private static void checkTimeout(long timeout) { if (timeout <= 0 || timeout > 120000) throw new IllegalArgumentException("Source timeout must be 1–120000 ms"); }
    private static void checkThread() { if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Source worker I/O must run off the main thread"); }
    private static IOException failure(Exception error) {
        if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); return new InterruptedIOException("Source operation cancelled"); }
        if (error instanceof TimeoutException) return new SocketTimeoutException("Source worker timed out");
        return new IOException("Source worker request failed");
    }
}
