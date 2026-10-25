package com.sogud.bananatv.media;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** CatVod wire fields; episode grouping follows FongMi TV's flag/episode format. */
public final class MediaModels {
    private MediaModels() { }
    public static final class Site {
        public String key, name, api, ext, jar, jarHost;
        public final List<String> mirrors = new ArrayList<>();
        public volatile String activeApi;
        public int type;
        public final boolean searchable;
        public JSONObject headers;
        public final JSONObject categories;
        public final List<String> flags;
        public final JSONArray parsers;
        Site(JSONObject json, String globalJar) { this(json, globalJar, ""); }
        Site(JSONObject json, String globalJar, String globalHost) {
            key = json.optString("key"); name = json.optString("name", key);
            api = json.optString("api"); type = json.optInt("type", 1);
            searchable = json.optInt("searchable", 1) != 0;
            activeApi = api;
            JSONArray endpoints = json.optJSONArray("mirrors");
            if (endpoints != null) for (int i = 0; i < endpoints.length(); i++) {
                String endpoint = endpoints.optString(i);
                if (!endpoint.equals(api) && !mirrors.contains(endpoint)) mirrors.add(endpoint);
            }
            Object value = json.opt("ext"); ext = value == null ? "" : value.toString();
            jar = json.optString("jar", globalJar); headers = json.optJSONObject("header");
            jarHost = json.optString("jarHost", globalHost);
            categories = json.optJSONObject("categories");
            JSONArray values = json.optJSONArray("flags");
            flags = values == null ? null : new ArrayList<>();
            if (values != null) for (int i = 0; i < values.length(); i++) flags.add(values.optString(i));
            parsers = json.optJSONArray("parses");
        }
    }
    public static final class Category {
        public final String id, name;
        Category(JSONObject json) { id = json.optString("type_id"); name = json.optString("type_name"); }
    }
    public static final class Episode {
        public final String name, id;
        Episode(String name, String id) { this.name = name; this.id = id; }
    }
    public static final class Line {
        public final String name;
        public final List<Episode> episodes = new ArrayList<>();
        Line(String name) { this.name = name; }
    }
    public static final class Movie {
        public final String id, title, poster, remark, summary, year, type;
        public final boolean folder;
        public final List<Line> lines = new ArrayList<>();
        Movie(JSONObject json) {
            id = json.optString("vod_id"); title = json.optString("vod_name");
            poster = json.optString("vod_pic"); remark = json.optString("vod_remarks");
            summary = json.optString("vod_content"); year = json.optString("vod_year");
            type = json.optString("type_name");
            folder = json.optString("vod_tag").equals("folder");
            String[] names = json.optString("vod_play_from").split("\\$\\$\\$", -1);
            String[] groups = json.optString("vod_play_url").split("\\$\\$\\$", -1);
            for (int i = 0; i < groups.length; i++) {
                Line line = new Line(i < names.length ? names[i] : String.valueOf(i + 1));
                for (String entry : groups[i].split("#")) {
                    if (entry.isEmpty()) continue;
                    int cut = entry.indexOf('$');
                    String name = cut < 0 ? String.valueOf(line.episodes.size() + 1) : entry.substring(0, cut);
                    String url = cut < 0 ? entry : entry.substring(cut + 1);
                    if (!url.isEmpty()) line.episodes.add(new Episode(name, url));
                }
                if (!line.episodes.isEmpty()) lines.add(line);
            }
        }
    }
    public static final class Page {
        final JSONObject wire;
        public final List<Category> categories = new ArrayList<>();
        public final List<Movie> movies = new ArrayList<>();
        public final int page, pages;
        Page(JSONObject json) {
            wire = json;
            page = json.optInt("page", 1); pages = json.optInt("pagecount", page);
            JSONArray classes = json.optJSONArray("class"), list = json.optJSONArray("list");
            if (classes != null) for (int i = 0; i < classes.length(); i++) {
                JSONObject item = classes.optJSONObject(i);
                if (item != null) categories.add(new Category(item));
            }
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null) movies.add(new Movie(item));
            }
        }
    }
}
