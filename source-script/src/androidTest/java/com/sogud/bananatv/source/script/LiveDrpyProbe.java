package com.sogud.bananatv.source.script;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional pinned real rules; prints structural observations only, never upstream response bodies. */
final class LiveDrpyProbe {
    private static String asset(Context context, String name) throws Exception {
        try (InputStream input = context.getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (output.size() + count > 1024 * 1024) throw new AssertionError("Reference input exceeds limit");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
    static JSONObject run(android.app.Instrumentation instrumentation, String filter) throws Exception {
        Context context = instrumentation.getContext();
        String adapter = asset(context, "drpy2.js");
        if (!hash(adapter).equals("67f4f6b460db1ec7ef50585953ce826c5263ca91d72958490ef4702b4e154fe1")) throw new AssertionError("Adapter digest mismatch");
        JSONObject manifest = new JSONObject(asset(context, "live-rules.json"));
        JSONArray rules = manifest.getJSONArray("rules"), reports = new JSONArray();
        for (int index = 0; index < rules.length(); index++) {
            JSONObject rule = rules.getJSONObject(index), report = new JSONObject().put("rule", rule.getString("name"));
            if (!filter.isEmpty() && !rule.getString("name").contains(filter)) continue;
            reports.put(report); String text = asset(context, rule.getString("asset"));
            if (!hash(text).equals(rule.getString("sha256"))) throw new AssertionError("Rule digest mismatch");
            if (rule.getString("name").startsWith("NT")) {
                java.util.regex.Matcher host = java.util.regex.Pattern.compile("host:[ \t]*'([^']+)'").matcher(text);
                java.util.regex.Matcher home = java.util.regex.Pattern.compile("homeUrl:[ \t]*'([^']+)'").matcher(text);
                if (host.find() && home.find()) {
                    String url = java.net.URI.create(host.group(1)).resolve(home.group(1)).toString();
                    try (ScriptHttp http = new ScriptHttp(new okhttp3.OkHttpClient(), 2 * 1024 * 1024)) {
                        JSONObject reply = http.request(url, new JSONObject().put("timeout", 5000));
                        report.put("nativeHttp", new JSONObject().put("status", reply.getInt("code")).put("characters", reply.getString("content").length()));
                    } catch (Exception failure) { report.put("nativeHttp", new JSONObject().put("errorType", failure.getClass().getSimpleName())); }
                }
            }
            String wrapper = "import drpy from './drpy2.js'; let home,category,detail;"
                    + "const decode=v=>typeof v==='string'?JSON.parse(v):v;"
                    + "const failure=e=>({error:e.name||'Error',missing:/([A-Za-z_$][A-Za-z0-9_$]*) is not defined/.exec(String(e).replaceAll(String.fromCharCode(39),'').replaceAll(String.fromCharCode(34),''))?.[1]||''});"
                    + "export default {async init(rule){try{await drpy.init(rule);return {initialized:true};}catch(e){return failure(e);}},"
                    + "async home(){try{home=decode(await drpy.home(true));let out={categories:home.class?.length||0};if(!out.categories){const r=drpy.getRule(),reply=req(urljoin(r.host,r.homeUrl),{headers:r.headers||{}});out.upstream={status:reply.code,bytes:reply.content.length,nodes:pdfa(reply.content,r.class_parse.split(';')[0]).length,title:pdfh(reply.content,'title&&Text')};}return out;}catch(e){return failure(e);}},"
                    + "async category(){try{const id=home?.class?.[0]?.type_id;if(!id)return {skipped:true};category=decode(await drpy.category(id,1,true,{}));return {items:category.list?.length||0,placeholder:/无数据|暂无|无结果/.test(category.list?.[0]?.vod_name||'')};}catch(e){return failure(e);}},"
                    + "async search(){try{const name=category?.list?.[0]?.vod_name;if(!name)return {skipped:true};const s=decode(await drpy.search(name,false,1));return {items:s.list?.length||0};}catch(e){return failure(e);}},"
                    + "async detail(){try{const id=category?.list?.[0]?.vod_id;if(!id)return {skipped:true};detail=decode(await drpy.detail(id));return {items:detail.list?.length||0,episodes:!!detail.list?.[0]?.vod_play_url};}catch(e){return failure(e);}},"
                    + "async play(){try{const video=detail?.list?.[0];if(!video?.vod_play_url)return {skipped:true};const line=video.vod_play_url.split('$$$')[0],episode=line.split('#')[0],id=episode.slice(episode.indexOf('$')+1);const p=decode(await drpy.play(video.vod_play_from.split('$$$')[0],id,[]));return {urlPresent:!!p.url,parse:p.parse,url:p.url,header:p.header||{},format:p.format||''};}catch(e){return failure(e);}}};";
            JSONArray calls = new JSONArray();
            for (String operation : new String[]{"INIT", "HOME", "CATEGORY", "SEARCH", "DETAIL", "PLAY"}) {
                calls.put(new JSONObject().put("operation", operation).put("arguments", operation.equals("INIT") ? new JSONArray().put(text) : new JSONArray()));
            }
            String owner = "live-rule-probe-" + UUID.randomUUID();
            JSONObject request = new JSONObject().put("entry", "live.js").put("profile", "drpy").put("diagnostics", true).put("sourceId", owner)
                    .put("modules", new JSONObject().put("live.js", wrapper).put("drpy2.js", adapter)).put("calls", calls);
            try (WorkerProbe worker = new WorkerProbe(context)) {
                JSONObject response = worker.run(request, 30000);
                String trace = response.optString("fixtureTrace", response.optString("diagnostic"));
                if (!trace.isEmpty()) {
                    String safe = trace.replaceAll("https?://\\S+", "[upstream]");
                    report.put("trace", safe.substring(0, Math.min(safe.length(), 2500)));
                }
                if (response.has("error")) report.put("error", response.getString("error"));
                else {
                    JSONArray results = response.getJSONArray("results");
                    String[] names = {"init", "home", "category", "search", "detail", "play"};
                    for (int step = 0; step < results.length(); step++) {
                        JSONObject value = new JSONObject(results.getString(step));
                        if (names[step].equals("play")) {
                            if (value.optBoolean("urlPresent") && value.optInt("parse", -1) == 0) {
                                value.put("playback", com.sogud.bananatv.source.fixture.PlaybackProbe.run(instrumentation, value));
                            }
                            value.remove("url"); value.remove("header");
                        }
                        report.put(names[step], value);
                    }
                }
            } catch (java.util.concurrent.TimeoutException expired) { report.put("error", "worker_timeout"); }
            finally {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
                String file = "source-" + android.util.Base64.encodeToString(digest,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                context.deleteSharedPreferences(file);
            }
        }
        return new JSONObject().put("rulesCommit", manifest.getString("commit")).put("rules", reports);
    }
    private static String hash(String text) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) result.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
        return result.toString();
    }
}
