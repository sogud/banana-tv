package com.sogud.bananatv.source.script;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Private pipe framing; script text and results do not travel in Binder strings. */
final class ScriptWire {
    static final int REQUEST_LIMIT = 1024 * 1024;
    static final int RESULT_LIMIT = 64 * 1024;
    static final int SESSION_RESULT_LIMIT = 8 * 1024 * 1024;

    static JSONObject read(InputStream input, int maximum) throws IOException, JSONException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, maximum - bytes.size() + 1));
            if (count < 0) break;
            if (bytes.size() + count > maximum) throw new IOException("Script pipe exceeds size limit");
            bytes.write(buffer, 0, count);
        }
        return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    static void write(OutputStream output, JSONObject value, int maximum) throws IOException {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IOException("Script pipe exceeds size limit");
        output.write(bytes);
    }
}
