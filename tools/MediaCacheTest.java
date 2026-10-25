package com.sogud.bananatv.media;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class MediaCacheTest {
    private static void require(boolean value) {
        if (!value) throw new AssertionError("Private file cache regression");
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); File directory = new File(root, "entries");
        MediaCache cache = new MediaCache(directory, 12);
        byte[] value = new byte[]{1, 2, 3, 4, 5, 6};
        cache.put("a", value);
        File first = directory.listFiles()[0]; first.setLastModified(System.currentTimeMillis() - 2000);
        cache.put("b", value);
        cache.put("c", value);
        require(cache.get("a") == null && Arrays.equals(cache.get("b"), value));
        require(Arrays.equals(new MediaCache(directory, 12).get("c"), value));
        require(cache.get("different-query") == null);
        cache.put("too-large", new byte[13]); require(cache.get("too-large") == null);
        for (File file : directory.listFiles()) file.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8));
        require(cache.get("c") == null);
        File unavailable = new File(root, "not-a-directory"); Files.write(unavailable.toPath(), value);
        MediaCache failed = new MediaCache(unavailable, 12); failed.put("a", value);
        require(failed.get("a") == null);
        System.out.println("Private cache persistence, query isolation, age/size bounds and unavailable storage passed");
    }
}
