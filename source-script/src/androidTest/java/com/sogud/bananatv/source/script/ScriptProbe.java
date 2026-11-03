package com.sogud.bananatv.source.script;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.sogud.bananatv.source.script.ScriptSession.Operation.*;

/** Synthetic scripts only; runs in this library's disposable test APK. */
public final class ScriptProbe extends Instrumentation {
    private int checks;
    private String ruleFilter = "";
    private boolean persistentOnly;
    private boolean liveDrpy;
    private boolean drpyReference;
    private boolean onlyReference;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        persistentOnly = args != null && "persistent".equals(args.getString("only"));
        if (args != null) ruleFilter = args.getString("rule", "");
        liveDrpy = args != null && "live-drpy".equals(args.getString("reference"));
        drpyReference = args != null && "drpy".equals(args.getString("reference"));
        onlyReference = drpyReference && "reference".equals(args.getString("only"));
        start();
    }

    private void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
        Bundle status = new Bundle();
        status.putString("check", label);
        sendStatus(0, status);
    }

    private ScriptSession open(String code) {
        return new ScriptSession(Collections.singletonMap("source.js", code), "source.js", 16 * 1024 * 1024, 4096);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (persistentOnly) {
                PersistentWorkerProbe.run(this, label -> require(true,label)); result.putString("result","PASS"); result.putInt("checks",checks);
                finish(Activity.RESULT_OK,result); return;
            }
            if (liveDrpy) {
                result.putString("report", LiveDrpyProbe.run(this, ruleFilter).toString()); result.putString("result", "OBSERVED");
                finish(Activity.RESULT_OK, result); return;
            }
            if (onlyReference) {
                DrpyReferenceProbe.run(getContext(), label -> require(true, label));
                result.putString("result", "PASS"); result.putInt("checks", checks);
                finish(Activity.RESULT_OK, result);
                return;
            }
            Map<String, String> modules = new HashMap<>();
            modules.put("source.js", "import {title} from './title.js'; let base=''; export default {"
                    + "init(ext){base=ext.base;}, home(){return {class:[{type_id:'1',type_name:title}]};},"
                    + "category(id,page){return {page,list:[{vod_id:id,vod_name:title}]};},"
                    + "search(word){return {list:[{vod_name:word}]};},"
                    + "detail(id){return {list:[{vod_id:id,vod_play_url:'1$'+base+'/clip.mp4'}]};},"
                    + "play(flag,id){return {url:id,parse:0};}};");
            modules.put("title.js", "export const title='动画';");
            try (ScriptSession session = new ScriptSession(modules, "source.js", 16 * 1024 * 1024, 4096)) {
                require(session.call(INIT, new JSONArray().put(new JSONObject().put("base", "https://fixture.invalid"))) == null,
                        "init_accepts_object_and_undefined");
                require(new JSONObject(session.call(HOME, new JSONArray())).getJSONArray("class").getJSONObject(0)
                        .getString("type_name").equals("动画"), "relative_module_and_home");
                require(new JSONObject(session.call(CATEGORY, new JSONArray().put("1").put(2))).getInt("page") == 2,
                        "category_arguments");
                String query = "quoted\"\\\n影视";
                require(new JSONObject(session.call(SEARCH, new JSONArray().put(query))).getJSONArray("list")
                        .getJSONObject(0).getString("vod_name").equals(query), "search_arguments_are_data");
                require(new JSONObject(session.call(DETAIL, new JSONArray().put("film"))).getJSONArray("list")
                        .getJSONObject(0).getString("vod_play_url").equals("1$https://fixture.invalid/clip.mp4"),
                        "session_state_and_detail");
                require(new JSONObject(session.call(PLAY, new JSONArray().put("main").put("https://fixture.invalid/clip.mp4")))
                        .getInt("parse") == 0, "play_result");
                require(session.call(HOME_VIDEO, new JSONArray()) == null, "optional_export_absent");
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread other = new Thread(() -> {
                    try { session.call(HOME, new JSONArray()); } catch (Throwable error) { failure.set(error); }
                });
                other.start(); other.join();
                require(failure.get() instanceof IllegalStateException, "foreign_thread_rejected_before_native_access");
            }
            try (ScriptSession session = open("export default {home(){return '{\"list\":[]}';}}")) {
                require(session.call(HOME, new JSONArray()).equals("{\"list\":[]}"), "wire_string_not_double_encoded");
                session.close(); session.close();
                boolean rejected = false;
                try { session.call(HOME, new JSONArray()); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "close_idempotent_and_use_after_close_rejected");
            }
            try (ScriptSession session = open("export default {home(){throw new Error('fixture');}, search(){return null;}}")) {
                boolean rejected = false;
                try { session.call(HOME, new JSONArray()); } catch (RuntimeException expected) { rejected = true; }
                require(rejected && session.call(SEARCH, new JSONArray()) == null, "script_exception_does_not_destroy_session");
            }
            try (ScriptSession session = open("export default {home(){return 'x'.repeat(4097);}}")) {
                boolean rejected = false;
                try { session.call(HOME, new JSONArray()); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "oversized_result_rejected");
            }
            try (ScriptSession session = open("export default {home(){return Promise.resolve({list:[]});}}")) {
                require(new JSONObject(session.call(HOME, new JSONArray())).getJSONArray("list").length() == 0,
                        "promise_result_resolved_before_return");
            }
            try (ScriptSession session = open("export default {async init(ext){await Promise.resolve();this.value=ext.value;},"
                    + "async home(){const value=await Promise.resolve(this.value); return Promise.resolve({name:value});}}")) {
                require(session.call(INIT, new JSONArray().put(new JSONObject().put("value", "动画"))) == null
                        && new JSONObject(session.call(HOME, new JSONArray())).getString("name").equals("动画"),
                        "async_init_await_and_nested_promise_preserve_state");
            }
            try (ScriptSession session = open("export default {home(){return Promise.reject(new Error('fixture-secret'));},"
                    + "search(){return 'ready';}}")) {
                boolean rejected = false;
                try { session.call(HOME, new JSONArray()); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected && session.call(SEARCH, new JSONArray()).equals("ready"),
                        "promise_rejection_consumed_without_poisoning_session");
            }
            try (ScriptSession session = open("let release; export default {home(){return new Promise(r=>release=r);},"
                    + "search(){release('old');return 'fresh';}}")) {
                boolean pending = false;
                try { session.call(HOME, new JSONArray()); } catch (IllegalStateException expected) { pending = true; }
                require(pending && session.call(SEARCH, new JSONArray()).equals("fresh"),
                        "unsettled_promise_does_not_overwrite_next_result");
            }
            try (ScriptSession session = open("export default {home(){return Promise.resolve('x'.repeat(4097));}}")) {
                boolean rejected = false;
                try { session.call(HOME, new JSONArray()); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "async_result_respects_size_limit");
            }
            try (ScriptSession session = open("export default {home(){return ()=>{};}}")) {
                boolean rejected = false;
                try { session.call(HOME, new JSONArray()); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "unserializable_result_not_treated_as_missing_content");
            }
            try (ScriptSession session = open("export default {home(){return {then(resolve){resolve('first');resolve('second');}};}}")) {
                require(session.call(HOME, new JSONArray()).equals("first"), "thenable_uses_first_settlement");
            }
            try (ScriptSession first = open("globalThis.fixtureValue='first'; export default {home(){return fixtureValue;}}")) {
                try (ScriptSession second = open("export default {home(){return typeof globalThis.fixtureValue;}}")) {
                    require(first.call(HOME, new JSONArray()).equals("first")
                            && second.call(HOME, new JSONArray()).equals("undefined"), "session_globals_do_not_leak");
                }
            }
            try (ScriptSession session = open("export default {home(){return '影'.repeat(2000);}}")) {
                boolean rejected = false;
                try { session.call(HOME, new JSONArray()); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "result_limit_counts_utf8_bytes");
            }
            boolean missingImport = false;
            try (ScriptSession ignored = open("import value from './missing.js'; export default value;")) {
                throw new AssertionError("Missing import accepted");
            } catch (RuntimeException expected) { missingImport = true; }
            require(missingImport, "unknown_module_rejected_without_network");
            try (ScriptSession session = open("export default {home(){return 'recovered';}}")) {
                require(session.call(HOME, new JSONArray()).equals("recovered"), "new_session_after_failed_initialization");
            }
            android.content.SharedPreferences preferences = getContext().getSharedPreferences("script-probe-fixture", 0);
            preferences.edit().clear().commit();
            try {
                ScriptStorage storage = new PreferenceStorage(preferences);
                String code = "export default {init(){local.set('settings','token','fixture-only');},"
                        + "home(){return local.get('settings','token');},search(){local.delete('settings','token');return local.get('settings','token');},"
                        + "proxy(){local.set(undefined,'count',42);return local.get(undefined,'count');}}";
                try (ScriptSession session = new ScriptSession(Collections.singletonMap("source.js", code), "source.js",
                        16 * 1024 * 1024, 4096, storage)) {
                    session.call(INIT, new JSONArray());
                    require(session.call(HOME, new JSONArray()).equals("fixture-only"), "local_set_get_bridge");
                    require(session.call(PROXY, new JSONArray()).equals("42"), "local_legacy_arguments_use_javascript_string_coercion");
                }
                try (ScriptSession restored = new ScriptSession(Collections.singletonMap("source.js", code), "source.js",
                        16 * 1024 * 1024, 4096, new PreferenceStorage(preferences))) {
                    require(restored.call(HOME, new JSONArray()).equals("fixture-only"), "local_store_survives_context_recreation");
                    require(restored.call(SEARCH, new JSONArray()).equals(""), "local_delete_bridge");
                }
                storage.set("a.b", "c", "first"); storage.set("a", "b.c", "second");
                require(storage.get("a.b", "c").equals("first") && storage.get("a", "b.c").equals("second"),
                        "storage_namespace_key_pairs_do_not_collide");
            } finally { preferences.edit().clear().commit(); }
            ScriptStorage broken = new ScriptStorage() {
                @Override public String get(String namespace, String key) { throw new IllegalStateException("private-fixture-token"); }
                @Override public void set(String namespace, String key, String value) { throw new IllegalStateException("private-fixture-token"); }
                @Override public void delete(String namespace, String key) { throw new IllegalStateException("private-fixture-token"); }
            };
            try (ScriptSession session = new ScriptSession(Collections.singletonMap("source.js",
                    "export default {home(){try{local.set('scope','key','value');return 'unexpected';}catch(error){return String(error);}}}"),
                    "source.js", 16 * 1024 * 1024, 8192, broken)) {
                require(session.call(HOME, new JSONArray()).equals("Error: Source host call failed"),
                        "script_can_catch_redacted_storage_failure");
            }
            try (WorkerProbe worker = new WorkerProbe(getContext())) {
                require(worker.run("export default {home(){return {list:[{vod_name:'fixture'}]};}}", 10000)
                        .getJSONArray("results").length() == 1, "worker_executes_outside_launcher_process");
                String large = worker.run("export default {home(){return 'x'.repeat(20000);}}", 10000)
                        .getJSONArray("results").getString(0);
                require(large.length() == 20000, "worker_pipe_result_exceeds_small_binder_metadata");
                require(worker.run("export default {home(){throw new Error('private-fixture-secret');}}", 10000)
                        .toString().equals("{\"error\":\"script_failed\"}"), "worker_errors_redact_script_data");
                require(worker.run("export default {async home(){return {list:await Promise.resolve([])};}}", 10000)
                        .getJSONArray("results").length() == 1, "worker_supports_async_source_exports");
                boolean timedOut = false;
                try { worker.run("export default {home(){while(true){};}}", 1500); }
                catch (java.util.concurrent.TimeoutException expected) { timedOut = true; }
                require(timedOut, "infinite_loop_deadline_proves_worker_binder_death");
            }
            try (WorkerProbe recovered = new WorkerProbe(getContext())) {
                require(recovered.run("export default {home(){return 'after-restart';}}", 10000)
                        .getJSONArray("results").getString(0).equals("after-restart"), "worker_recovers_after_hard_timeout");
                boolean timedOut = false;
                try { recovered.run("export default {destroy(){while(true){};},home(){return 'done';}}", 1500); }
                catch (java.util.concurrent.TimeoutException expected) { timedOut = true; }
                require(timedOut, "destroy_hook_loop_also_terminates_worker");
            }
            HttpProbe.run(getContext(), label -> require(true, label));
            LibraryProbe.run(getContext(), label -> require(true, label));
            WorkerStateProbe.run(getContext(), label -> require(true, label));
            if (drpyReference) DrpyReferenceProbe.run(getContext(), label -> require(true, label));
            result.putString("result", "PASS"); result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", failure.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
