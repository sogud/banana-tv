package com.sogud.bananatv.source.script;

import org.json.JSONArray;

/** Interpreter owned by one worker executor; transport and lifetime are shared across languages. */
public interface WorkerSession extends AutoCloseable {
    String call(ScriptSession.Operation operation, JSONArray arguments) throws Exception;
    @Override void close();
}
