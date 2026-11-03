package com.sogud.bananatv.source.script;

/** A source owner supplies its own store; the interpreter never chooses another source's scope. */
public interface ScriptStorage {
    String get(String namespace, String key);
    void set(String namespace, String key, String value);
    void delete(String namespace, String key);
}
