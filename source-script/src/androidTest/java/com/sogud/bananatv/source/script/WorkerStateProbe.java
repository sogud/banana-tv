package com.sogud.bananatv.source.script;

import android.content.Context;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class WorkerStateProbe {
    private static JSONObject request(String sourceId, String body) throws Exception {
        return new JSONObject().put("sourceId", sourceId).put("entry", "source.js")
                .put("modules", new JSONObject().put("source.js", "export default {home(){" + body + "}}"))
                .put("calls", new JSONArray().put(new JSONObject().put("operation", "HOME").put("arguments", new JSONArray())));
    }

    static void run(Context context, Consumer<String> report) throws Exception {
        String owner = "script-worker-storage-fixture";
        try (WorkerProbe worker = new WorkerProbe(context)) {
            JSONObject written = worker.run(request(owner, "local.set('fixture','state','committed');return local.get('fixture','state');"), 10000);
            if (!written.getJSONArray("results").getString(0).equals("committed")) throw new AssertionError("Worker storage write failed");
            report.accept("worker_storage_acknowledges_durable_write");
            boolean timedOut = false;
            try { worker.run("export default {home(){while(true){};}}", 1500); }
            catch (TimeoutException expected) { timedOut = true; }
            if (!timedOut) throw new AssertionError("Worker did not time out");
        }
        try (WorkerProbe worker = new WorkerProbe(context)) {
            JSONObject restored = worker.run(request(owner, "return local.get('fixture','state');"), 10000);
            if (!restored.getJSONArray("results").getString(0).equals("committed")) throw new AssertionError("Worker storage lost after death");
            report.accept("worker_storage_survives_real_process_death");
            JSONObject unrelated = worker.run(request(owner + "-other", "return local.get('fixture','state');"), 10000);
            if (!unrelated.getJSONArray("results").getString(0).equals("")) throw new AssertionError("Source storage crossed owners");
            report.accept("worker_storage_isolated_by_source_owner");
            JSONObject cleaned = worker.run(request(owner, "local.delete('fixture','state');return local.get('fixture','state');"), 10000);
            if (!cleaned.getJSONArray("results").getString(0).equals("")) throw new AssertionError("Fixture cleanup failed");
        }
    }
}
