package com.github.catvod.utils;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.github.catvod.Runtime;

public class Prefers {

    public static SharedPreferences getPrefers() {
        return PreferenceManager.getDefaultSharedPreferences(Runtime.context());
    }

    public static String getString(String key) {
        return getString(key, "");
    }

    public static String getString(String key, String defaultValue) {
        try {
            return getPrefers().getString(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static int getInt(String key) {
        return getInt(key, 0);
    }

    public static int getInt(String key, int defaultValue) {
        try {
            return getPrefers().getInt(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static long getLong(String key) {
        return getLong(key, 0L);
    }

    public static long getLong(String key, long defaultValue) {
        try {
            return getPrefers().getLong(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static float getFloat(String key) {
        return getFloat(key, 0f);
    }

    public static float getFloat(String key, float defaultValue) {
        try {
            return getPrefers().getFloat(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        try {
            return getPrefers().getBoolean(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static void put(String key, Object obj) {
        SharedPreferences.Editor editor = getPrefers().edit();
        if (obj instanceof String) editor.putString(key, (String) obj);
        else if (obj instanceof Boolean) editor.putBoolean(key, (Boolean) obj);
        else if (obj instanceof Float) editor.putFloat(key, (Float) obj);
        else if (obj instanceof Integer) editor.putInt(key, (Integer) obj);
        else if (obj instanceof Long) editor.putLong(key, (Long) obj);
        else if (obj instanceof Number) {
            Number number = (Number) obj;
            if (number.toString().contains(".")) editor.putFloat(key, number.floatValue());
            else editor.putInt(key, number.intValue());
        } else return;
        editor.apply();
    }

    public static void remove(String key) {
        getPrefers().edit().remove(key).apply();
    }
}
