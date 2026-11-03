package com.sogud.bananatv.source.script;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in private source fixture; source text is not part of the library or public assets. */
final class DrpyReferenceProbe {
    static void run(Context context, Consumer<String> report) throws Exception {
        String adapter;
        try (InputStream input = context.getAssets().open("drpy2.js")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            adapter = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(adapter.getBytes(StandardCharsets.UTF_8));
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : digest) fingerprint.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!fingerprint.toString().equals("67f4f6b460db1ec7ef50585953ce826c5263ca91d72958490ef4702b4e154fe1")) {
            throw new AssertionError("Reference adapter does not match pinned fixture");
        }
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body;
                if (request.getPath().startsWith("/detail/")) {
                    body = "<h1>Fixture Film</h1><img class=poster src='/poster.jpg'><div class=type>动画</div>"
                            + "<div class=meta>2026</div><div class=summary>Fixture plot</div>"
                            + "<div class=tabs><b class=tab>原画</b></div><div class=episodes><a href='/video.mp4'>第1集</a></div>";
                } else {
                    body = "<div class=items><div class=item><a href='/detail/one'>Fixture Film</a>"
                            + "<img src='/poster.jpg'><span class=note>HD</span></div></div>";
                }
                return new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body);
            }
        });
        server.start();
        try {
            String wrapper = "import drpy from './drpy2.js'; let catalogue,detail;"
                    + "const decode=value=>typeof value==='string'?JSON.parse(value):value;"
                    + "async function call(name,args){try{return {value:await drpy[name](...args)};}catch(e){return {error:String(e)};}}"
                    + "export default {init(cfg){return call('init',[cfg]);},home(){return call('home',[true]);},"
                    + "async category(...args){catalogue=await call('category',args);return catalogue;},"
                    + "search(...args){return call('search',args);},"
                    + "async detail(){try{detail=await call('detail',[decode(catalogue.value).list[0].vod_id]);return detail;}catch(e){return {error:String(e)};}},"
                    + "async play(){try{const film=decode(detail.value).list[0];const episode=film.vod_play_url.split('$$$')[0].split('#')[0];"
                    + "return call('play',[film.vod_play_from.split('$$$')[0],episode.slice(episode.indexOf('$')+1),[]]);}catch(e){return {error:String(e)};}}};";
            String listing = ".items .item;a&&Text;img&&src;.note&&Text;a&&href";
            JSONObject rule = new JSONObject().put("title", "Banana Fixture").put("host", server.url("/").toString())
                    .put("class_name", "电影&剧集").put("class_url", "movie&series").put("url", "/category/fyclass/fypage")
                    .put("searchUrl", "/search?wd=**").put("一级", listing).put("搜索", listing)
                    .put("二级", new JSONObject().put("title", "h1&&Text;.type&&Text").put("img", ".poster&&src")
                            .put("desc", ".meta&&Text").put("content", ".summary&&Text").put("tabs", ".tabs .tab")
                            .put("lists", ".episodes:eq(#id) a"))
                    .put("play_parse", true).put("lazy", "js:input={parse:0,url:input}");
            JSONArray calls = new JSONArray()
                    .put(call("INIT", new JSONArray().put(rule)))
                    .put(call("HOME", new JSONArray()))
                    .put(call("CATEGORY", new JSONArray().put("movie").put(1).put(false).put(new JSONObject())))
                    .put(call("SEARCH", new JSONArray().put("fixture").put(false).put(1)))
                    .put(call("DETAIL", new JSONArray()))
                    .put(call("PLAY", new JSONArray()));
            JSONObject request = new JSONObject().put("entry", "fixture.js").put("profile", "drpy").put("diagnostics", true)
                    .put("sourceId", "drpy-reference-fixture")
                    .put("modules", new JSONObject().put("fixture.js", wrapper).put("drpy2.js", adapter)).put("calls", calls);
            try (WorkerProbe worker = new WorkerProbe(context)) {
                JSONObject response = worker.run(request, 15000);
                if (response.has("error")) throw new AssertionError("drpy worker failed: " + response);
                JSONArray results = response.getJSONArray("results");
                JSONObject home = wire(results, 1, response);
                require(home.getJSONArray("class").length() == 2
                        && home.getJSONArray("class").getJSONObject(0).getString("type_name").equals("电影"),
                        "pinned_drpy_initialization_and_categories_on_new_runtime", report);
                JSONObject catalogue = wire(results, 2, response);
                if (catalogue.getJSONArray("list").length() != 1 || !catalogue.getJSONArray("list")
                        .getJSONObject(0).optString("vod_name").equals("Fixture Film")) {
                    throw new AssertionError("drpy catalogue fixture: " + response);
                }
                require(catalogue.getJSONArray("list").length() == 1 && catalogue.getJSONArray("list").getJSONObject(0)
                        .getString("vod_name").equals("Fixture Film"), "pinned_drpy_reads_html_movie_catalogue", report);
                require(wire(results, 3, response).getJSONArray("list").getJSONObject(0).getString("vod_name").equals("Fixture Film"),
                        "pinned_drpy_searches_loopback_fixture", report);
                JSONObject film = wire(results, 4, response).getJSONArray("list").getJSONObject(0);
                require(film.getString("vod_play_url").contains("/video.mp4"), "pinned_drpy_detail_keeps_episode_url", report);
                JSONObject playback = wire(results, 5, response);
                require(playback.getString("url").equals(server.url("/video.mp4").toString()) && playback.getInt("parse") == 0,
                        "pinned_drpy_resolves_fixture_playback_url", report);
            }
        } finally { server.shutdown(); }
    }

    private static JSONObject call(String operation, JSONArray arguments) throws Exception {
        return new JSONObject().put("operation", operation).put("arguments", arguments);
    }

    private static JSONObject wire(JSONArray results, int index, JSONObject response) throws Exception {
        JSONObject wrapped = new JSONObject(results.getString(index));
        if (wrapped.has("error") || !wrapped.has("value")) throw new AssertionError("drpy operation " + index + ": " + response);
        Object value = wrapped.get("value");
        return value instanceof String ? new JSONObject((String)value) : (JSONObject)value;
    }

    private static void require(boolean value, String label, Consumer<String> report) {
        if (!value) throw new AssertionError(label);
        report.accept(label);
    }
}
