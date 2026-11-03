package com.github.catvod.net;

import android.content.Context;
import com.sogud.bananatv.source.SourceHttp;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;

/** Real sockets and disposable file transactions, never private media or user storage. */
public final class StreamProbe {
    public static void run(Context context, Consumer<String> passed) throws Exception {
        File directory = new File(context.getCacheDir(), "source-stream-probe-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new AssertionError("Cannot create disposable directory");
        try {
            streams(passed);
            largeStream(passed);
            downloads(directory, passed);
            interruptedHandoff(directory, passed);
            cancelledPublication(directory, passed);
            completedPublication(directory, passed);
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) if (!file.delete()) throw new AssertionError("Fixture cleanup failed");
            if (!directory.delete()) throw new AssertionError("Fixture directory cleanup failed");
        }
    }

    private static void streams(Consumer<String> passed) throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            server.enqueue(new MockResponse().setBody("delayed").setBodyDelay(3, TimeUnit.SECONDS));
            Net.StreamResult stream = net.requestStreamAsync(server.url("/headers-first").toString(), NetOptions.from("{}"))
                    .get(1, TimeUnit.SECONDS);
            require(stream.code == 200, "stream_headers_arrive_before_delayed_body", passed);
            CompletableFuture<Integer> reading = stream.readAsync(ByteBuffer.allocate(16));
            require(reading.cancel(true) && call(stream).isCanceled(), "cancelled_async_read_closes_its_socket", passed);
            server.enqueue(new MockResponse().setBody("parent-alive"));
            require(net.get(server.url("/parent").toString()).equals("parent-alive"), "stream_cancel_preserves_parent_client", passed);
            server.enqueue(new MockResponse().setBody("direct"));
            try (Net.StreamResult direct = net.requestStreamAsync(server.url("/direct").toString(), NetOptions.from("{}")).get(1, TimeUnit.SECONDS)) {
                require(direct.readAsync(ByteBuffer.allocate(0)).get(1, TimeUnit.SECONDS) == 0,
                        "zero_remaining_buffer_does_not_consume_stream", passed);
                boolean readOnly = false;
                try { direct.readAsync(ByteBuffer.allocate(8).asReadOnlyBuffer()).get(1, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException expected) { readOnly = expected.getCause() instanceof java.nio.ReadOnlyBufferException; }
                ByteBuffer buffer = ByteBuffer.allocateDirect(16); int count = direct.readAsync(buffer).get(1, TimeUnit.SECONDS);
                buffer.flip(); byte[] body = new byte[buffer.remaining()]; buffer.get(body);
                require(readOnly && count == 6 && new String(body, StandardCharsets.UTF_8).equals("direct"),
                        "direct_buffer_reads_and_readonly_rejection_preserve_bytes", passed);
            }
            server.enqueue(new MockResponse().setBody("timeout").setBodyDelay(3, TimeUnit.SECONDS));
            try (Net.StreamResult timed = net.requestStreamAsync(server.url("/private-fixture-token").toString(),
                    NetOptions.from("{\"callTimeout\":1000}")).get(1, TimeUnit.SECONDS)) {
                boolean expired = false;
                try { timed.readAsync(ByteBuffer.allocate(8)).get(2, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException failure) {
                    expired = failure.getCause() instanceof IOException && !failure.getCause().toString().contains("private-fixture-token");
                }
                require(expired && call(timed).isCanceled(), "stream_deadline_covers_body_and_redacts_url", passed);
            }
            server.enqueue(new MockResponse().setBody("owned"));
            Net.StreamResult owned = net.requestStreamAsync(server.url("/owned").toString(), NetOptions.from("{}")).get(1, TimeUnit.SECONDS);
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            CompletableFuture<Net.StreamResult> pending = net.requestStreamAsync(server.url("/pending").toString(), NetOptions.from("{}"));
            net.close();
            boolean pendingFailed = false, deliveredClosed = false;
            try { pending.get(1, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException expected) { pendingFailed = expected.getCause() instanceof IOException; }
            try { owned.read(); } catch (IOException expected) { deliveredClosed = true; }
            require(pendingFailed && deliveredClosed && call(owned).isCanceled(), "source_close_finishes_pending_and_delivered_streams", passed);
        }
    }

    private static void largeStream(Consumer<String> passed) throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start(); byte[] block = new byte[8192]; Arrays.fill(block, (byte) 61);
            Buffer body = new Buffer(); int blocks = 33 * 128;
            for (int index = 0; index < blocks; index++) body.write(block);
            server.enqueue(new MockResponse().setBody(body));
            long total = 0; boolean valid = true;
            try (Net.StreamResult stream = net.requestStreamAsync(server.url("/large").toString(), NetOptions.from("{}"))
                    .get(2, TimeUnit.SECONDS)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = stream.read(buffer, 0, buffer.length)) != -1;) {
                    total += count;
                    for (int index = 0; index < count; index++) if (buffer[index] != 61) valid = false;
                }
            }
            require(valid && total == 33L * 1024 * 1024, "stream_reads_more_than_buffered_limit_with_fixed_size_caller_buffer", passed);
        }
    }

    private static void downloads(File directory, Consumer<String> passed) throws Exception {
        File target = new File(directory, "download.bin"); Files.write(target.toPath(), bytes("original"));
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            server.enqueue(new MockResponse().setBody("truncated-body").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
            boolean failed = false;
            try { net.download(server.url("/truncated").toString(), "{}", target); } catch (IOException expected) { failed = true; }
            require(failed && text(target).equals("original") && noPartial(directory), "truncated_download_preserves_target_and_removes_partial", passed);
            server.enqueue(new MockResponse().setChunkedBody("too-large", 2));
            failed = false;
            try { net.download(server.url("/chunk-limit").toString(), "{\"maxBytes\":3}", target); } catch (IOException expected) { failed = true; }
            require(failed && text(target).equals("original") && noPartial(directory), "chunked_download_limit_preserves_target", passed);
            server.enqueue(new MockResponse().setBody("slow-body").setBodyDelay(3, TimeUnit.SECONDS));
            CompletableFuture<String> future = net.downloadAsync(server.url("/cancel-download").toString(), "{}", target);
            await(() -> !noPartial(directory));
            require(future.cancel(true), "download_can_cancel_after_partial_file_is_created", passed);
            await(() -> noPartial(directory));
            require(text(target).equals("original"), "cancelled_download_keeps_original_target", passed);
            server.enqueue(new MockResponse().setBody("verified"));
            net.download(server.url("/verified").toString(), "{\"size\":8,\"maxBytes\":8}", target);
            require(text(target).equals("verified") && noPartial(directory), "exact_size_download_atomically_replaces_target", passed);
            File invalid = new File(directory, "missing-parent/illegal.bin");
            Files.write(new File(directory, "missing-parent").toPath(), bytes("not-directory"));
            server.enqueue(new MockResponse().setBody("cannot-save"));
            failed = false;
            try { net.download(server.url("/path-redaction").toString(), "{}", invalid); }
            catch (IOException expected) { failed = !expected.toString().contains(directory.getAbsolutePath()); }
            require(failed && noPartial(directory), "download_filesystem_failure_redacts_private_path", passed);
        }
    }

    private static void interruptedHandoff(File directory, Consumer<String> passed) throws Exception {
        CountDownLatch claimed = new CountDownLatch(1), deliver = new CountDownLatch(1);
        File target = new File(directory, "handoff.bin"); Files.write(target.toPath(), bytes("original"));
        try (MockWebServer server = new MockWebServer(); Net net = new Net(() -> { claimed.countDown(); uninterruptible(deliver); }, () -> { })) {
            server.start(); server.enqueue(new MockResponse().setBody("abandoned"));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread waiter = new Thread(() -> { try { net.download(server.url("/handoff").toString(), "{}", target); }
                catch (Throwable error) { failure.set(error); } });
            waiter.start();
            try {
                if (!claimed.await(2, TimeUnit.SECONDS)) throw new AssertionError("No claimed handoff");
                Net.StreamResult offered = offered(net); Call call = call(offered);
                waiter.interrupt(); waiter.join(1000);
                require(!waiter.isAlive() && failure.get() instanceof java.io.InterruptedIOException,
                        "interrupted_download_waiter_returns_during_claimed_handoff", passed);
                deliver.countDown(); await(call::isCanceled);
                require(text(target).equals("original") && noPartial(directory), "abandoned_late_stream_is_closed_without_replacing_target", passed);
            } finally { deliver.countDown(); waiter.interrupt(); waiter.join(2000); }
        }
    }

    private static void cancelledPublication(File directory, Consumer<String> passed) throws Exception {
        CountDownLatch prepared = new CountDownLatch(1), publish = new CountDownLatch(1);
        File target = new File(directory, "cancel-publish.bin"); Files.write(target.toPath(), bytes("original"));
        try (MockWebServer server = new MockWebServer(); Net net = new Net(() -> { }, () -> { prepared.countDown(); uninterruptible(publish); })) {
            server.start(); server.enqueue(new MockResponse().setBody("replacement"));
            CompletableFuture<String> result = net.downloadAsync(server.url("/publish").toString(), "{}", target);
            try {
                if (!prepared.await(2, TimeUnit.SECONDS)) throw new AssertionError("Download not prepared");
                require(result.cancel(true), "download_cancellation_wins_before_publication", passed);
                publish.countDown(); await(() -> noPartial(directory));
                require(text(target).equals("original"), "cancellation_winning_publish_race_cannot_replace_target", passed);
            } finally { publish.countDown(); }
        }
    }

    private static void completedPublication(File directory, Consumer<String> passed) throws Exception {
        CountDownLatch inside = new CountDownLatch(1), finish = new CountDownLatch(1);
        File target = new File(directory, "committed.bin"), temporary = new File(directory, "commit-temp.bin");
        Files.write(target.toPath(), bytes("original")); Files.write(temporary.toPath(), bytes("committed"));
        try (NetTasks tasks = new NetTasks(); SourceHttp.Scope owner = new SourceHttp.Scope()) {
            CompletableFuture<String> result = tasks.submit(() -> {
                NetTasks.publish(owner, () -> { inside.countDown(); uninterruptible(finish);
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); });
                return "committed";
            }, () -> { }, null);
            AtomicBoolean cancelled = new AtomicBoolean(true);
            Thread cancel = new Thread(() -> cancelled.set(result.cancel(true)));
            try {
                if (!inside.await(2, TimeUnit.SECONDS)) throw new AssertionError("Commit not entered");
                cancel.start(); await(() -> cancel.getState() == Thread.State.BLOCKED);
                finish.countDown(); cancel.join(2000);
                require(!cancelled.get() && result.get(2, TimeUnit.SECONDS).equals("committed") && text(target).equals("committed"),
                        "completed_atomic_publication_reports_success_instead_of_false_cancellation", passed);
            } finally { finish.countDown(); cancel.join(2000); }
        }
    }

    private static Net.StreamResult offered(Net net) throws Exception {
        java.lang.reflect.Field field = Net.class.getDeclaredField("streams"); field.setAccessible(true);
        java.util.Set<?> streams = (java.util.Set<?>) field.get(net);
        synchronized (streams) { return (Net.StreamResult) streams.iterator().next(); }
    }
    private static Call call(Net.StreamResult stream) throws Exception {
        java.lang.reflect.Field field = Net.StreamResult.class.getDeclaredField("call"); field.setAccessible(true); return (Call) field.get(stream);
    }
    private static void uninterruptible(CountDownLatch latch) {
        boolean complete = false;
        while (!complete) { try { latch.await(); complete = true; } catch (InterruptedException ignored) { } }
    }
    private static boolean noPartial(File directory) {
        File[] files = directory.listFiles((parent, name) -> name.startsWith(".source-download-"));
        return files != null && files.length == 0;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) { if (System.nanoTime() >= deadline) throw new AssertionError("Expected state was not reached"); Thread.sleep(10); }
    }
    private static void require(boolean value, String label, Consumer<String> passed) {
        if (!value) throw new AssertionError(label); passed.accept(label);
    }
}
