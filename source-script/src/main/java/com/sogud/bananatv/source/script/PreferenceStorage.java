package com.sogud.bananatv.source.script;

import android.content.SharedPreferences;
import org.json.JSONArray;

/** The caller owns the source-specific preference file and its lifecycle. */
public final class PreferenceStorage implements ScriptStorage {
    private final SharedPreferences preferences;

    public PreferenceStorage(SharedPreferences preferences) {
        this.preferences = java.util.Objects.requireNonNull(preferences);
    }

    private static String name(String namespace, String key) {
        return new JSONArray().put(namespace).put(key).toString();
    }

    @Override public String get(String namespace, String key) {
        return preferences.getString(name(namespace, key), "");
    }

    @Override public void set(String namespace, String key, String value) {
        // A timed-out worker may be killed immediately after this callback returns.
        if (!preferences.edit().putString(name(namespace, key), value).commit()) {
            throw new IllegalStateException("Source storage write failed");
        }
    }

    @Override public void delete(String namespace, String key) {
        if (!preferences.edit().remove(name(namespace, key)).commit()) {
            throw new IllegalStateException("Source storage delete failed");
        }
    }
}
