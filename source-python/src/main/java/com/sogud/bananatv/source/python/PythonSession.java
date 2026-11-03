package com.sogud.bananatv.source.python;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.sogud.bananatv.source.script.ScriptSession;
import com.sogud.bananatv.source.script.ScriptStorage;
import com.sogud.bananatv.source.script.WorkerSession;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

final class PythonSession implements WorkerSession {
    private final Thread owner = Thread.currentThread();
    private final int maximumResultBytes;
    private PyObject session;

    PythonSession(String entry, JSONObject modules, ScriptStorage storage, int maximumResultBytes) {
        this.maximumResultBytes = maximumResultBytes;
        session = Python.getInstance().getModule("banana_python_host").callAttr("Session", entry, modules.toString(), storage);
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Python session belongs to another thread");
    }
    @Override public String call(ScriptSession.Operation operation, JSONArray arguments) {
        checkOwner();
        if (session == null) throw new IllegalStateException("Python session is closed");
        try (PyObject result = session.callAttr("call", operation.name(), arguments.toString())) {
            if (result == null) return null;
            String value = result.toString();
            if (value.getBytes(StandardCharsets.UTF_8).length > maximumResultBytes)
                throw new IllegalStateException("Source result exceeds limit");
            return value;
        }
    }
    @Override public void close() {
        checkOwner();
        PyObject closing = session; session = null;
        if (closing != null) {
            try (PyObject ignored = closing.callAttr("close")) { }
            finally { closing.close(); }
        }
    }
}
