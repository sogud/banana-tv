package com.sogud.bananatv.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded source downloads with one reusable connection pool and caller-owned cancellation. */
public final class SourceHttp {
    private final OkHttpClient client;

    public SourceHttp(OkHttpClient client) {
        this.client = java.util.Objects.requireNonNull(client);
    }

    public byte[] download(Request request, int maximum, long timeoutMillis, Scope scope) throws IOException {
        return execute(request, maximum, timeoutMillis, scope, true).body;
    }

    /** Script adapters may need an error page's status, headers and body to handle it themselves. */
    public Reply exchange(Request request, int maximum, long timeoutMillis, Scope scope) throws IOException {
        return execute(request, maximum, timeoutMillis, scope, false);
    }

    private Reply execute(Request request, int maximum, long timeoutMillis, Scope scope, boolean requireSuccess) throws IOException {
        if (maximum <= 0 || timeoutMillis <= 0) throw new IllegalArgumentException("Positive download limits required");
        scope.check();
        Call call = client.newCall(request);
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS);
        scope.attach(call);
        try (Response response = call.execute()) {
            scope.check();
            if (requireSuccess && !response.isSuccessful()) throw new HttpFailure(response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Source response has no body");
            if (body.contentLength() > maximum) throw new SizeLimit();
            try (InputStream input = body.byteStream()) {
                return new Reply(response.code(), response.headers(), read(input, maximum, scope));
            }
        } finally {
            scope.detach(call);
        }
    }

    public static final class Reply {
        public final int status;
        public final okhttp3.Headers headers;
        public final byte[] body;

        private Reply(int status, okhttp3.Headers headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    private static byte[] read(InputStream input, int maximum, Scope scope) throws IOException {
        byte[] buffer = new byte[Math.min(8192, maximum)];
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(buffer.length)) {
            while (true) {
                scope.check();
                // At most one byte beyond the limit is needed to reject a chunked response.
                int length = (int) Math.min(buffer.length, (long) maximum - output.size() + 1);
                int count = input.read(buffer, 0, length);
                if (count < 0) break;
                if (output.size() + (long) count > maximum) throw new SizeLimit();
                output.write(buffer, 0, count);
            }
            scope.check();
            return output.toByteArray();
        }
    }

    public static final class HttpFailure extends IOException {
        public final int statusCode;
        HttpFailure(int statusCode) {
            super("Source HTTP status " + statusCode);
            this.statusCode = statusCode;
        }
    }

    public static final class SizeLimit extends IOException {
        SizeLimit() { super("Source response exceeds download limit"); }
    }

    @FunctionalInterface
    public interface IoAction { void run() throws IOException; }

    /** A page owns a scope. Closing it aborts every in-flight socket without affecting other pages. */
    public static class Scope implements AutoCloseable {
        private final List<Call> calls = new ArrayList<>();
        private boolean cancelled;

        public synchronized void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Source request cancelled");
        }

        /** Complete a short file publication before cancellation, or reject it if cancellation won. */
        public synchronized void runIfOpen(IoAction action) throws IOException {
            check(); action.run();
        }

        public synchronized void attach(Call call) throws InterruptedIOException {
            try {
                check();
                calls.add(call);
            } catch (InterruptedIOException error) {
                call.cancel();
                throw error;
            }
        }

        public synchronized void detach(Call call) { calls.remove(call); }

        public void cancel() {
            List<Call> pending;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                pending = new ArrayList<>(calls);
                calls.clear();
            }
            for (Call call : pending) call.cancel();
        }

        @Override public void close() { cancel(); }
    }
}
