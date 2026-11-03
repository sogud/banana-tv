package com.sogud.bananatv.source.jar;

import android.app.Activity;
import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderApi;
import com.github.catvod.net.Net;
import com.github.catvod.utils.Local;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** A same-process source with a private, verified DEX artifact and explicit lifetime. */
public final class JarSession implements AutoCloseable {
    private static final int MAX_ARTIFACT_BYTES = 32 * 1024 * 1024;
    private final Thread owner = Thread.currentThread();
    private final File artifact;
    private final Net network;
    private Spider source;
    private volatile boolean cancelled;

    private JarSession(File artifact, Spider source, Net network) {
        this.artifact = artifact;
        this.source = source;
        this.network = network;
    }

    /** Takes ownership of input. Only the verified private copy is given to Android's loader. */
    public static JarSession open(Context context, InputStream input, String sha256, String className,
            Supplier<Activity> activity, SpiderApi callbacks, String sourceId, String extension) throws SourceFailure {
        Objects.requireNonNull(input);
        File artifact = null;
        Spider source = null;
        Net network = null;
        boolean ready = false;
        try {
            try (InputStream owned = input) {
                if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) throw new SourceFailure("Invalid source digest");
                File directory = new File(context.getCodeCacheDir(), "source-jars");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
                artifact = File.createTempFile("source-", ".jar", directory);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileOutputStream output = new FileOutputStream(artifact)) {
                    // Mark read-only before writing through the already-open descriptor (Android 14 DCL).
                    if (!artifact.setReadOnly()) throw new IOException();
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int count;
                    while ((count = owned.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException();
                        total += count;
                        if (total > MAX_ARTIFACT_BYTES) throw new SourceFailure("Source artifact is too large");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
                if (!MessageDigest.isEqual(hex(digest.digest()).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        sha256.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw new SourceFailure("Source digest mismatch");
                }
            }
            DexClassLoader loader = new DexClassLoader(artifact.getPath(), context.getCodeCacheDir().getPath(),
                    null, Spider.class.getClassLoader());
            Class<? extends Spider> type = loader.loadClass(className).asSubclass(Spider.class);
            // A fixture/source class must come from this artifact, not a matching host class.
            if (type.getClassLoader() != loader) throw new SourceFailure("Source entry must belong to its artifact");
            source = type.getDeclaredConstructor().newInstance();
            network = new Net();
            source.siteKey = sourceId;
            source.net = network;
            source.local = new Local(context, sourceId);
            source.attachHost(Objects.requireNonNull(activity), Objects.requireNonNull(callbacks));
            source.init(context.getApplicationContext(), extension);
            JarSession session = new JarSession(artifact, source, network);
            ready = true;
            return session;
        } catch (Exception | LinkageError | AssertionError failure) {
            if (failure instanceof SourceFailure) throw (SourceFailure) failure;
            throw new SourceFailure("Source initialization failed");
        } finally {
            if (!ready) {
                try {
                    if (source != null) {
                        try { source.destroy(); }
                        catch (RuntimeException | LinkageError | AssertionError ignored) { }
                        finally { source.detachHost(); }
                    }
                } finally {
                    try { if (network != null) network.close(); }
                    finally {
                        if (artifact != null && artifact.exists() && !artifact.delete()) {
                            throw new SourceFailure("Source initialization failed; artifact cleanup failed");
                        }
                    }
                }
            }
        }
    }

    public <T> T call(Invocation<T> operation) throws SourceFailure {
        checkOwner();
        if (source == null) throw new IllegalStateException("Source session is closed");
        if (cancelled) throw new SourceFailure("Source session was cancelled");
        try {
            return operation.run(source);
        } catch (Exception | LinkageError | AssertionError failure) {
            throw new SourceFailure("Source execution failed");
        }
    }

    /** Cancels network I/O and waits from any thread; arbitrary source code cannot be interrupted safely. */
    public void cancel() { cancelled = true; network.close(); }

    @Override public void close() throws SourceFailure {
        checkOwner();
        Spider closing = source;
        source = null;
        try {
            if (closing != null) {
                try {
                    closing.destroy();
                } catch (RuntimeException | LinkageError | AssertionError error) {
                    throw new SourceFailure("Source cleanup failed");
                } finally {
                    closing.detachHost();
                }
            }
        } finally {
            try { network.close(); }
            finally {
                if (artifact.exists() && !artifact.delete()) throw new SourceFailure("Source artifact cleanup failed");
            }
        }
    }

    private void checkOwner() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("Source session belongs to another thread");
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte next : bytes) value.append(String.format(Locale.ROOT, "%02x", next & 255));
        return value.toString();
    }

    @FunctionalInterface public interface Invocation<T> {
        T run(Spider source) throws Exception;
    }

    public static final class SourceFailure extends Exception {
        SourceFailure(String message) { super(message); }
    }
}
