package com.github.catvod.crawler;

/** Binary logging callbacks. Source text and exceptions must not expose credentials in logcat. */
public class SpiderDebug {
    public SpiderDebug() { }
    public static void log(Throwable error) { }
    public static void log(String message) { }
    public static void log(String tag, String format, Object... values) { }
}
