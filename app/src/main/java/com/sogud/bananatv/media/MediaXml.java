package com.sogud.bananatv.media;

import android.util.Xml;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** MacCMS XML feeds use the same internal records as JSON feeds. */
final class MediaXml {
    private MediaXml() { }

    static JSONObject parse(String text) throws Exception {
        XmlPullParser xml = Xml.newPullParser();
        xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        xml.setInput(new StringReader(text));
        JSONObject result = new JSONObject();
        JSONArray categories = new JSONArray(), movies = new JSONArray();
        boolean rss = false;
        for (int event = xml.nextToken(); event != XmlPullParser.END_DOCUMENT; event = xml.nextToken()) {
            if (event == XmlPullParser.DOCDECL) throw new XmlPullParserException("DTD is not supported");
            if (event != XmlPullParser.START_TAG) continue;
            String name = xml.getName();
            if (name.equals("rss")) rss = true;
            else if (name.equals("ty")) {
                JSONObject category = new JSONObject().put("type_id", attribute(xml, "id"));
                category.put("type_pid", attribute(xml, "pid"));
                category.put("type_name", content(xml)); categories.put(category);
            } else if (name.equals("list")) {
                result.put("page", number(xml, "page", 1));
                result.put("pagecount", number(xml, "pagecount", 1));
            } else if (name.equals("video")) movies.put(movie(xml));
        }
        if (!rss) throw new XmlPullParserException("Not a MacCMS XML feed");
        return result.put("class", categories).put("list", movies);
    }

    private static JSONObject movie(XmlPullParser xml) throws Exception {
        JSONObject movie = new JSONObject();
        List<String> names = new ArrayList<>(), groups = new ArrayList<>();
        int depth = xml.getDepth();
        while (xml.nextToken() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.END_TAG && xml.getDepth() == depth) break;
            if (xml.getEventType() == XmlPullParser.DOCDECL) throw new XmlPullParserException("DTD is not supported");
            if (xml.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = xml.getName();
            if (tag.equals("dl")) continue;
            if (tag.equals("dd")) {
                names.add(attribute(xml, "flag")); groups.add(content(xml)); continue;
            }
            String field;
            switch (tag) {
                case "id": field = "vod_id"; break;
                case "name": field = "vod_name"; break;
                case "pic": field = "vod_pic"; break;
                case "note": field = "vod_remarks"; break;
                case "des": field = "vod_content"; break;
                case "year": field = "vod_year"; break;
                case "type": field = "type_name"; break;
                case "tid": field = "type_id"; break;
                default: field = "vod_" + tag;
            }
            movie.put(field, content(xml));
        }
        return movie.put("vod_play_from", String.join("$$$", names)).put("vod_play_url", String.join("$$$", groups));
    }

    private static String attribute(XmlPullParser xml, String name) {
        String value = xml.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static int number(XmlPullParser xml, String name, int fallback) {
        try { return Integer.parseInt(attribute(xml, name)); }
        catch (NumberFormatException error) { return fallback; }
    }

    private static String content(XmlPullParser xml) throws Exception {
        int depth = xml.getDepth();
        StringBuilder text = new StringBuilder();
        while (xml.nextToken() != XmlPullParser.END_DOCUMENT) {
            int event = xml.getEventType();
            if (event == XmlPullParser.DOCDECL || (event == XmlPullParser.ENTITY_REF && xml.getText() == null))
                throw new XmlPullParserException("External or unresolved entities are not supported");
            if (event == XmlPullParser.END_TAG && xml.getDepth() == depth) return text.toString().trim();
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF) text.append(xml.getText());
        }
        throw new XmlPullParserException("Unclosed XML field");
    }
}
