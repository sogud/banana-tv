package com.sogud.bananatv.source.contract;

import android.content.Context;
import com.github.catvod.net.Net;
import com.github.catvod.net.NetOptions;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;

/** Original stream/download observations with disposable files and loopback responses. */
public final class StreamContractFixture {
    private interface Action { Object run() throws Exception; }
    private static Object observe(Action action) throws Exception {
        try { return action.run(); }
        catch (Exception error) {
            Throwable cause = error;
            while (cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null) cause = cause.getCause();
            return new JSONObject().put("failure", cause.getClass().getSimpleName());
        }
    }
    private static String read(Net.StreamResult stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[3];
        for (int count; (count = stream.read(buffer, 0, buffer.length)) != -1;) output.write(buffer, 0, count);
        return output.toString("UTF-8");
    }
    public static JSONObject run(Context context) throws Exception {
        File directory = new File(context.getCacheDir(), "stream-contract-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new AssertionError("Cannot create disposable directory");
        JSONObject report = new JSONObject();
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            server.enqueue(new MockResponse().setBody("stream-影视").setHeader("Content-Type", "text/plain; charset=utf-8"));
            try (Net.StreamResult stream = net.requestStreamAsync(server.url("/stream").toString(), NetOptions.from("{}")).get(3, TimeUnit.SECONDS)) {
                report.put("stream", new JSONObject().put("code", stream.code).put("headers", new JSONObject(stream.headers))
                        .put("contentType", stream.contentType).put("body", read(stream)));
            }
            server.enqueue(new MockResponse().setBody("bytes"));
            try (Net.StreamResult stream = net.requestStreamAsync(server.url("/bytes").toString(), NetOptions.from("{}")).get(3, TimeUnit.SECONDS)) {
                ByteBuffer buffer = ByteBuffer.allocate(16);
                int count = stream.readAsync(buffer).get(3, TimeUnit.SECONDS);
                report.put("readAsync", new JSONObject().put("count", count).put("position", buffer.position())
                        .put("body", new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8))
                        .put("eof", stream.readAsync(ByteBuffer.allocateDirect(2)).get(3, TimeUnit.SECONDS)));
            }
            server.enqueue(new MockResponse().setBody("missing").setResponseCode(404));
            report.put("status", observe(() -> {
                try (Net.StreamResult stream = net.requestStreamAsync(server.url("/missing").toString(), NetOptions.from("{}")).get(3, TimeUnit.SECONDS)) {
                    return new JSONObject().put("code", stream.code).put("body", read(stream));
                }
            }));
            server.enqueue(new MockResponse().setBody("oversized"));
            report.put("maxBytes", observe(() -> {
                try (Net.StreamResult stream = net.requestStreamAsync(server.url("/limit").toString(), NetOptions.from("{\"maxBytes\":3}")).get(3, TimeUnit.SECONDS)) { return read(stream); }
            }));
            server.enqueue(new MockResponse().setChunkedBody("oversized", 2));
            report.put("chunkedMaxBytes", observe(() -> {
                try (Net.StreamResult stream = net.requestStreamAsync(server.url("/chunked").toString(), NetOptions.from("{\"maxBytes\":3}")).get(3, TimeUnit.SECONDS)) { return read(stream); }
            }));
            server.enqueue(new MockResponse().setBody("closed"));
            Net.StreamResult closed = net.requestStreamAsync(server.url("/close").toString(), NetOptions.from("{}")).get(3, TimeUnit.SECONDS);
            closed.close(); report.put("afterClose", observe(closed::read));
            File target = new File(directory, "download.bin");
            server.enqueue(new MockResponse().setBody("download-影视"));
            net.download(server.url("/download").toString(), "{}", target);
            report.put("download", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
            server.enqueue(new MockResponse().setBody("async-download"));
            String result = net.downloadAsync(server.url("/async-download").toString(), "{}", target).get(3, TimeUnit.SECONDS);
            report.put("downloadAsync", new JSONObject().put("pathMatches", target.getAbsolutePath().equals(result))
                    .put("body", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)));
            server.enqueue(new MockResponse().setBody("not-found").setResponseCode(404));
            report.put("downloadStatus", observe(() -> { net.download(server.url("/download-error").toString(), "{}", target); return "success"; }));
            report.put("afterDownloadFailure", target.exists() ? new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8) : "absent");
            server.enqueue(new MockResponse().setBody("oversized"));
            report.put("downloadLimit", observe(() -> { net.download(server.url("/download-limit").toString(), "{\"maxBytes\":3}", target); return "success"; }));
            report.put("afterDownloadLimit", target.exists() ? new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8) : "absent");
            server.enqueue(new MockResponse().setBody("size"));
            report.put("sizeHint", observe(() -> { net.download(server.url("/size").toString(), "{\"size\":100}", target); return Files.size(target.toPath()); }));
        } finally {
            File[] files = directory.listFiles(); if (files != null) for (File file : files) if (!file.delete()) throw new AssertionError("Disposable file cleanup failed");
            if (!directory.delete()) throw new AssertionError("Disposable directory cleanup failed");
        }
        return report;
    }
}
