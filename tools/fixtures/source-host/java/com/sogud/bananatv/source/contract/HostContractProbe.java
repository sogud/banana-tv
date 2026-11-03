package com.sogud.bananatv.source.contract;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.github.catvod.net.Net;
import com.github.catvod.net.NetOptions;
import com.github.catvod.utils.Local;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original black-box contract fixture. Only fake loopback responses and disposable storage. */
public final class HostContractProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    private JSONObject options(String json) throws Exception {
        NetOptions options;
        try { options = NetOptions.from(json); }
        catch (RuntimeException error) { return new JSONObject().put("failure", error.getClass().getSimpleName()); }
        JSONObject result = new JSONObject();
        // Public scalar accessors are observations; no implementation fields are inspected.
        for (java.lang.reflect.Method method : NetOptions.class.getMethods()) {
            if (method.getParameterCount() != 0 || method.getDeclaringClass() != NetOptions.class) continue;
            Class<?> type = method.getReturnType();
            if (type.isPrimitive() || type == String.class || java.util.Map.class.isAssignableFrom(type)) {
                Object value = method.invoke(options);
                result.put(method.getName(), value instanceof java.util.Map ? new JSONObject((java.util.Map<?, ?>) value)
                        : value == null ? JSONObject.NULL : value);
            }
        }
        return result;
    }

    private JSONObject received(MockWebServer server) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        if (request == null) throw new AssertionError("Fixture request was not sent");
        return new JSONObject().put("method", request.getMethod()).put("path", request.getPath())
                .put("body", request.getBody().readUtf8()).put("fixtureHeader", request.getHeader("X-Fixture"))
                .put("contentType", request.getHeader("Content-Type")).put("cookie", request.getHeader("Cookie"));
    }

    private MockResponse response(String body) {
        return new MockResponse().setBody(body).setHeader("Content-Type", "text/plain; charset=utf-8")
                .addHeader("X-Repeated", "one").addHeader("X-Repeated", "two");
    }

    @Override public void onStart() {
        Bundle status = new Bundle();
        JSONObject report = new JSONObject();
        Local first = null;
        Local second = null;
        com.github.catvod.Runtime.set(getTargetContext());
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String base = server.url("/").toString();
            report.put("defaults", options("{}"));
            report.put("explicit", options("{\"method\":\"post\",\"timeout\":1250,\"callTimeout\":2500,\"redirect\":0,"
                    + "\"cookie\":true,\"buffer\":2,\"maxBytes\":8192,\"headers\":{\"X-Fixture\":\"plural\"}}"));
            report.put("numericCookie", options("{\"cookie\":1}"));
            report.put("singularHeader", options("{\"header\":{\"X-Fixture\":\"singular\"}}"));
            report.put("dataOptions", options("{\"method\":\"POST\",\"data\":{\"fixture\":true}}"));
            try (Net net = new Net()) {
                JSONObject requests = new JSONObject();
                report.put("requests", requests);
                NetOptions plain = NetOptions.from("{}");
                Net.Result failed = Net.Result.failure(new java.io.IOException("fixture-only"));
                report.put("failureResult", new JSONObject().put("code", failed.code).put("error", failed.error)
                        .put("json", failed.json(plain)).put("parts", new JSONArray(failed.parts(plain))).put("text", failed.text()));
                server.enqueue(response("plain-影视"));
                requests.put("get", new JSONObject().put("result", net.get(base + "get")).put("request", received(server)));
                server.enqueue(response("limited"));
                requests.put("getWithNumber", new JSONObject().put("result", net.get(base + "limited", 2000)).put("request", received(server)));
                server.enqueue(response("headers"));
                requests.put("getWithHeaders", new JSONObject().put("result", net.get(base + "headers", Collections.singletonMap("X-Fixture", "header")))
                        .put("request", received(server)));
                server.enqueue(response("posted"));
                requests.put("post", new JSONObject().put("result", net.post(base + "post", "{\"name\":\"影视\"}"))
                        .put("request", received(server)));
                String custom = "{\"method\":\"POST\",\"data\":\"payload\",\"headers\":{\"X-Fixture\":\"custom\"}}";
                server.enqueue(response("wire"));
                requests.put("req", new JSONObject().put("result", net.req(base + "req", custom)).put("request", received(server)));
                server.enqueue(response("{\"fixture\":\"json-wire\"}"));
                requests.put("json", new JSONObject().put("result", net.json(base + "json", "{}")).put("request", received(server)));
                server.enqueue(response("{\"fixture\":true}"));
                requests.put("jsonText", new JSONObject().put("result", net.jsonText(base + "jsonText", "{}")).put("request", received(server)));
                server.enqueue(response("missing").setResponseCode(404));
                requests.put("nonSuccessGet", new JSONObject().put("result", net.get(base + "missing")).put("request", received(server)));
                server.enqueue(response("missing").setResponseCode(404));
                requests.put("nonSuccessReq", new JSONObject().put("result", net.req(base + "missing", "{}")).put("request", received(server)));
                server.enqueue(response("result-body"));
                Net.Result result = net.request(base + "result", plain);
                requests.put("result", new JSONObject().put("code", result.code).put("headers", new JSONObject(result.headers))
                        .put("text", result.text()).put("json", result.json(plain)).put("parts", new JSONArray(result.parts(plain)))
                        .put("request", received(server)));
                server.enqueue(response("binary"));
                requests.put("base64Parts", new JSONArray(net.reqParts(base + "base64", "{\"buffer\":2}")));
                received(server);
                server.enqueue(response("bytes"));
                NetOptions binary = NetOptions.from("{\"buffer\":1}");
                Net.Result raw = net.request(base + "bytes", binary);
                requests.put("binaryParts", new JSONArray(raw.parts(binary)));
                requests.put("binaryJson", raw.json(binary));
                received(server);
                server.enqueue(response("oversized"));
                requests.put("limitedBytes", net.req(base + "oversized", "{\"maxBytes\":3}"));
                received(server);
                server.enqueue(response("query"));
                requests.put("params", new JSONObject().put("result", net.get(base + "query?existing=yes",
                        Collections.emptyMap())).put("request", received(server)));
                server.enqueue(response("form"));
                requests.put("form", new JSONObject().put("result", net.req(base + "form",
                        "{\"method\":\"post\",\"postType\":\"form\",\"data\":{\"name\":\"影视\",\"count\":2},\"params\":{\"q\":\"one two\"}}"))
                        .put("request", received(server)));
                net.close();
                try { report.put("afterClose", net.get(base + "closed")); }
                catch (RuntimeException expected) { report.put("afterClose", new JSONObject().put("failure", expected.getClass().getSimpleName())); }
            }
            JSONArray cookieReads = new JSONArray();
            JSONArray cookieWrites = new JSONArray();
            Net.Cookies cookies = new Net.Cookies() {
                @Override public String getCookie(String key) { cookieReads.put(key); return "fixture=old"; }
                @Override public boolean setCookie(String key, String value) {
                    cookieWrites.put(new JSONObject(Collections.singletonMap(key, value))); return true;
                }
            };
            try (Net parent = new Net(cookies, "fixture-owner")) {
                JSONObject cookie = new JSONObject();
                report.put("cookies", cookie);
                cookie.put("directRead", parent.getCookie(base + "cookie"));
                cookie.put("directWrite", parent.setCookie(base + "cookie", "fixture=manual"));
                server.enqueue(response("cookie").addHeader("Set-Cookie", "fixture=new; Path=/; HttpOnly"));
                cookie.put("response", parent.req(base + "cookie", "{\"cookie\":true}"));
                cookie.put("request", received(server));
                Net child = parent.session(); child.close();
                server.enqueue(response("parent-alive"));
                cookie.put("parentAfterChildClosed", parent.get(base + "parent")); received(server);
                cookie.put("reads", cookieReads).put("writes", cookieWrites);
                report.put("cookies", cookie);
            }
            String owner = "source-host-fixture-" + UUID.randomUUID();
            first = new Local(owner);
            second = new Local(owner + "-other");
            JSONObject storage = new JSONObject();
            report.put("storage", storage);
            storage.put("missing", first.get("key") == null ? JSONObject.NULL : first.get("key"));
            first.set("key", JSONObject.quote("影视"));
            storage.put("restored", new Local(owner).get("key"))
                    .put("otherOwner", second.get("key") == null ? JSONObject.NULL : second.get("key"));
            first.delete("key"); storage.put("deleted", first.get("key") == null ? JSONObject.NULL : first.get("key"));
            first.set("object", "{\"fixture\":true}"); storage.put("object", first.get("object"));
            first.set("first", JSONObject.quote("one")); first.set("second", JSONObject.quote("two")); first.clear();
            storage.put("cleared", first.get("first") == null && first.get("second") == null);
            report.put("webSocket", SocketContractFixture.run());
            report.put("webSocketFirst", SocketContractFixture.firstMessage());
            report.put("async", AsyncContractFixture.run());
            report.put("stream", StreamContractFixture.run(getTargetContext()));
            String normalized = report.toString().replace(base, "http://fixture.invalid/");
            status.putString("report", normalized);
            status.putString("result", "PASS");
            finish(Activity.RESULT_OK, status);
        } catch (Throwable error) {
            status.putString("report", report.toString());
            status.putString("result", "FAIL"); status.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, status);
        } finally {
            if (first != null) first.clear();
            if (second != null) second.clear();
            com.github.catvod.Runtime.set(null);
        }
    }
}
