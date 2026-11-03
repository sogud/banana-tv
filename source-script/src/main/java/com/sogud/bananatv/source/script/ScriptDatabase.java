package com.sogud.bananatv.source.script;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Transactional source storage shared by separate worker processes. */
final class ScriptDatabase extends SQLiteOpenHelper {
    ScriptDatabase(Context context) { super(context, "script-storage.db", null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE values_by_source (source TEXT NOT NULL, namespace TEXT NOT NULL, item TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(source,namespace,item))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unsupported source storage version"); }
    private static String owner(String sourceId) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(sourceId.getBytes(StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }
    ScriptStorage source(String sourceId) throws Exception {
        String source = owner(sourceId);
        return new ScriptStorage() {
            @Override public String get(String namespace, String key) {
                try (Cursor cursor = getReadableDatabase().query("values_by_source", new String[]{"value"},
                        "source=? AND namespace=? AND item=?", new String[]{source, namespace, key}, null, null, null)) {
                    return cursor.moveToFirst() ? cursor.getString(0) : "";
                }
            }
            @Override public void set(String namespace, String key, String value) {
                ContentValues row = new ContentValues(); row.put("source", source); row.put("namespace", namespace); row.put("item", key); row.put("value", value);
                if (getWritableDatabase().insertWithOnConflict("values_by_source", null, row, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                    throw new IllegalStateException("Source storage write failed");
            }
            @Override public void delete(String namespace, String key) {
                getWritableDatabase().delete("values_by_source", "source=? AND namespace=? AND item=?", new String[]{source, namespace, key});
            }
        };
    }
    void removeSource(String sourceId) throws Exception { getWritableDatabase().delete("values_by_source", "source=?", new String[]{owner(sourceId)}); }
}
