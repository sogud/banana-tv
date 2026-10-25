package com.sogud.bananatv.media;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Bounded private display cache. A cache failure must not prevent a network request. */
final class MediaCache {
    private static final long MAX_AGE = TimeUnit.DAYS.toMillis(7);
    private static final int MAX_ENTRY = 2 * 1024 * 1024;
    private final File directory;
    private final long maximumBytes;

    MediaCache(File directory, long maximumBytes) {
        this.directory = directory; this.maximumBytes = maximumBytes;
    }
    private File file(String key) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new File(directory, name + ".cache");
    }
    synchronized byte[] get(String key) {
        try {
            File file = file(key);
            long age = System.currentTimeMillis() - file.lastModified();
            if (!file.isFile() || file.length() > MAX_ENTRY || age < 0 || age > MAX_AGE) return null;
            return Files.readAllBytes(file.toPath());
        } catch (Exception ignored) { return null; }
    }
    synchronized void put(String key, byte[] bytes) {
        if (bytes.length > MAX_ENTRY || bytes.length > maximumBytes) return;
        File pending = null;
        try {
            directory.mkdirs(); File destination = file(key);
            pending = new File(directory, destination.getName() + ".pending");
            try (FileOutputStream output = new FileOutputStream(pending)) { output.write(bytes); }
            if (!pending.renameTo(destination)) return;
            File[] files = directory.listFiles((parent, name) -> name.endsWith(".cache"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            long total = 0;
            for (File item : files) total += item.length();
            for (File item : files) {
                if (total <= maximumBytes) break;
                long size = item.length();
                if (item.delete()) total -= size;
            }
        } catch (Exception ignored) { /* Results remain usable without disk caching. */ }
        finally { if (pending != null && pending.exists()) pending.delete(); }
    }
}
