package com.github.catvod.net;

import android.util.Base64;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sogud.bananatv.source.SourceHttp;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import okhttp3.Call;
import okhttp3.Callback;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Buffered JAR-source HTTP. Each instance owns cancellation; the socket pool is shared. */
public final class Net implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private static final OkHttpClient SHARED_CLIENT = new OkHttpClient();
    private static final int MAX_BUFFERED_BYTES = 32 * 1024 * 1024;
    private final Runnable beforeStreamDelivery;
    private final Runnable beforeDownloadPublish;
    private final OkHttpClient client;
    private final SourceHttp.Scope scope = new SourceHttp.Scope();
    private final NetTasks tasks = new NetTasks();
    private final Object sleeper = new Object();
    private final Set<StreamFuture> openings = new HashSet<>();
    private final Set<StreamResult> streams = new HashSet<>();
    private final Set<NetSocket> sockets = new HashSet<>();
    private volatile Cookies cookies;
    private final String sourceId;
    private final Family family;
    private final boolean root;
    private volatile boolean stopped;

    public Net() { this(null, ""); }
    public Net(Cookies cookies) { this(cookies, ""); }
    public Net(Cookies cookies, String sourceId) { this(SHARED_CLIENT, cookies, sourceId, new Family(), null, () -> { }, () -> { }); }

    Net(Runnable beforeStreamDelivery, Runnable beforeDownloadPublish) {
        this(SHARED_CLIENT, null, "", new Family(), null, beforeStreamDelivery, beforeDownloadPublish);
    }

    private Net(OkHttpClient client, Cookies cookies, String sourceId, Family family, Net parent, Runnable beforeStreamDelivery, Runnable beforeDownloadPublish) {
        this.beforeStreamDelivery = beforeStreamDelivery; this.beforeDownloadPublish = beforeDownloadPublish;
        this.client = client;
        this.cookies = cookies;
        this.sourceId = sourceId;
        this.family = family;
        root = parent == null;
        family.add(this, parent);
    }

    public Net session() { return new Net(client, cookies, sourceId, family, this, beforeStreamDelivery, beforeDownloadPublish); }
    public String getCookie(String url) {
        Cookies provider = cookies;
        return provider == null ? "" : provider.getCookie(url);
    }
    public boolean setCookie(String url, String value) {
        Cookies provider = cookies;
        return provider != null && provider.setCookie(url, value);
    }

    public String get(String url) { return text(request(url, new NetOptions())); }
    public String get(String url, int timeout) {
        NetOptions options = new NetOptions(); options.timeout = timeout;
        return text(request(url, options));
    }
    public String get(String url, Map<String, String> headers) {
        NetOptions options = new NetOptions(); options.headers = headers;
        return text(request(url, options));
    }
    public String post(String url, String json) { return post(url, json, Collections.emptyMap()); }
    public String post(String url, String json, Map<String, String> headers) {
        NetOptions options = new NetOptions();
        options.method = "post"; options.data = JsonParser.parseString(json); options.headers = headers;
        return text(request(url, options));
    }

    public String req(String url, String options) {
        NetOptions parsed = NetOptions.from(options);
        return request(url, parsed).json(parsed);
    }
    public Object[] reqParts(String url, String options) {
        NetOptions parsed = NetOptions.from(options);
        return request(url, parsed).parts(parsed);
    }
    public String json(String url, String options) { return JsonParser.parseString(jsonText(url, options)).toString(); }
    public String jsonText(String url, String options) {
        Result result = request(url, NetOptions.from(options));
        String value = text(result);
        if (!(result.code instanceof Integer) || (Integer) result.code < 200 || (Integer) result.code >= 300) {
            throw new IllegalStateException("Source HTTP status is not successful");
        }
        JsonParser.parseString(value);
        return value;
    }

    private Prepared prepare(String url, NetOptions options) throws IOException {
        Objects.requireNonNull(options); scope.check();
        if (options.getTimeout() <= 0 || options.getCallTimeout() < 0) throw new IllegalArgumentException("Invalid request timing");
        HttpUrl address = Objects.requireNonNull(HttpUrl.parse(url));
        HttpUrl.Builder query = address.newBuilder();
        JsonElement params = options.getParams();
        if (params != null && !params.isJsonNull()) {
            for (Map.Entry<String, JsonElement> entry : params.getAsJsonObject().entrySet()) {
                query.addQueryParameter(entry.getKey(), scalar(entry.getValue()));
            }
        }
        Request.Builder request = new Request.Builder().url(query.build());
        for (Map.Entry<String, String> header : options.getHeader().entrySet()) request.header(header.getKey(), header.getValue());
        String method = options.getMethod().toUpperCase(Locale.ROOT);
        RequestBody body = null;
        if (!method.equals("GET") && !method.equals("HEAD")) body = body(options, request);
        request.method(method, body);
        HttpUrl origin = request.build().url();
        OkHttpClient configured = client.newBuilder()
                .connectTimeout(options.getTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(options.getTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(options.getTimeout(), TimeUnit.MILLISECONDS)
                .followRedirects(options.isRedirect()).followSslRedirects(options.isRedirect())
                .addNetworkInterceptor(chain -> routeCookies(chain, origin, options.isCookie())).build();
        long deadline = options.getCallTimeout() > 0 ? options.getCallTimeout() : options.getTimeout();
        return new Prepared(configured, request.build(), deadline);
    }

    private static final class Prepared {
        final OkHttpClient client;
        final Request request;
        final long deadline;
        Prepared(OkHttpClient client, Request request, long deadline) {
            this.client = client; this.request = request; this.deadline = deadline;
        }
    }

    public Result request(String url, NetOptions options) {
        Objects.requireNonNull(options);
        try {
            Prepared prepared = prepare(url, options);
            // Legacy maxBytes applies to downloads, not buffered requests or streams.
            SourceHttp.Reply reply = new SourceHttp(prepared.client).exchange(prepared.request,
                    MAX_BUFFERED_BYTES, prepared.deadline, NetTasks.scopeFor(scope));
            return new Result(reply.status, headers(reply.headers), reply.body, null);
        } catch (IOException | RuntimeException error) { return Result.failure(error); }
    }

    public CompletableFuture<Result> requestAsync(String url, NetOptions options) {
        final Net request;
        try { request = session(); }
        catch (RuntimeException closed) { return CompletableFuture.completedFuture(Result.failure(new InterruptedIOException())); }
        return tasks.submit(() -> request.request(url, options), request::close, Result::failure);
    }

    public void enqueue(String url, NetOptions options, Consumer<Result> callback) {
        Objects.requireNonNull(callback);
        requestAsync(url, options).thenAccept(value -> { if (!stopped) callback.accept(value); });
    }

    public CompletableFuture<StreamResult> requestStreamAsync(String url, NetOptions options) {
        try {
            Prepared prepared = prepare(url, options);
            Call call = prepared.client.newCall(prepared.request);
            call.timeout().timeout(prepared.deadline, TimeUnit.MILLISECONDS);
            StreamFuture future = new StreamFuture(call);
            try {
                synchronized (openings) {
                    scope.attach(call); openings.add(future);
                    call.enqueue(future);
                }
            } catch (IOException | RuntimeException error) { future.fail(); }
            return future;
        } catch (IOException | RuntimeException error) {
            CompletableFuture<StreamResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("Source stream could not be opened")); return failed;
        }
    }

    private final class StreamFuture extends CompletableFuture<StreamResult> implements Callback {
        private final Call call;
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicReference<StreamResult> offered = new AtomicReference<>();
        StreamFuture(Call call) { this.call = call; }
        private void retire() { synchronized (openings) { openings.remove(this); } }
        private void abortTransport() {
            call.cancel(); scope.detach(call);
            StreamResult stream = offered.get(); if (stream != null) stream.close();
            retire();
        }
        @Override public boolean cancel(boolean interrupt) {
            if (!settled.compareAndSet(false, true)) return false;
            abortTransport(); return super.cancel(interrupt);
        }
        void fail() {
            if (!settled.compareAndSet(false, true)) return;
            abortTransport();
            CompletableFuture.runAsync(() -> completeExceptionally(new IOException("Source stream could not be opened")));
        }
        @Override public void onFailure(Call failed, IOException error) { fail(); }
        @Override public void onResponse(Call ready, Response response) {
            StreamResult stream;
            try {
                synchronized (streams) {
                    scope.check();
                    if (response.body() == null) throw new IOException();
                    stream = new StreamResult(call, response); streams.add(stream);
                }
            } catch (IOException | RuntimeException error) { response.close(); fail(); return; }
            offered.set(stream);
            if (settled.get()) { stream.close(); return; }
            CompletableFuture.runAsync(() -> {
                if (settled.compareAndSet(false, true)) {
                    retire(); beforeStreamDelivery.run();
                    if (!complete(stream)) stream.close();
                } else stream.close();
            });
        }
    }

    public final class StreamResult extends InputStream {
        public final int code;
        public final Map<String, Object> headers;
        public final String contentType;
        private final Call call;
        private final Response response;
        private final InputStream input;
        private final long length;
        private final Object reading = new Object();
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean eof;
        StreamResult(Call call, Response response) {
            this.call = call; this.response = response;
            code = response.code(); headers = Net.headers(response.headers());
            contentType = response.header("Content-Type", "application/octet-stream");
            input = response.body().byteStream(); length = response.body().contentLength();
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; int count = read(one, 0, 1); return count < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            Objects.requireNonNull(buffer);
            if (offset < 0 || count < 0 || offset > buffer.length - count) throw new IndexOutOfBoundsException();
            synchronized (reading) {
                if (count == 0) return 0;
                if (eof) return -1;
                if (closed.get()) throw new IOException("Source stream is closed");
                try {
                    scope.check(); int size = input.read(buffer, offset, count); scope.check();
                    if (size == -1) { eof = true; close(); }
                    return size;
                } catch (IOException | RuntimeException error) {
                    close(); throw new IOException("Source stream read failed");
                }
            }
        }
        public CompletableFuture<Integer> readAsync(ByteBuffer destination) {
            return tasks.submit(() -> {
                Objects.requireNonNull(destination);
                if (destination.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
                if (!destination.hasRemaining()) return 0;
                byte[] buffer = new byte[Math.min(destination.remaining(), 8192)];
                int count = read(buffer, 0, buffer.length);
                if (count > 0) destination.put(buffer, 0, count);
                return count;
            }, () -> { }, this::close, null);
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            call.cancel();
            try { response.close(); }
            catch (RuntimeException ignored) { /* Cancellation may race an active Okio read. */ }
            finally {
                scope.detach(call);
                synchronized (streams) { streams.remove(this); }
            }
        }
    }

    public void download(String url, String options, File target) throws IOException {
        NetOptions parsed = NetOptions.from(options);
        if (parsed.getMaxBytes() < 0 || parsed.getSize() < -1) throw new IOException("Invalid source download limits");
        Path temporary = null;
        CompletableFuture<StreamResult> opening = requestStreamAsync(url, parsed);
        try (StreamResult stream = opening.get()) {
            if (stream.code < 200 || stream.code >= 300) throw new IOException("Source download HTTP status is not successful");
            if (stream.length > parsed.getMaxBytes()) throw new IOException("Source download exceeds size limit");
            Path destination = target.toPath().toAbsolutePath();
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), ".source-download-", ".part");
            SourceHttp.Scope operation = NetTasks.operationScope();
            if (operation != null) operation.attach(stream.call);
            try {
                long total = 0;
                try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        long remaining = parsed.getMaxBytes() - total;
                        int limit = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
                        int count = stream.read(buffer, 0, limit);
                        if (count == -1) break;
                        if (count > remaining) throw new IOException("Source download exceeds size limit");
                        output.write(buffer, 0, count); total += count;
                    }
                    if (parsed.getSize() >= 0 && parsed.getSize() != total) throw new IOException("Source download size did not match");
                    output.getFD().sync();
                }
                Path completed = temporary; beforeDownloadPublish.run();
                NetTasks.publish(scope, () -> Files.move(completed, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
                temporary = null;
            } finally { if (operation != null) operation.detach(stream.call); }
        } catch (InterruptedException interrupted) {
            abandon(opening); Thread.currentThread().interrupt(); throw new InterruptedIOException("Source download cancelled");
        } catch (ExecutionException | RuntimeException error) {
            abandon(opening); throw new IOException("Source download failed");
        } catch (IOException error) { throw new IOException("Source download failed"); }
        finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException error) { throw new IOException("Source download cleanup failed"); }
            }
        }
    }

    private static void abandon(CompletableFuture<StreamResult> opening) {
        opening.cancel(true);
        // A header handoff can already be committed while its future is still being delivered.
        opening.thenAccept(StreamResult::close);
    }

    public CompletableFuture<String> downloadAsync(String url, String options, File target) {
        final Net request;
        try { request = session(); }
        catch (RuntimeException closed) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("Source download cancelled")); return failed;
        }
        String path = target.getAbsolutePath();
        return tasks.submit(() -> { request.download(url, options, target); return path; }, request::close, null);
    }

    public interface Mapper<T, R> { R apply(T value, int index) throws Exception; }

    public <T, R> List<R> map(List<T> values, Mapper<T, R> mapper) throws Exception {
        return map(values, mapper, 4);
    }

    public <T, R> List<R> map(List<T> values, Mapper<T, R> mapper, int threads) throws Exception {
        Objects.requireNonNull(values); Objects.requireNonNull(mapper);
        if (threads <= 0) throw new IllegalArgumentException("Invalid map options");
        scope.check();
        if (values.isEmpty()) return Collections.emptyList();
        List<T> input = new ArrayList<>(values);
        // Nested maps must not wait for the same bounded pool they already occupy.
        if (NetTasks.isWorkerThread()) {
            List<R> result = new ArrayList<>(input.size());
            for (int index = 0; index < input.size(); index++) {
                scope.check(); result.add(mapper.apply(input.get(index), index));
            }
            return result;
        }
        Object[] output = new Object[input.size()];
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.BlockingQueue<CompletableFuture<Void>> completed = new java.util.concurrent.LinkedBlockingQueue<>();
        List<CompletableFuture<Void>> workers = new ArrayList<>();
        int count = Math.min(input.size(), Math.max(1, Math.min(threads, 8)));
        try {
            for (int worker = 0; worker < count; worker++) {
                CompletableFuture<Void> future = tasks.submit(() -> {
                    while (true) {
                        scope.check();
                        int index = next.getAndIncrement();
                        if (index >= input.size()) return null;
                        output[index] = mapper.apply(input.get(index), index);
                    }
                }, () -> { }, null);
                workers.add(future);
                future.whenComplete((value, error) -> completed.add(future));
            }
            for (int worker = 0; worker < count; worker++) completed.take().get();
            List<R> result = new ArrayList<>(output.length);
            for (Object value : output) {
                @SuppressWarnings("unchecked") R mapped = (R) value;
                result.add(mapped);
            }
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Source operation cancelled");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Source mapping failed");
        } finally {
            for (CompletableFuture<Void> worker : workers) worker.cancel(true);
        }
    }

    public Result[] batch(String requests) throws IOException { return batch(requests, 4); }
    public Result[] batch(String requests, int threads) throws IOException {
        try {
            List<JsonElement> entries = new ArrayList<>();
            for (JsonElement entry : JSON.fromJson(requests, com.google.gson.JsonArray.class)) entries.add(entry);
            List<Result> results = map(entries, (entry, index) -> {
                com.google.gson.JsonObject item = entry.getAsJsonObject();
                NetOptions options = NetOptions.from(item.has("options") ? item.get("options").toString() : "{}");
                return request(item.get("url").getAsString(), options);
            }, threads);
            return results.toArray(new Result[0]);
        } catch (IOException error) { throw error; }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IOException("Source batch failed"); }
    }

    public Object[][] batchParts(String requests, int threads) throws IOException {
        Result[] results = batch(requests, threads);
        com.google.gson.JsonArray entries = JSON.fromJson(requests, com.google.gson.JsonArray.class);
        Object[][] parts = new Object[results.length][];
        for (int index = 0; index < results.length; index++) {
            com.google.gson.JsonObject item = entries.get(index).getAsJsonObject();
            NetOptions options = NetOptions.from(item.has("options") ? item.get("options").toString() : "{}");
            parts[index] = results[index].parts(options);
        }
        return parts;
    }

    static Map<String, Object> headers(okhttp3.Headers headers) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : headers.names()) {
            List<String> values = headers.values(name);
            result.put(name.toLowerCase(Locale.ROOT), values.size() == 1 ? values.get(0)
                    : Collections.unmodifiableList(new ArrayList<>(values)));
        }
        return Collections.unmodifiableMap(result);
    }

    public NetSocket connect(String url, String options, NetSocket.Listener listener) throws IOException {
        return connect(url, NetOptions.from(options), listener, ignored -> { });
    }

    private NetSocket connect(String url, NetOptions options, NetSocket.Listener listener, Consumer<String> ended) throws IOException {
        scope.check();
        if (options.getTimeout() <= 0 || options.getIdle() < 0 || options.getPing() < 0) throw new IllegalArgumentException("Invalid WebSocket timing");
        Request.Builder request = new Request.Builder().url(url);
        for (Map.Entry<String, String> header : options.getHeader().entrySet()) request.header(header.getKey(), header.getValue());
        Request initial = request.build();
        String explicitCookie = initial.header("Cookie");
        request.removeHeader("Cookie");
        OkHttpClient configured = client.newBuilder().connectTimeout(options.getTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(options.getTimeout(), TimeUnit.MILLISECONDS).pingInterval(options.getPing(), TimeUnit.MILLISECONDS)
                .followRedirects(options.isRedirect()).followSslRedirects(options.isRedirect())
                .cookieJar(socketCookies(initial.url(), explicitCookie, options.isCookie())).build();
        NetSocket socket = new NetSocket(configured, request.build(), options, listener,
                closed -> { synchronized (sockets) { sockets.remove(closed); } }, ended, () -> !stopped);
        synchronized (sockets) { scope.check(); sockets.add(socket); }
        try { socket.start(); return socket; }
        catch (IOException | RuntimeException | LinkageError failure) { socket.cancel(); throw failure; }
    }

    private CookieJar socketCookies(HttpUrl origin, String explicit, boolean enabled) {
        return new CookieJar() {
            @Override public List<Cookie> loadForRequest(HttpUrl address) {
                boolean sameOrigin = origin.scheme().equals(address.scheme()) && origin.host().equals(address.host())
                        && origin.port() == address.port();
                String value = sameOrigin ? explicit : null;
                if (value == null && enabled) value = getCookie(address.toString());
                List<Cookie> result = new ArrayList<>();
                if (value == null || value.isEmpty()) return result;
                for (String part : value.split(";")) {
                    int separator = part.indexOf('=');
                    if (separator <= 0) continue;
                    result.add(new Cookie.Builder().hostOnlyDomain(address.host()).path("/")
                            .name(part.substring(0, separator).trim()).value(part.substring(separator + 1).trim()).build());
                }
                return result;
            }
            @Override public void saveFromResponse(HttpUrl address, List<Cookie> cookies) {
                if (!enabled || cookies.isEmpty()) return;
                List<String> values = new ArrayList<>();
                for (Cookie cookie : cookies) values.add(cookie.toString());
                setCookie(address.toString(), String.join("; ", values));
            }
        };
    }

    public CompletableFuture<Result> socketAsync(String url, NetOptions options) {
        AtomicReference<NetSocket> active = new AtomicReference<>();
        AtomicBoolean settled = new AtomicBoolean();
        CompletableFuture<Result> result = new CompletableFuture<Result>() {
            @Override public boolean cancel(boolean interrupt) {
                settled.set(true);
                NetSocket socket = active.get(); if (socket != null) socket.cancel();
                return super.cancel(interrupt);
            }
        };
        Consumer<Result> complete = value -> {
            if (settled.compareAndSet(false, true)) {
                NetSocket socket = active.get(); if (socket != null) socket.cancel();
                CompletableFuture.runAsync(() -> result.complete(value));
            }
        };
        long timeout = options.getCallTimeout() > 0 ? options.getCallTimeout() : options.getTimeout();
        if (timeout <= 0) { result.complete(Result.failure(new IllegalArgumentException())); return result; }
        ScheduledFuture<?> deadline = NetSocket.schedule(() -> complete.accept(Result.failure(new java.net.SocketTimeoutException())), timeout);
        result.whenComplete((value, failure) -> {
            settled.set(true); deadline.cancel(false);
            NetSocket socket = active.get(); if (socket != null) socket.cancel();
        });
        try {
            NetSocket socket = connect(url, options, (owner, event) -> {
                if (event.type.equals("message")) {
                    byte[] bytes = event.content instanceof byte[] ? (byte[]) event.content
                            : ((String) event.content).getBytes(StandardCharsets.UTF_8);
                    complete.accept(new Result(owner.responseCode(), owner.responseHeaders(), bytes, null));
                } else if (event.type.equals("error")) {
                    complete.accept(new Result(event.code > 0 ? event.code : "", owner.responseHeaders(), new byte[0], event.error));
                }
            }, ignored -> complete.accept(Result.failure(new IOException())));
            active.set(socket);
            if (settled.get()) socket.cancel();
        } catch (IOException | RuntimeException error) { complete.accept(Result.failure(error)); }
        return result;
    }

    public void webSocket(String url, NetOptions options, Consumer<Result> callback) {
        socketAsync(url, options).thenAccept(value -> { if (!stopped) callback.accept(value); });
    }
    public String ws(String url, String options) {
        NetOptions parsed = NetOptions.from(options); return awaitSocket(url, parsed).json(parsed);
    }
    public Object[] wsParts(String url, String options) {
        NetOptions parsed = NetOptions.from(options); return awaitSocket(url, parsed).parts(parsed);
    }
    private Result awaitSocket(String url, NetOptions options) {
        CompletableFuture<Result> result = socketAsync(url, options);
        try { return result.get(); }
        catch (InterruptedException interrupted) {
            result.cancel(true); Thread.currentThread().interrupt(); return Result.failure(new InterruptedIOException());
        } catch (ExecutionException failed) { return Result.failure(new IOException()); }
    }

    private Response routeCookies(Interceptor.Chain chain, HttpUrl origin, boolean enabled) throws IOException {
        Request request = chain.request();
        HttpUrl address = request.url();
        boolean sameOrigin = origin.scheme().equals(address.scheme()) && origin.host().equals(address.host())
                && origin.port() == address.port();
        Request.Builder routed = request.newBuilder();
        if (!sameOrigin) routed.removeHeader("Cookie");
        if (enabled && (!sameOrigin || request.header("Cookie") == null)) {
            String saved = getCookie(address.toString());
            if (saved != null && !saved.isEmpty()) routed.header("Cookie", saved);
        }
        Response response = chain.proceed(routed.build());
        boolean delivered = false;
        try {
            if (enabled) {
                List<String> values = response.headers().values("Set-Cookie");
                if (!values.isEmpty()) setCookie(address.toString(), String.join("; ", values));
            }
            delivered = true;
            return response;
        } finally {
            if (!delivered) response.close();
        }
    }

    private static RequestBody body(NetOptions options, Request.Builder request) {
        String type = request.build().header("Content-Type");
        if (options.getBody() != null) {
            return RequestBody.create(options.getBody(), MediaType.parse(type == null ? "text/plain; charset=utf-8" : type));
        }
        JsonElement data = options.getData();
        if ("form".equals(options.getPostType())) {
            FormBody.Builder form = new FormBody.Builder();
            if (data != null && !data.isJsonNull()) {
                for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject().entrySet()) {
                    form.add(entry.getKey(), scalar(entry.getValue()));
                }
            }
            return form.build();
        }
        return RequestBody.create(data == null ? "" : data.toString(),
                MediaType.parse(type == null ? "application/json; charset=utf-8" : type));
    }

    private static String scalar(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }
    private static String text(Result result) {
        if (result.error != null) throw new IllegalStateException(result.error);
        return result.text();
    }

    public void sleep(long millis) throws InterruptedIOException {
        if (millis < 0) throw new IllegalArgumentException("Negative sleep duration");
        long remaining = TimeUnit.MILLISECONDS.toNanos(millis);
        long started = System.nanoTime();
        synchronized (sleeper) {
            while (true) {
                scope.check();
                if (remaining <= 0) return;
                try { TimeUnit.NANOSECONDS.timedWait(sleeper, remaining); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Source request cancelled");
                }
                remaining = TimeUnit.MILLISECONDS.toNanos(millis) - (System.nanoTime() - started);
            }
        }
    }

    @Override public void close() {
        if (root) family.close();
        else { family.remove(this); stop(); }
    }

    private void stop() {
        stopped = true;
        scope.close(); tasks.close(); cookies = null;
        List<StreamFuture> pending;
        synchronized (openings) { pending = new ArrayList<>(openings); openings.clear(); }
        for (StreamFuture future : pending) future.fail();
        List<StreamResult> transfers;
        synchronized (streams) { transfers = new ArrayList<>(streams); streams.clear(); }
        for (StreamResult stream : transfers) stream.close();
        List<NetSocket> active;
        synchronized (sockets) { active = new ArrayList<>(sockets); sockets.clear(); }
        for (NetSocket socket : active) socket.cancel();
        synchronized (sleeper) { sleeper.notifyAll(); }
    }

    /** A source owns every fork; an individual caller closes only its own fork. */
    private static final class Family {
        private final Set<Net> members = new HashSet<>();
        private boolean closed;

        synchronized void add(Net child, Net parent) {
            if (closed || (parent != null && !members.contains(parent))) {
                throw new IllegalStateException("Source network session is closed");
            }
            members.add(child);
        }
        synchronized void remove(Net child) { members.remove(child); }
        void close() {
            List<Net> closing;
            synchronized (this) {
                if (closed) return;
                closed = true;
                closing = new ArrayList<>(members);
                members.clear();
            }
            for (Net member : closing) member.stop();
        }
    }

    public interface Cookies {
        String getCookie(String url);
        boolean setCookie(String url, String value);
    }

    public static final class Result {
        public final Object code;
        public final Map<String, Object> headers;
        public final byte[] body;
        public final String error;

        private Result(Object code, Map<String, Object> headers, byte[] body, String error) {
            this.code = code; this.headers = Collections.unmodifiableMap(headers); this.body = body; this.error = error;
        }
        public static Result failure(Throwable failure) {
            String error = failure instanceof InterruptedIOException ? "InterruptedIOException: Canceled"
                    : failure instanceof IOException ? "IOException: Network request failed" : "IllegalArgumentException: Invalid request";
            return new Result("", Collections.emptyMap(), new byte[0], error);
        }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
        public String json(NetOptions options) {
            Map<String, Object> value = metadata(); value.put("content", content(options)); return JSON.toJson(value);
        }
        public Object[] parts(NetOptions options) { return new Object[] {JSON.toJson(metadata()), content(options)}; }
        private Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>(); result.put("code", code); result.put("headers", headers);
            if (error != null) result.put("error", error);
            return result;
        }
        private Object content(NetOptions options) {
            if (options.getBuffer() == 1) return body.clone();
            if (options.getBuffer() == 2) return Base64.encodeToString(body, Base64.NO_WRAP);
            if (options.getBuffer() != 0) throw new IllegalArgumentException("Unsupported response buffer mode");
            return new String(body, Charset.forName(options.getCharset()));
        }
    }
}
