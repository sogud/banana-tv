package com.sogud.bananatv.media;

import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static com.sogud.bananatv.media.MediaModels.*;

/** Shared home, category and search loading. JSON runs three at a time; scripts remain serial. */
final class MediaCatalog {
    static final class Reference {
        final Site site;
        final Movie movie;
        Reference(Site site, Movie movie) { this.site = site; this.movie = movie; }
    }
    static final class Group {
        final String key;
        final Movie movie;
        final List<Reference> sources = new ArrayList<>();
        Group(String key, Movie movie) { this.key = key; this.movie = movie; }
        void add(Site site, Movie movie) {
            for (Reference source : sources) {
                if (source.site.key.equals(site.key) && source.movie.id.equals(movie.id)) return;
            }
            sources.add(new Reference(site, movie));
        }
        String poster() {
            for (Reference source : sources) if (!source.movie.poster.isEmpty()) return source.movie.poster;
            return "";
        }
    }
    static final class Results {
        final Map<String, Group> groups = new LinkedHashMap<>();
        void add(Site site, List<Movie> movies) {
            for (Movie movie : movies.subList(0, Math.min(movies.size(), 100))) {
                if (movie.id.isEmpty() || movie.title.isBlank()) continue;
                String key = identity(site, movie);
                Group group = groups.get(key);
                if (group == null) { group = new Group(key, movie); groups.put(key, group); }
                group.add(site, movie);
            }
        }
        void replace(Site site, List<Movie> movies) {
            for (Group group : groups.values()) group.sources.removeIf(source -> source.site.key.equals(site.key));
            add(site, movies);
            groups.values().removeIf(group -> group.sources.isEmpty());
        }
        static String identity(Site site, Movie movie) {
            if (movie.folder) return "folder:" + site.key + ":" + movie.id;
            String title = normalize(movie.title);
            String year = movie.year.trim();
            String type = normalize(movie.type);
            // Missing metadata stays separate. Numeric IDs are meaningful only within their source.
            if (!year.matches("(?:19|20)[0-9]{2}") || type.isEmpty()) return "source:" + site.key + ":" + movie.id;
            String kind = type;
            if (type.contains("解说")) kind = "commentary";
            else if (type.contains("预告")) kind = "trailer";
            else if (type.contains("综艺")) kind = "variety";
            else if (type.contains("动漫")) kind = "animation";
            else if (type.contains("片") || type.contains("电影")) kind = "film";
            else if (type.contains("剧") || type.contains("连续")) kind = "series";
            return "title:" + title + "|" + year + "|" + kind;
        }
        private static String normalize(String value) {
            String plain = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
            return Normalizer.normalize(plain, Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);
        }
    }
    enum Mode { HOME, CATEGORY, SEARCH }
    static final class Library {
        final Map<String, List<Category>> categories = new ConcurrentHashMap<>();
    }
    private static final class Target {
        final String id;
        int next = 1, pages = 1;
        Target(String id) { this.id = id; }
    }
    private static final class Source {
        final Site site;
        final List<Target> targets = new ArrayList<>();
        int cursor;
        boolean initialized, failed, attempted, cached;
        String cacheKey;
        Source(Site site) { this.site = site; }
        Target next() {
            for (int i = 0; i < targets.size(); i++) {
                Target target = targets.get((cursor + i) % targets.size());
                if (target.next <= target.pages) return target;
            }
            return null;
        }
    }
    final Results results = new Results();
    private final MediaEngine engine;
    private final ExecutorService scripts;
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaEngine.Cancellation cancellation = new MediaEngine.Cancellation();
    private final List<Future<?>> tasks = new ArrayList<>();
    private final List<Source> sources = new ArrayList<>();
    final Mode mode;
    final String query;
    private final Library library;
    private final Runnable changed;
    private boolean cancelled;
    int pending, completed;

    MediaCatalog(MediaEngine engine, ExecutorService scripts, Library library, Mode mode, String query, Runnable changed) {
        this.engine = engine; this.scripts = scripts; this.library = library;
        this.mode = mode; this.query = query; this.changed = changed;
        for (Site site : engine.enabledSites()) {
            if (mode != Mode.SEARCH || site.searchable) sources.add(new Source(site));
        }
    }
    int total() { return sources.size(); }
    boolean loading() { return pending > 0; }
    boolean showingCache() {
        for (Source source : sources) if (source.cached) return true;
        return false;
    }
    List<String> failures() {
        List<String> names = new ArrayList<>();
        for (Source source : sources) if (source.failed) names.add(source.site.name);
        return names;
    }
    boolean hasMore() {
        for (Source source : sources) if (!source.failed && (!source.initialized || source.next() != null)) return true;
        return false;
    }
    void load(boolean retry) {
        if (cancelled || loading()) return;
        List<Source> batch = new ArrayList<>();
        for (Source source : sources) {
            if (retry ? source.failed : !source.failed && (!source.initialized || source.next() != null)) batch.add(source);
        }
        pending = batch.size();
        tasks.removeIf(Future::isDone);
        for (Source source : batch) {
            ExecutorService executor = source.site.type == 3 ? scripts : network;
            tasks.add(executor.submit(() -> {
                Page found = null;
                Target target = null;
                boolean firstPage = !source.attempted;
                try {
                    if (firstPage) restoreCache(source);
                    initialize(source);
                    target = source.next();
                    if (target == null) found = new Page(new org.json.JSONObject());
                    else if (mode == Mode.SEARCH) found = engine.search(source.site, query, target.next, cancellation);
                    else if (mode == Mode.CATEGORY) found = engine.category(source.site, target.id, target.next, cancellation);
                    else if (target.next == 1) {
                        found = engine.home(source.site, cancellation);
                        cancellation.check(); library.categories.put(source.site.key, found.categories);
                    } else found = engine.latest(source.site, target.next, cancellation);
                    cancellation.check();
                    if (firstPage && found != null) saveCache(source, found);
                }
                catch (Exception | LinkageError ignored) { /* Other sources remain available. */ }
                Page page = found;
                Target selected = target;
                main.post(() -> {
                    if (cancelled) return;
                    source.failed = page == null;
                    if (page != null) {
                        if (source.cached) results.replace(source.site, page.movies);
                        else results.add(source.site, page.movies);
                        source.cached = false;
                        if (selected != null) {
                            selected.pages = mode == Mode.HOME && source.site.type == 3 ? 1 : page.pages;
                            selected.next++;
                            source.cursor = (source.targets.indexOf(selected) + 1) % source.targets.size();
                        }
                    }
                    pending--;
                    if (!source.attempted) { source.attempted = true; completed++; }
                    changed.run();
                });
            }));
        }
        changed.run();
    }
    private void restoreCache(Source source) throws Exception {
        source.cacheKey = engine.catalogKey(source.site, mode.name(), query);
        byte[] bytes = engine.catalogCache.get(source.cacheKey);
        if (bytes == null) return;
        try {
            org.json.JSONObject saved = new org.json.JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            Page page = new Page(saved.getJSONObject("page"));
            Page classes = new Page(new org.json.JSONObject().put("class", saved.getJSONArray("classes")));
            cancellation.check();
            if (!classes.categories.isEmpty()) library.categories.put(source.site.key, classes.categories);
            main.post(() -> {
                if (cancelled) return;
                source.cached = true; results.add(source.site, page.movies); changed.run();
            });
        } catch (org.json.JSONException ignored) { /* A damaged display cache is a miss. */ }
    }
    private void saveCache(Source source, Page page) throws Exception {
        org.json.JSONArray classes = new org.json.JSONArray();
        List<Category> categories = library.categories.get(source.site.key);
        if (categories != null) for (Category item : categories)
            classes.put(new org.json.JSONObject().put("type_id", item.id).put("type_name", item.name));
        org.json.JSONObject saved = new org.json.JSONObject().put("page", page.wire).put("classes", classes);
        engine.catalogCache.put(source.cacheKey, saved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void initialize(Source source) throws Exception {
        cancellation.check();
        if (source.initialized) return;
        List<String> ids = new ArrayList<>();
        if (mode == Mode.CATEGORY) {
            List<Category> classes = library.categories.get(source.site.key);
            if (classes == null) {
                classes = engine.categories(source.site, cancellation);
                cancellation.check(); library.categories.put(source.site.key, classes);
            }
            ids = MediaCategories.targets(source.site, query, classes);
        } else ids.add("");
        for (String id : ids) source.targets.add(new Target(id));
        source.initialized = true;
    }
    void cancel() {
        cancelled = true; cancellation.cancel();
        for (Future<?> task : tasks) task.cancel(true);
        network.shutdownNow(); main.removeCallbacksAndMessages(null);
    }
}
