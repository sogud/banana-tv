package com.sogud.bananatv.source.script;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Explicit library profile. Supplied source modules are never modified or silently overwritten. */
public final class ScriptLibraries {
    static final String MODULE = "@banana/libraries";

    public static Map<String, String> modules(Context context, Map<String, String> supplied, String entry) throws IOException {
        Map<String, String> modules = new HashMap<>(supplied);
        if (modules.containsKey(MODULE)) throw new IllegalArgumentException("Reserved library module already supplied");
        try (InputStream input = context.getAssets().open("banana-libraries.js")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > 1024 * 1024) throw new IOException("Library bundle exceeds size limit");
                output.write(buffer, 0, count);
            }
            modules.put(MODULE, new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
        return modules;
    }

    /** For custom drpy rules only: preset template data is not provided by this profile. */
    public static Map<String, String> drpyModules(Context context, Map<String, String> supplied, String entry) throws IOException {
        Map<String, String> modules = modules(context, supplied, entry);
        String core = URI.create(entry).resolve("./drpy-core-lite.min.js").normalize().toString();
        if (modules.containsKey(core)) throw new IllegalArgumentException("Source already supplies its core module");
        modules.put(core, "export {cheerio} from '@banana/libraries'; export const 模板={getMubans(){return {};}};");
        return modules;
    }
}
