package com.sogud.bananatv.source.python;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.sogud.bananatv.source.script.ScriptWorkerService;
import com.sogud.bananatv.source.script.WorkerSession;
import org.json.JSONObject;

/** Reuses worker generations, cancellation, bounded pipes and source-owned storage. */
public class PythonWorkerService extends ScriptWorkerService {
    public static final class Slot0 extends PythonWorkerService { }
    public static final class Slot1 extends PythonWorkerService { }
    public static final class Slot2 extends PythonWorkerService { }
    public static final class Slot3 extends PythonWorkerService { }

    @Override protected WorkerSession createSession(JSONObject request, int maximumResultBytes) throws Exception {
        if (!request.optString("profile").equals("python")) throw new IllegalArgumentException("Python source required");
        if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
        return new PythonSession(request.getString("entry"), request.getJSONObject("modules"),
                storage(request), maximumResultBytes);
    }
}
