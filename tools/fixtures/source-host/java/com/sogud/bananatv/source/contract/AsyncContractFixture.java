package com.sogud.bananatv.source.contract;

import com.github.catvod.net.Net;
import com.github.catvod.net.NetOptions;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original observations of asynchronous and ordered concurrent operations. */
public final class AsyncContractFixture {
    public static JSONObject run() throws Exception {
        JSONObject report = new JSONObject();
        try (MockWebServer server = new MockWebServer(); Net net = new Net()) {
            server.start();
            server.enqueue(new MockResponse().setBody("async-影视"));
            report.put("request", net.requestAsync(server.url("/async").toString(), NetOptions.from("{}")).get(3, TimeUnit.SECONDS).json(NetOptions.from("{}")));
            if (server.takeRequest(2, TimeUnit.SECONDS) == null) throw new AssertionError("Missing async request");
            server.enqueue(new MockResponse().setBody("callback-影视"));
            CountDownLatch called = new CountDownLatch(1);
            AtomicReference<String> value = new AtomicReference<>();
            net.enqueue(server.url("/callback").toString(), NetOptions.from("{}"), result -> { value.set(result.text()); called.countDown(); });
            if (!called.await(3, TimeUnit.SECONDS)) throw new AssertionError("Missing callback");
            report.put("callback", value.get()); server.takeRequest(2, TimeUnit.SECONDS);
            report.put("map", new JSONArray(net.map(Arrays.asList("a", "b", "c"), (item, index) -> index + ":" + item, 2)));
            try { net.map(Arrays.asList("a"), (item, index) -> item, 0); }
            catch (Exception failure) { report.put("zeroThreadsMap", failure.getClass().getSimpleName()); }
            try { net.map(Collections.<String>emptyList(), (item, index) -> item, 0); }
            catch (Exception failure) { report.put("emptyZeroThreadsMap", failure.getClass().getSimpleName()); }
            report.put("emptyMap", new JSONArray(net.map(Collections.<String>emptyList(), (item, index) -> item)));
            try { net.map(Arrays.asList("a"), (item, index) -> { throw new IllegalArgumentException("fixture"); }, 2); }
            catch (Exception failure) { report.put("mapFailure", failure.getClass().getSimpleName()); }
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    return new MockResponse().setBody(request.getPath());
                }
            });
            JSONArray requests = new JSONArray();
            requests.put(new JSONObject().put("url", server.url("/batch-one").toString()).put("options", new JSONObject()));
            requests.put(new JSONObject().put("url", server.url("/batch-two").toString()).put("options", new JSONObject()));
            JSONArray results = new JSONArray();
            for (Net.Result result : net.batch(requests.toString(), 2)) results.put(result.json(NetOptions.from("{}")));
            report.put("batch", results);
            JSONArray binary = new JSONArray().put(new JSONObject().put("url", server.url("/binary").toString())
                    .put("options", new JSONObject().put("buffer", 2)));
            report.put("batchParts", new JSONArray(net.batchParts(binary.toString(), 2)));
            try { net.batch("not-json", 2); }
            catch (Exception failure) { report.put("batchFailure", failure.getClass().getSimpleName()); }
        }
        return report;
    }
}
