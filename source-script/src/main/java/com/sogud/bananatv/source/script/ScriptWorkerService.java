package com.sogud.bananatv.source.script;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private worker transport shared by the JavaScript and Python source backends. */
public class ScriptWorkerService extends Service {
    static final int RUN = 1, STARTED = 2, FINISHED = 3, TERMINATE = 4, BUSY = 5;
    static final int OPEN = 6, CALL = 7, RELEASE = 8;
    public static final class Slot0 extends ScriptWorkerService { }
    public static final class Slot1 extends ScriptWorkerService { }
    public static final class Slot2 extends ScriptWorkerService { }
    public static final class Slot3 extends ScriptWorkerService { }
    private static volatile String generation;
    private static volatile boolean released;
    private WorkerSession persistent;
    private ScriptDatabase database;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final okhttp3.OkHttpClient http = new okhttp3.OkHttpClient();
    private final Messenger control = new Messenger(new Handler(Looper.getMainLooper(), this::receive));

    @Override public IBinder onBind(Intent intent) { return control.getBinder(); }

    private boolean receive(Message message) {
        if (message.sendingUid != Process.myUid()) return true;
        boolean leased = getClass() != ScriptWorkerService.class;
        String requested = message.getData().getString("generation");
        if (message.what == TERMINATE) {
            if (!leased || (generation != null && generation.equals(requested))) Process.killProcess(Process.myPid());
            return true;
        }
        if (leased) {
            if (requested == null || requested.isEmpty()) { reject(message); return true; }
            if (message.what == OPEN && (generation == null || (released && !running.get()))) { generation = requested; released = false; }
            if (!requested.equals(generation) || (message.what != OPEN && message.what != CALL && message.what != RELEASE)) {
                reject(message); return true;
            }
        } else if (message.what != RUN) return false;
        Bundle data = message.getData();
        ParcelFileDescriptor input = data.getParcelable("input");
        ParcelFileDescriptor output = data.getParcelable("output");
        Messenger callback = message.replyTo;
        int request = message.arg1;
        int operation = message.what;
        if (input == null || output == null || callback == null) {
            close(input); close(output);
            return true;
        }
        if (!running.compareAndSet(false, true)) {
            close(input); close(output);
            reply(callback, BUSY, request, requested);
            return true;
        }
        reply(callback, STARTED, request, requested);
        executor.execute(() -> {
            try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(input);
                 ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                JSONObject result;
                JSONObject payload = null;
                try {
                    payload = ScriptWire.read(in, ScriptWire.REQUEST_LIMIT);
                    result = leased ? executeSession(operation, payload) : execute(payload);
                } catch (Exception failure) {
                    // Never return exception messages which can include source URLs or script text.
                    result = new JSONObject().put("error", "script_failed");
                    if (BuildConfig.SOURCE_REFERENCE_FIXTURE && payload != null && payload.optBoolean("diagnostics")) {
                        result.put("diagnostic", failure.toString());
                    }
                }
                if (leased) result.put("generation", requested);
                ScriptWire.write(out, result, leased ? ScriptWire.SESSION_RESULT_LIMIT : ScriptWire.RESULT_LIMIT);
            } catch (Exception ignored) {
                // Closing the result pipe signals transport failure to the caller.
            } finally {
                running.set(false);
                reply(callback, FINISHED, request, requested);
            }
        });
        return true;
    }

    private void reject(Message request) {
        close(request.getData().getParcelable("input")); close(request.getData().getParcelable("output"));
        if (request.replyTo != null) reply(request.replyTo, BUSY, request.arg1, request.getData().getString("generation"));
    }

    private JSONObject executeSession(int operation, JSONObject request) throws Exception {
        if (!generation.equals(request.getString("generation"))) throw new IllegalStateException("Stale script request");
        if (operation == OPEN) {
            if (persistent != null) throw new IllegalStateException("Session already open");
            WorkerSession opening = createSession(request, 1024 * 1024);
            boolean ready = false;
            try {
                opening.call(ScriptSession.Operation.INIT, request.getJSONArray("initialization"));
                persistent = opening; ready = true;
            } finally { if (!ready) opening.close(); }
            return new JSONObject().put("opened", true);
        }
        if (persistent == null) throw new IllegalStateException("Session not open");
        if (operation == CALL) {
            String result = persistent.call(ScriptSession.Operation.valueOf(request.getString("operation")), request.getJSONArray("arguments"));
            return new JSONObject().put("value", result == null ? JSONObject.NULL : result);
        }
        WorkerSession closing = persistent; persistent = null;
        closing.close(); released = true;
        return new JSONObject().put("closed", true);
    }

    protected WorkerSession createSession(JSONObject request, int maximumResultBytes) throws Exception {
        JSONObject supplied = request.getJSONObject("modules");
        Map<String, String> modules = new HashMap<>();
        Iterator<String> names = supplied.keys();
        while (names.hasNext()) { String name = names.next(); modules.put(name, supplied.getString(name)); }
        if (request.optString("profile").equals("drpy")) modules = ScriptLibraries.drpyModules(this, modules, request.getString("entry"));
        else if (request.optBoolean("libraries")) modules = ScriptLibraries.modules(this, modules, request.getString("entry"));
        ScriptStorage storage = storage(request);
        return new ScriptSession(modules, request.getString("entry"), 32 * 1024 * 1024,
                maximumResultBytes, storage, new ScriptHttp(http, 2 * 1024 * 1024));
    }

    protected final ScriptStorage storage(JSONObject request) throws Exception {
        ScriptStorage storage = null;
        String sourceId = request.optString("sourceId");
        if (!sourceId.isEmpty() && getClass() != ScriptWorkerService.class) {
            if (database == null) database = new ScriptDatabase(this);
            storage = database.source(sourceId);
        } else if (!sourceId.isEmpty()) {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(sourceId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String file = "source-" + android.util.Base64.encodeToString(digest,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            storage = new PreferenceStorage(getSharedPreferences(file, MODE_PRIVATE));
        }
        return storage;
    }

    private JSONObject execute(JSONObject request) throws Exception {
        JSONArray calls = request.getJSONArray("calls");
        if (calls.length() > 32) throw new IllegalArgumentException("Too many script operations");
        JSONArray results = new JSONArray();
        StringBuilder fixtureTrace = new StringBuilder();
        try (WorkerSession session = createSession(request, 32 * 1024)) {
            if (BuildConfig.SOURCE_REFERENCE_FIXTURE && request.optBoolean("diagnostics") && session instanceof ScriptSession) {
                ((ScriptSession) session).captureFixtureConsole(new com.whl.quickjs.wrapper.QuickJSContext.Console() {
                    private void append(String value) {
                        int start = Math.max(0, value.length() - 4095);
                        fixtureTrace.append(value, start, value.length()).append('\n');
                        if (fixtureTrace.length() > 4096) fixtureTrace.delete(0, fixtureTrace.length() - 4096);
                    }
                    @Override public void log(String value) { append(value); }
                    @Override public void info(String value) { append(value); }
                    @Override public void warn(String value) { append(value); }
                    @Override public void error(String value) { append(value); }
                });
            }
            for (int index = 0; index < calls.length(); index++) {
                JSONObject call = calls.getJSONObject(index);
                ScriptSession.Operation operation = ScriptSession.Operation.valueOf(call.getString("operation"));
                String result = session.call(operation, call.getJSONArray("arguments"));
                results.put(result == null ? JSONObject.NULL : result);
            }
        }
        JSONObject response = new JSONObject().put("results", results);
        if (BuildConfig.SOURCE_REFERENCE_FIXTURE && request.optBoolean("diagnostics")) response.put("fixtureTrace", fixtureTrace.toString());
        return response;
    }

    private static void reply(Messenger callback, int kind, int request, String generation) {
        Message response = Message.obtain(null, kind, request, Process.myPid());
        if (generation != null) response.getData().putString("generation", generation);
        try { callback.send(response); } catch (RemoteException ignored) { }
    }

    private static void close(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (IOException ignored) { }
    }

    @Override public void onDestroy() {
        // Run native cleanup on the interpreter owner thread; the client bounds a looping destroy hook.
        executor.execute(() -> {
            try { if (persistent != null) { WorkerSession closing = persistent; persistent = null; closing.close(); } }
            finally { if (database != null) database.close(); }
        });
        executor.shutdown();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
        super.onDestroy();
    }
}
