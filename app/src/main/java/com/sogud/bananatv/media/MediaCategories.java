package com.sogud.bananatv.media;

import com.sogud.bananatv.R;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import static com.sogud.bananatv.media.MediaModels.*;

/** Provider IDs remain private to their provider; only the display categories are shared. */
final class MediaCategories {
    static final String[] IDS = {"movie", "series", "anime", "variety", "documentary", "other"};
    static final int[] LABELS = {R.string.media_movies, R.string.media_series, R.string.media_anime,
            R.string.media_variety, R.string.media_documentary, R.string.media_other};
    private MediaCategories() { }
    static String section(String query) {
        int split = query.indexOf(':');
        return split < 0 ? query : query.substring(0, split);
    }
    static String genre(String query) {
        int split = query.indexOf(':');
        return split < 0 ? "" : query.substring(split + 1);
    }
    private static boolean parent(String category, String name) {
        return category.equals("movie") && (name.equals("电影") || name.equals("电影片"))
                || category.equals("series") && (name.equals("剧集") || name.equals("电视剧") || name.equals("连续剧"))
                || category.equals("anime") && (name.equals("动漫") || name.equals("动漫片") || name.equals("动画"))
                || category.equals("variety") && (name.equals("综艺") || name.equals("综艺片"))
                || category.equals("documentary") && (name.equals("纪录片") || name.equals("记录片"));
    }
    private static String genreName(String category, String value) {
        String name = value.replaceAll("\\s+", "");
        if (category.equals("movie")) return name.replaceFirst("(电影|片)$", "");
        if (category.equals("series")) return name.replaceFirst("(电视剧|连续剧|剧集|剧)$", "");
        if (category.equals("anime")) return name.replaceFirst("(动漫片|动画片|动漫|动画|片)$", "");
        if (category.equals("variety")) return name.replaceFirst("(综艺片|综艺)$", "");
        if (category.equals("documentary")) return name.replaceFirst("(纪录片|记录片)$", "");
        return name;
    }
    static List<String> genres(Site site, String category, List<Category> classes) {
        List<String> names = new ArrayList<>();
        List<String> ids = targets(site, category, classes);
        for (Category item : classes) {
            if (!ids.contains(item.id) || parent(category, item.name.replaceAll("\\s+", ""))) continue;
            String name = genreName(category, item.name);
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }
    static String kind(String value) {
        String name = value.replaceAll("\\s+", "");
        if (name.contains("解说") || name.contains("预告")) return "other";
        if (name.contains("资讯") || name.contains("新闻") || name.equals("演员")) return "other";
        if (name.contains("纪录") || name.contains("记录")) return "documentary";
        if (name.contains("动漫") || name.contains("动画")) return "anime";
        if (name.contains("综艺")) return "variety";
        if (name.contains("电影") || name.endsWith("片")) return "movie";
        if (name.contains("剧") || name.contains("连续")) return "series";
        return "other";
    }
    static List<String> targets(Site site, String category, List<Category> classes) {
        String genre = genre(category);
        category = section(category);
        List<String> ids = new ArrayList<>();
        JSONArray override = site.categories == null ? null : site.categories.optJSONArray(category);
        if (override != null) {
            for (int i = 0; i < override.length(); i++) {
                String id = override.optString(i);
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
        } else {
            List<String> parents = new ArrayList<>();
            // MacCMS parent queries do not include children. Page through both, with genres first.
            for (Category item : classes) {
                if (parent(category, item.name.replaceAll("\\s+", "")) && !item.id.isEmpty()) parents.add(item.id);
            }
            for (Category item : classes) {
                if (kind(item.name).equals(category) && !item.id.isEmpty() && !parents.contains(item.id) && !ids.contains(item.id)) ids.add(item.id);
            }
            for (String id : parents) if (!ids.contains(id)) ids.add(id);
        }
        if (!genre.isEmpty()) {
            List<String> matching = new ArrayList<>();
            for (Category item : classes) {
                if (ids.contains(item.id) && !parent(category, item.name.replaceAll("\\s+", ""))
                        && genre.equals(genreName(category, item.name))) matching.add(item.id);
            }
            return matching;
        }
        return ids;
    }
}
