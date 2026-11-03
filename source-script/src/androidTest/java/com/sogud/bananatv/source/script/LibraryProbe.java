package com.sogud.bananatv.source.script;

import android.content.Context;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.sogud.bananatv.source.script.ScriptSession.Operation.*;

final class LibraryProbe {
    private static void check(boolean value, String label, Consumer<String> report) {
        if (!value) throw new AssertionError(label);
        report.accept(label);
    }

    static void run(Context context, Consumer<String> report) throws Exception {
        String source = "import {cheerio} from '@banana/libraries'; export default {home(){"
                + "const $=cheerio.load('<ul class=items><li><a href=/one>动画 &amp; 电影</a><li><a href=/two>剧集</a></ul>"
                + "<script>globalThis.htmlExecuted=true</script>');"
                + "const query=(path,json)=>{try{return cheerio.jp(path,json);}catch(e){return {failure:String(e),stack:e.stack};}};"
                + "return {titles:$('.items li a').map((i,e)=>$(e).text()).get(),"
                + "href:$('.items li').eq(1).find('a').attr('href'),count:$('.items li:has(a)').length,"
                + "inert:typeof globalThis.htmlExecuted==='undefined',"
                + "selected:query('$.list[?(@.score>=8)].name',{list:[{name:'film',score:8},{name:'series',score:6}]}),"
                + "nested:query('$.list',{list:[{id:'one'}]}),missing:query('$.missing',{})};},"
                + "category(){return {md5:CryptoJS.MD5('abc').toString(),sha:CryptoJS.SHA256('abc').toString()};},"
                + "search(){const key=CryptoJS.enc.Hex.parse('000102030405060708090a0b0c0d0e0f');"
                + "const iv=CryptoJS.enc.Hex.parse('101112131415161718191a1b1c1d1e1f');"
                + "const data=CryptoJS.enc.Hex.parse('00112233445566778899aabbccddeeff');"
                + "const encrypted=CryptoJS.AES.encrypt(data,key,{iv,mode:CryptoJS.mode.CBC,padding:CryptoJS.pad.NoPadding});"
                + "const decrypted=CryptoJS.AES.decrypt({ciphertext:encrypted.ciphertext},key,{iv,mode:CryptoJS.mode.CBC,padding:CryptoJS.pad.NoPadding});"
                + "return {cipher:encrypted.ciphertext.toString(),plain:decrypted.toString()};},"
                + "detail(){let calls=0;const original=crypto.getRandomValues;crypto.getRandomValues=view=>{calls++;return original(view);};"
                + "try{const first=CryptoJS.lib.WordArray.random(16),second=CryptoJS.lib.WordArray.random(16);"
                + "return {bytes:first.sigBytes,different:first.toString()!==second.toString(),calls};}finally{crypto.getRandomValues=original;}},"
                + "play(){return {encoded:base64Encode('动画'),decoded:base64Decode(base64Encode('动画')),hash:md5('abc')};},"
                + "proxy(){const doc='<a data-key=\"a&&b\" href=\"../video.mp4\">动画 &amp; 电影</a>';"
                + "let rejected=false;try{gbkTool.decode('%XZ');}catch(e){rejected=!String(e).includes('XZ');}"
                + "return {gbk:gbkTool.encode('你好 A'),gbkBack:gbkTool.decode('%C4%E3%BA%C3+A'),badEncoding:rejected,text:pdfh(doc,'a[data-key=\"a&&b\"]&&Text'),"
                + "url:pd(doc,'a&&href','https://fixture.invalid/path/page'),"
                + "nodes:pdfa('<ul><li>A</li><li>B</li></ul>','ul&&li').length,"
                + "body:pdfh('<b>fragment</b>','body&&Text'),stripped:'🐱//hi//🐱'.strip('🐱/'),"
                + "missing:pd('<p>No poster</p>','img&&src','https://fixture.invalid/detail/one'),"
                + "emptyStyle:pd('<b style=\"background:url()\"></b>','b&&style','https://fixture.invalid/detail/one'),"
                + "contains:'动画电影'.contains('电影')&&!('动画电影'.contains('动画',1)),"
                + "nonHttp:urljoin('https://fixture.invalid/page','javascript:void(0)'),"
                + "enumerable:Object.keys(String.prototype).includes('strip')||Object.keys(String.prototype).includes('contains'),"
                + "template:cheerio.jinja2('/{{kind}}/{% if page %}{{page}}{% else %}1{% endif %}',{kind:'movie',page:2})};}}";
        Map<String, String> modules = ScriptLibraries.modules(context, Collections.singletonMap("source.js", source), "source.js");
        try (ScriptSession session = new ScriptSession(modules, "source.js", 32 * 1024 * 1024, 8192)) {
            JSONObject html = new JSONObject(session.call(HOME, new JSONArray()));
            if (html.optJSONObject("selected") != null) throw new AssertionError("JSONPath fixture: " + html.getJSONObject("selected"));
            check(html.getJSONArray("titles").length() == 2 && html.getJSONArray("titles").getString(0).equals("动画 & 电影"),
                    "cheerio_css_selection_malformed_html_and_entities", report);
            check(html.getString("href").equals("/two") && html.getInt("count") == 2,
                    "cheerio_traversal_attributes_and_pseudo_selectors", report);
            check(html.getBoolean("inert"), "html_scripts_are_not_executed", report);
            check(html.getJSONArray("selected").length() == 1 && html.getJSONArray("selected").getString(0).equals("film"),
                    "legacy_cheerio_jsonpath_filters_work_in_quickjs", report);
            check(html.getJSONArray("nested").getJSONArray(0).getJSONObject(0).getString("id").equals("one")
                    && html.getJSONArray("missing").length() == 0, "legacy_jsonpath_keeps_nested_array_and_missing_result_contract", report);
            JSONObject hashes = new JSONObject(session.call(CATEGORY, new JSONArray()));
            check(hashes.getString("md5").equals("900150983cd24fb0d6963f7d28e17f72")
                    && hashes.getString("sha").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                    "crypto_legacy_names_match_hash_vectors", report);
            JSONObject encrypted = new JSONObject(session.call(SEARCH, new JSONArray()));
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(bytes("000102030405060708090a0b0c0d0e0f"), "AES"),
                    new IvParameterSpec(bytes("101112131415161718191a1b1c1d1e1f")));
            check(encrypted.getString("cipher").equals(hex(cipher.doFinal(bytes("00112233445566778899aabbccddeeff")))),
                    "crypto_aes_cbc_matches_android_jca", report);
            check(encrypted.getString("plain").equals("00112233445566778899aabbccddeeff"), "crypto_aes_decrypt_roundtrip", report);
            JSONObject random = new JSONObject(session.call(DETAIL, new JSONArray()));
            check(random.getInt("bytes") == 16 && random.getBoolean("different") && random.getInt("calls") > 0,
                    "crypto_random_uses_host_entropy", report);
            JSONObject encoding = new JSONObject(session.call(PLAY, new JSONArray()));
            check(encoding.getString("decoded").equals("动画") && encoding.getString("encoded").equals("5Yqo55S7")
                    && encoding.getString("hash").equals(hashes.getString("md5")), "base64_unicode_and_md5_helpers", report);
            JSONObject helpers = new JSONObject(session.call(PROXY, new JSONArray()));
            check(helpers.getString("text").equals("动画 & 电影") && helpers.getInt("nodes") == 2,
                    "source_html_helpers_preserve_quoted_css_and_lists", report);
            check(helpers.getString("url").equals("https://fixture.invalid/video.mp4")
                    && helpers.getString("body").equals("fragment"), "source_html_helpers_resolve_links_and_fragments", report);
            check(helpers.getString("missing").equals("") && helpers.getString("emptyStyle").equals(""),
                    "missing_html_urls_remain_empty_instead_of_base_page", report);
            check(helpers.getString("stripped").equals("hi") && !helpers.getBoolean("enumerable"),
                    "source_string_helpers_handle_unicode_without_enumeration_noise", report);
            check(helpers.getBoolean("contains"), "legacy_string_contains_matches_standard_includes", report);
            check(helpers.getString("nonHttp").equals("javascript:void(0)"),
                    "absolute_non_http_links_remain_available_for_source_filtering", report);
            check(helpers.getString("gbk").equals("%C4%E3%BA%C3+A") && helpers.getString("gbkBack").equals("你好 A")
                    && helpers.getBoolean("badEncoding"), "gbk_search_encoding_and_catchable_redacted_invalid_percent", report);
            check(helpers.getString("template").equals("/movie/2"), "jinja_renders_source_url_variables_and_branches", report);
        }
        try (WorkerProbe worker = new WorkerProbe(context)) {
            JSONObject result = new JSONObject(worker.run("export default {async home(){"
                    + "return {text:cheerio.load('<b>fixture</b>')('b').text(),hash:CryptoJS.MD5('abc').toString()};}}",
                    15000, true).getJSONArray("results").getString(0));
            check(result.getString("text").equals("fixture") && result.getString("hash").equals("900150983cd24fb0d6963f7d28e17f72"),
                    "worker_loads_packaged_html_and_crypto_libraries", report);
        }
    }

    private static byte[] bytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte)Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
