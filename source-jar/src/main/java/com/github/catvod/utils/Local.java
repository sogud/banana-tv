package com.github.catvod.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.github.catvod.Runtime;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/** Source-owned JSON values. A missing key is null, distinct from the serialized JSON null. */
public final class Local {
    private final SharedPreferences preferences;

    public Local(String owner) { this(Objects.requireNonNull(Runtime.context(), "Source context is not configured"), owner); }
    public Local(Context context, String owner) {
        Objects.requireNonNull(owner);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("source-local-");
            for (byte value : digest) name.append(String.format(Locale.ROOT, "%02x", value & 255));
            preferences = context.getApplicationContext().getSharedPreferences(name.toString(), Context.MODE_PRIVATE);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }

    public String get(String key) { return preferences.getString(key, null); }
    public void set(String key, String value) {
        String serialized = JsonParser.parseString(value).toString();
        commit(preferences.edit().putString(key, serialized));
    }
    public void delete(String key) { commit(preferences.edit().remove(key)); }
    public void clear() { commit(preferences.edit().clear()); }
    private static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("Source storage update failed");
    }
}
