package com.sogud.bananatv.source;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SourceHttpTest {
    private MockWebServer server;
    private OkHttpClient client;
    private SourceHttp http;
    private ExecutorService workers;

    @Before public void start() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient();
        http = new SourceHttp(client);
        workers = Executors.newFixedThreadPool(2);
    }

    @After public void stop() throws Exception {
        workers.shutdownNow();
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
        server.shutdown();
    }

    private Request request() { return new Request.Builder().url(server.url("/source?token=private-fixture")).build(); }

    @Test public void binaryBodySurvivesAtExactLimit() throws Exception {
        byte[] expected = new byte[]{0, (byte) 255, 10, 13};
        server.enqueue(new MockResponse().setBody(new Buffer().write(expected)));
        assertArrayEquals(expected, http.download(request(), 4, 1000, new SourceHttp.Scope()));
    }

    @Test public void chunkedBodySurvivesAtExactLimit() throws Exception {
        server.enqueue(new MockResponse().setChunkedBody("abcd", 1));
        assertEquals("abcd", new String(http.download(request(), 4, 1000, new SourceHttp.Scope()), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void declaredOversizeIsRejectedBeforeBodyArrives() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Length", "1000000"));
        assertThrows(SourceHttp.SizeLimit.class, () -> http.download(request(), 4, 1000, new SourceHttp.Scope()));
    }

    @Test public void chunkedOversizeIsRejected() throws Exception {
        server.enqueue(new MockResponse().setChunkedBody("abcde", 1));
        assertThrows(SourceHttp.SizeLimit.class, () -> http.download(request(), 4, 1000, new SourceHttp.Scope()));
    }

    @Test public void httpFailureExposesStatusWithoutPrivateUrl() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403));
        SourceHttp.HttpFailure failure = assertThrows(SourceHttp.HttpFailure.class,
                () -> http.download(request(), 128, 1000, new SourceHttp.Scope()));
        assertEquals(403, failure.statusCode);
        assertFalse(failure.getMessage().contains("private-fixture"));
        assertFalse(failure.getMessage().contains("token"));
    }

    @Test public void stalledResponseHonorsCallDeadline() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        assertThrows(InterruptedIOException.class, () -> http.download(request(), 128, 100, new SourceHttp.Scope()));
    }

    @Test public void exchangePreservesErrorStatusHeadersAndBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).addHeader("Set-Cookie", "first=1")
                .addHeader("Set-Cookie", "second=2").setBody("challenge"));
        SourceHttp.Reply reply = http.exchange(request(), 128, 1000, new SourceHttp.Scope());
        assertEquals(403, reply.status);
        assertEquals(2, reply.headers.values("Set-Cookie").size());
        assertEquals("challenge", new String(reply.body, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void exchangeStillBoundsErrorBody() {
        server.enqueue(new MockResponse().setResponseCode(500).setChunkedBody("oversized", 1));
        assertThrows(SourceHttp.SizeLimit.class, () -> http.exchange(request(), 4, 1000, new SourceHttp.Scope()));
    }

    @Test public void cancelledScopeDoesNotSendAnotherRequest() {
        SourceHttp.Scope scope = new SourceHttp.Scope();
        scope.cancel(); scope.close();
        assertThrows(InterruptedIOException.class, () -> http.download(request(), 128, 1000, scope));
        assertEquals(0, server.getRequestCount());
    }

    @Test public void cancelAbortsAllRequestsOwnedByPage() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        SourceHttp.Scope scope = new SourceHttp.Scope();
        Future<IOException> first = workers.submit(() -> failure(scope));
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        Future<IOException> second = workers.submit(() -> failure(scope));
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        scope.cancel();
        assertNotNull(first.get(2, TimeUnit.SECONDS));
        assertNotNull(second.get(2, TimeUnit.SECONDS));
    }

    @Test public void cancellingOnePageDoesNotCancelAnother() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        SourceHttp.Scope oldPage = new SourceHttp.Scope();
        Future<IOException> blocked = workers.submit(() -> failure(oldPage));
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        oldPage.cancel();
        assertNotNull(blocked.get(2, TimeUnit.SECONDS));
        server.enqueue(new MockResponse().setBody("ok"));
        assertEquals(2, http.download(request(), 128, 1000, new SourceHttp.Scope()).length);
    }

    @Test public void invalidLimitsNeverReachNetwork() {
        assertThrows(IllegalArgumentException.class, () -> http.download(request(), 0, 1000, new SourceHttp.Scope()));
        assertThrows(IllegalArgumentException.class, () -> http.download(request(), 128, 0, new SourceHttp.Scope()));
        assertEquals(0, server.getRequestCount());
    }

    private IOException failure(SourceHttp.Scope scope) throws Exception {
        try {
            http.download(request(), 128, 10000, scope);
            throw new AssertionError("Request should not succeed");
        } catch (IOException expected) { return expected; }
    }
}
