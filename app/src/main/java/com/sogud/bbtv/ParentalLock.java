package com.sogud.bbtv;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** A local settings PIN, not an Android account or a device-wide parental control. */
final class ParentalLock {
    private static volatile long unlockedUntil;
    private static long retryAfter;
    private static int failures;

    static boolean enabled(Context context) {
        return !HomeOptions.text(context, "pin_hash", "").isEmpty();
    }

    static boolean locked(Context context) {
        return enabled(context) && SystemClock.elapsedRealtime() >= unlockedUntil;
    }

    static void relock() { unlockedUntil = 0; }

    static boolean valid(String pin) { return pin != null && pin.matches("[0-9]{4,8}"); }

    static void set(Context context, String pin) throws Exception {
        if (!valid(pin)) throw new IllegalArgumentException("Invalid PIN");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        HomeOptions.prefs(context).edit()
                .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pin_hash", Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP)).apply();
        unlockedUntil = SystemClock.elapsedRealtime() + 600_000L;
    }

    static boolean verify(Context context, String pin) {
        long now = SystemClock.elapsedRealtime();
        if (now < retryAfter || !valid(pin)) return false;
        try {
            byte[] salt = Base64.decode(HomeOptions.text(context, "pin_salt", ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(HomeOptions.text(context, "pin_hash", ""), Base64.NO_WRAP);
            if (MessageDigest.isEqual(expected, derive(pin, salt))) {
                failures = 0;
                unlockedUntil = now + 600_000L;
                return true;
            }
        } catch (Exception ignored) { }
        if (++failures >= 5) { retryAfter = now + 30_000L; failures = 0; }
        return false;
    }

    static void disable(Context context) {
        HomeOptions.prefs(context).edit().remove("pin_hash").remove("pin_salt").apply();
        relock();
    }

    private static byte[] derive(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 20_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
