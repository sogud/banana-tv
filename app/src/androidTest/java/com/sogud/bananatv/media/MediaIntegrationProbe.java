package com.sogud.bananatv.media;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import com.sogud.bananatv.R;
import org.json.JSONObject;
import java.lang.reflect.Field;
import static com.sogud.bananatv.media.MediaModels.*;

/** Disposable emulator fixture only; never runs against the owner's installed package or sources. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class MediaIntegrationProbe extends Instrumentation {
    private String base;
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; base = args.getString("fixture"); start(); }
    private void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        Bundle status = new Bundle(); status.putString("check", label); sendStatus(0, status);
    }
    private boolean contains(android.view.View view, String title) {
        if (view instanceof android.widget.TextView && ((android.widget.TextView) view).getText().toString().equals(title)) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (contains(group.getChildAt(i), title)) return true;
        }
        return false;
    }
    private android.view.accessibility.AccessibilityNodeInfo findNode(android.view.accessibility.AccessibilityNodeInfo node, String value, boolean edit) {
        if (node == null) return null;
        boolean matches = value.contentEquals(node.getText() == null ? "" : node.getText())
                || value.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription());
        if (edit ? node.isEditable() : matches) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = findNode(node.getChild(i), value, edit);
            if (found != null) return found;
        }
        return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo awaitNode(String value, boolean edit) throws Exception {
        for (int i = 0; i < 100; i++) {
            android.view.accessibility.AccessibilityNodeInfo node = findNode(getUiAutomation().getRootInActiveWindow(), value, edit);
            if (node != null) return node;
            Thread.sleep(200);
        }
        throw new AssertionError("Missing UI element: " + value);
    }
    private void clickText(String value) throws Exception {
        boolean clicked = false;
        for (int i = 0; i < 10 && !clicked; i++) {
            android.view.accessibility.AccessibilityNodeInfo node = awaitNode(value, false);
            while (node != null && !node.isClickable()) node = node.getParent();
            clicked = node != null && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            if (!clicked) Thread.sleep(100); // A page replacement can invalidate an accessibility node.
        }
        require(clicked, "ui_click_" + value);
    }
    private void enterText(String value) throws Exception {
        Bundle text = new Bundle(); text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        require(awaitNode("", true).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text), "ui_text_entered");
    }
    private void touchText(String value) throws Exception {
        android.graphics.Rect bounds = new android.graphics.Rect(); awaitNode(value, false).getBoundsInScreen(bounds);
        long start = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(start, start, android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(start, start + 40, android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
        try { sendPointerSync(down); sendPointerSync(up); } finally { down.recycle(); up.recycle(); }
        waitForIdleSync();
    }
    private void capture(String name) throws Exception {
        waitForIdleSync(); Thread.sleep(350);
        android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
        require(screenshot != null, "ui_capture_" + name);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(), name + ".png"))) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
    }
    private void awaitText(Activity activity, String value) throws Exception {
        for (int i = 0; i < 80; i++) {
            final boolean[] found = {false};
            runOnMainSync(() -> found[0] = contains(activity.getWindow().getDecorView(), value));
            if (found[0]) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Missing activity text: " + value);
    }
    private void aggregationCheck(MediaEngine engine) throws Exception {
        Site first = engine.site("fixture"), second = engine.site("second");
        JSONObject wire = new JSONObject().put("vod_id", "one").put("vod_name", "Spring").put("vod_year", "2019").put("type_name", "动画片");
        MediaCatalog.Results groups = new MediaCatalog.Results();
        groups.add(first, java.util.List.of(new Movie(wire)));
        groups.add(second, java.util.List.of(new Movie(new JSONObject(wire.toString()).put("vod_id", "different-id").put("vod_name", " Spring  "))));
        require(groups.groups.size() == 1 && groups.groups.values().iterator().next().sources.size() == 2, "aggregate_same_film_preserves_source_ids");
        MediaCatalog.Results genres = new MediaCatalog.Results();
        genres.add(first, java.util.List.of(new Movie(new JSONObject(wire.toString()).put("type_name", "剧情片"))));
        genres.add(second, java.util.List.of(new Movie(new JSONObject(wire.toString()).put("type_name", "科幻片"))));
        require(genres.groups.size() == 1 && MediaCategories.kind("喜剧片").equals("movie")
                && MediaCategories.kind("剧情片").equals("movie"), "film_genres_are_not_misclassified_as_series");
        groups.add(second, java.util.List.of(new Movie(new JSONObject(wire.toString()).put("vod_year", "2020")),
                new Movie(new JSONObject(wire.toString()).put("type_name", "电影解说")),
                new Movie(new JSONObject(wire.toString()).put("vod_name", "Spring 第二季")),
                new Movie(new JSONObject(wire.toString()).put("vod_year", ""))));
        require(groups.groups.size() == 5, "aggregation_keeps_year_commentary_season_and_unknown_separate");
        final MediaCatalog[] search = {null};
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService scripts = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            runOnMainSync(() -> {
                search[0] = new MediaCatalog(engine, scripts, new MediaCatalog.Library(), MediaCatalog.Mode.SEARCH, "sample", () -> { if (!search[0].loading()) finished.countDown(); });
                search[0].load(false);
            });
            require(finished.await(15, java.util.concurrent.TimeUnit.SECONDS), "cross_search_finishes_with_failed_source");
            require(search[0].total() == 3 && search[0].completed == 3 && search[0].failures().size() == 1
                    && search[0].results.groups.size() == 1 && search[0].results.groups.values().iterator().next().sources.size() == 2,
                    "cross_search_partial_success_and_searchable_flag");
            java.util.concurrent.CountDownLatch more = new java.util.concurrent.CountDownLatch(1);
            runOnMainSync(() -> {
                require(search[0].hasMore(), "cross_search_pagination_available");
                search[0].cancel();
                search[0] = new MediaCatalog(engine, scripts, new MediaCatalog.Library(), MediaCatalog.Mode.SEARCH, "sample", () -> { if (!search[0].loading() && search[0].results.groups.size() == 2) more.countDown(); });
                search[0].load(false);
            });
            for (int i = 0; i < 100; i++) {
                final boolean[] loading = {true}; runOnMainSync(() -> loading[0] = search[0].loading());
                if (!loading[0]) break; Thread.sleep(100);
            }
            runOnMainSync(() -> search[0].load(false));
            require(more.await(15, java.util.concurrent.TimeUnit.SECONDS) && search[0].results.groups.size() == 2,
                    "load_more_accumulates_and_deduplicates");
            final int[] updates = {0};
            runOnMainSync(() -> {
                search[0].cancel(); search[0] = new MediaCatalog(engine, scripts, new MediaCatalog.Library(), MediaCatalog.Mode.SEARCH, "slow", () -> updates[0]++);
                search[0].load(false); search[0].cancel(); updates[0] = 0;
            });
            Thread.sleep(3300); waitForIdleSync(); require(updates[0] == 0, "cancelled_search_cannot_publish_stale_results");
        } finally {
            runOnMainSync(() -> { if (search[0] != null) search[0].cancel(); }); scripts.shutdownNow();
        }
    }
    private void categoryAndSearchUi(Activity browser) throws Exception {
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); awaitText(browser, "推荐"); awaitText(browser, "Spring · 播放测试");
        for (int i = 0; i < 40; i++) {
            final boolean[] card = {false};
            runOnMainSync(() -> card[0] = browser.getCurrentFocus() != null
                    && "Spring · 播放测试".contentEquals(browser.getCurrentFocus().getContentDescription()));
            if (card[0]) break; Thread.sleep(100);
        }
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP); waitForIdleSync();
        final String[] focused = {""};
        runOnMainSync(() -> focused[0] = ((android.widget.Button) browser.getCurrentFocus()).getText().toString());
        require(focused[0].equals("推荐"), "remote_up_returns_to_category_strip_" + focused[0]);
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
        awaitText(browser, "Spring · 播放测试");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
        awaitText(browser, "Spring · 剧集测试");
        require(containsOnMain(browser, "电影") && containsOnMain(browser, "剧集"), "switch_categories_without_back");
        clickText("纪录片"); awaitText(browser, getTargetContext().getString(R.string.media_empty));
        require(containsOnMain(browser, "剧集"), "empty_category_retains_strip");
        int before = fixtureStats().getInt("/slow");
        clickText("综艺");
        for (int i = 0; i < 30 && fixtureStats().getInt("/slow") == before; i++) Thread.sleep(100);
        require(fixtureStats().getInt("/slow") > before, "slow_category_http_started");
        long start = android.os.SystemClock.elapsedRealtime(); clickText("剧集"); awaitText(browser, "Spring · 剧集测试");
        require(android.os.SystemClock.elapsedRealtime() - start < 2500, "new_category_cancels_pending_http");
        Thread.sleep(3200);
        require(!containsOnMain(browser, "Spring · 过期结果"), "late_category_response_does_not_replace_selection");
        capture("media-fixed-categories");
        clickText(getTargetContext().getString(R.string.media_search)); enterText("sample"); clickText(getTargetContext().getString(R.string.media_search));
        awaitText(browser, "2019 · 2 个来源");
        clickText("Spring · 播放测试"); awaitText(browser, "第二片库"); clickText("第二片库");
        awaitText(browser, "✓ 第二线路");
        require(containsOnMain(browser, "第二集"), "aggregate_source_switch_uses_own_detail_and_episodes");
        capture("media-aggregate-detail");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); awaitText(browser, "2019 · 2 个来源");
        require(containsOnMain(browser, getTargetContext().getString(R.string.media_retry_sources)), "partial_failure_keeps_results_and_retry");
        capture("media-cross-source-search");
        clickText("Spring · 播放测试"); awaitText(browser, "第二片库"); capture("media-before-invalid-import");
        clickText(getTargetContext().getString(R.string.media_settings)); clickText(getTargetContext().getString(R.string.media_source_config));
        clickText(getTargetContext().getString(R.string.media_add_source));
        enterText(base + "/invalid.json"); clickText(getTargetContext().getString(R.string.media_import));
        awaitText(browser, getTargetContext().getString(R.string.media_invalid_config));
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); awaitText(browser, "推荐"); awaitText(browser, "Spring · 播放测试");
        require(containsOnMain(browser, "剧集"), "failed_import_from_grouped_detail_returns_safely");
        clickText(getTargetContext().getString(R.string.media_settings)); clickText(getTargetContext().getString(R.string.media_content_sources));
        clickText("第二片库"); clickText(getTargetContext().getString(R.string.media_save));
        awaitText(browser, "2019 · 1 个来源");
        require(!containsOnMain(browser, "第二片库"), "sources_managed_in_settings_not_home_header");
        clickText(getTargetContext().getString(R.string.media_settings)); clickText(getTargetContext().getString(R.string.media_content_sources));
        clickText("第二片库"); clickText(getTargetContext().getString(R.string.media_save));
        awaitText(browser, "2019 · 2 个来源"); capture("media-unified-home");
    }
    private void awaitCatalog(MediaCatalog catalog) throws Exception {
        for (int i = 0; i < 150; i++) {
            final boolean[] pending = {true}; runOnMainSync(() -> pending[0] = catalog.loading());
            if (!pending[0]) return; Thread.sleep(100);
        }
        throw new AssertionError("Catalog request did not finish");
    }
    private void detailDiscoveryCheck(MediaEngine engine) throws Exception {
        engine.importSource(base + "/discovery-config.json");
        Activity browser = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            awaitText(browser, "2019 · 1 个来源"); clickText("Spring · 播放测试");
            awaitText(browser, "第二片库"); clickText("第二片库"); awaitText(browser, "✓ 第二线路");
            require(containsOnMain(browser, "第二集"), "details_discover_sources_missing_from_home_page");
        } finally { runOnMainSync(browser::finish); }
    }
    private MediaCatalog loadCatalog(MediaEngine engine, java.util.concurrent.ExecutorService scripts, MediaCatalog.Library library,
            MediaCatalog.Mode mode, String query) throws Exception {
        MediaCatalog catalog = new MediaCatalog(engine, scripts, library, mode, query, () -> { });
        runOnMainSync(() -> catalog.load(false)); awaitCatalog(catalog); return catalog;
    }
    private void cacheMode(String query) throws Exception {
        try (java.io.InputStream input = new java.net.URL(base + "/cache-mode?" + query).openStream()) { input.readAllBytes(); }
    }
    private void posterQueueCheck() throws Exception {
        runOnMainSync(() -> {
            MediaImages.cancelPending();
            for (int i = 0; i < 3; i++) MediaImages.load(new android.widget.ImageView(getTargetContext()), base + "/slow-poster.jpg?slot=" + i);
        });
        for (int i = 0; i < 40 && fixtureStats().optInt("slow-posters") < 3; i++) Thread.sleep(50);
        require(fixtureStats().optInt("slow-posters") == 3, "old_page_poster_requests_are_in_flight");
        android.widget.ImageView[] target = {null};
        runOnMainSync(() -> {
            MediaImages.cancelPending(); target[0] = new android.widget.ImageView(getTargetContext());
            MediaImages.load(target[0], base + "/poster.jpg?new-page");
        });
        boolean loaded = false;
        for (int i = 0; i < 40; i++) {
            final boolean[] ready = {false}; runOnMainSync(() -> ready[0] = target[0].getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
            if (ready[0]) { loaded = true; break; }
            Thread.sleep(50);
        }
        require(loaded, "new_page_poster_does_not_wait_for_old_requests");
        runOnMainSync(MediaImages::cancelPending);
    }
    private void visiblePosterCheck(MediaEngine engine) throws Exception {
        engine.importSource(base + "/poster-grid-config.json");
        int before = fixtureStats().optInt("posters");
        Activity browser = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            awaitText(browser, getTargetContext().getString(R.string.media_catalog_complete, 100));
            java.lang.reflect.Field field = MediaActivity.class.getDeclaredField("catalogGrid"); field.setAccessible(true);
            android.widget.GridLayout grid = (android.widget.GridLayout) field.get(browser);
            android.widget.ImageView first = (android.widget.ImageView) ((android.widget.LinearLayout) grid.getChildAt(0)).getChildAt(0);
            boolean loaded = false;
            for (int i = 0; i < 60; i++) {
                final boolean[] ready = {false}; runOnMainSync(() -> ready[0] = first.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
                if (ready[0]) { loaded = true; break; }
                Thread.sleep(50);
            }
            require(loaded && fixtureStats().optInt("posters") - before < 30, "only_visible_posters_are_requested_for_large_grid");
            runOnMainSync(() -> ((android.widget.ScrollView) grid.getParent().getParent()).fullScroll(android.view.View.FOCUS_DOWN));
            boolean released = false;
            for (int i = 0; i < 40; i++) {
                final boolean[] empty = {false}; runOnMainSync(() -> empty[0] = first.getDrawable() == null);
                if (empty[0]) { released = true; break; }
                Thread.sleep(50);
            }
            require(released, "offscreen_posters_release_their_decoded_bitmaps");
        } finally { runOnMainSync(browser::finish); }
    }
    private void catalogCacheCheck(MediaEngine engine) throws Exception {
        java.util.concurrent.ExecutorService scripts = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.List<MediaCatalog> queries = new java.util.ArrayList<>();
        try {
            engine.importSource(base + "/cache-config.json");
            MediaCatalog initial = loadCatalog(engine, scripts, new MediaCatalog.Library(), MediaCatalog.Mode.HOME, ""); queries.add(initial);
            require(initial.results.groups.values().iterator().next().movie.title.equals("Cache · 旧内容"), "catalog_cache_initial_network_result");
            Site site = engine.site("cached"); String key = engine.catalogKey(site, "HOME", "");
            require(engine.catalogCache.get(key) != null && engine.catalogCache.get(engine.catalogKey(site, "CATEGORY", "movie")) == null,
                    "catalog_cache_is_persistent_and_query_scoped");
            cacheMode("mode=fresh");
            MediaCatalog refreshing = new MediaCatalog(engine, scripts, new MediaCatalog.Library(), MediaCatalog.Mode.HOME, "", () -> { }); queries.add(refreshing);
            runOnMainSync(() -> refreshing.load(false));
            boolean restored = false;
            for (int i = 0; i < 50; i++) {
                final boolean[] visible = {false};
                runOnMainSync(() -> visible[0] = refreshing.loading() && refreshing.showingCache() && !refreshing.results.groups.isEmpty());
                if (visible[0]) { restored = true; break; }
                Thread.sleep(20);
            }
            require(restored, "catalog_cache_appears_before_slow_network_refresh");
            awaitCatalog(refreshing);
            require(refreshing.results.groups.size() == 1 && refreshing.results.groups.values().iterator().next().movie.title.equals("Cache · 新内容")
                    && !refreshing.showingCache(), "fresh_catalog_replaces_cached_source_without_old_duplicates");
            cacheMode("mode=fail");
            MediaCatalog offline = loadCatalog(engine, scripts, new MediaCatalog.Library(), MediaCatalog.Mode.HOME, ""); queries.add(offline);
            require(offline.showingCache() && offline.failures().size() == 1 && offline.results.groups.size() == 1,
                    "failed_refresh_retains_cached_catalog_and_retry_state");
            Activity browser = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                awaitText(browser, "Cache · 新内容"); awaitText(browser, getTargetContext().getString(R.string.media_retry_sources));
                awaitText(browser, getTargetContext().getString(R.string.media_catalog_complete, 1)
                        + " · " + getTargetContext().getString(R.string.media_cached_content));
                require(true, "offline_cached_catalog_is_rendered");
                capture("media-cached-library");
            } finally { runOnMainSync(browser::finish); }
            cacheMode("mode=old"); engine.catalogCache.put(key, new byte[]{1});
            MediaCatalog repaired = loadCatalog(engine, scripts, new MediaCatalog.Library(), MediaCatalog.Mode.HOME, ""); queries.add(repaired);
            require(!repaired.showingCache() && repaired.results.groups.values().iterator().next().movie.title.equals("Cache · 旧内容"),
                    "damaged_catalog_cache_recovers_from_network");
            int before = fixtureStats().optInt("cache-detail"); engine.detail(site, "旧内容"); engine.detail(site, "旧内容");
            require(fixtureStats().optInt("cache-detail") == before + 2, "details_are_refetched_for_current_episode_addresses");
            cacheMode("revision=2"); engine.refreshSource();
            require(!key.equals(engine.catalogKey(engine.site("cached"), "HOME", ""))
                    && engine.catalogCache.get(engine.catalogKey(engine.site("cached"), "HOME", "")) == null,
                    "updated_configuration_does_not_reuse_old_catalog_cache");
        } finally {
            for (MediaCatalog query : queries) runOnMainSync(query::cancel);
            scripts.shutdownNow();
        }
    }
    private void unifiedLibraryCheck(MediaEngine engine) throws Exception {
        java.util.concurrent.ExecutorService scripts = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.List<MediaCatalog> queries = new java.util.ArrayList<>();
        MediaCatalog.Library library = new MediaCatalog.Library();
        try {
            engine.enableSites(java.util.Set.of("fixture", "second", "failed"));
            MediaCatalog home = loadCatalog(engine, scripts, library, MediaCatalog.Mode.HOME, ""); queries.add(home);
            require(home.results.groups.size() == 1 && home.results.groups.values().iterator().next().sources.size() == 2
                    && home.failures().size() == 1, "unified_home_aggregates_and_tolerates_source_failure");
            runOnMainSync(() -> home.load(false)); awaitCatalog(home);
            require(home.results.groups.size() == 2, "unified_home_load_more");
            MediaCatalog movies = loadCatalog(engine, scripts, library, MediaCatalog.Mode.CATEGORY, "movie"); queries.add(movies);
            require(movies.results.groups.size() == 1 && movies.results.groups.values().iterator().next().sources.size() == 2,
                    "unified_movie_category_maps_distinct_provider_ids");
            JSONObject stats = fixtureStats();
            require(stats.optInt("category:/api:film") > 0 && stats.optInt("category:/second:cinema") > 0, "provider_category_ids_are_not_interchanged");
            MediaCatalog series = loadCatalog(engine, scripts, library, MediaCatalog.Mode.CATEGORY, "series"); queries.add(series);
            require(series.results.groups.values().iterator().next().movie.title.equals("Spring · 剧集测试")
                    && series.results.groups.values().iterator().next().sources.size() == 2, "series_aliases_merge_across_sources");
            Site override = new Site(new JSONObject().put("key", "override").put("api", base + "/api")
                    .put("categories", new JSONObject().put("movie", new org.json.JSONArray().put("custom-film"))), "");
            require(MediaCategories.targets(override, "movie", java.util.List.of()).equals(java.util.List.of("custom-film")), "config_can_override_category_mapping");
            engine.enableSites(java.util.Set.of("fixture"));
            MediaCatalog disabled = loadCatalog(engine, scripts, library, MediaCatalog.Mode.HOME, ""); queries.add(disabled);
            require(disabled.total() == 1 && disabled.results.groups.values().iterator().next().sources.size() == 1, "disabled_sources_excluded_from_home");
            engine.refreshSource(); require(engine.enabledSites().size() == 1, "source_preferences_survive_config_refresh");
            engine.useBuiltIn(true); require(engine.enabledSites().size() == 6, "source_preferences_are_config_scoped"); engine.useBuiltIn(false);
            engine.enableSites(java.util.Set.of());
            MediaCatalog empty = loadCatalog(engine, scripts, library, MediaCatalog.Mode.HOME, ""); queries.add(empty);
            require(empty.total() == 0 && empty.results.groups.isEmpty(), "all_sources_disabled_is_empty");
            engine.importSource(base + "/taxonomy-config.json");
            MediaCatalog.Library taxonomyLibrary = new MediaCatalog.Library();
            MediaCatalog taxonomy = loadCatalog(engine, scripts, taxonomyLibrary, MediaCatalog.Mode.CATEGORY, "movie"); queries.add(taxonomy);
            require(taxonomy.results.groups.values().iterator().next().movie.title.equals("action 第1页"), "empty_parent_does_not_hide_genre_children");
            runOnMainSync(() -> taxonomy.load(false)); awaitCatalog(taxonomy);
            require(taxonomy.results.groups.size() == 2, "load_more_rotates_through_child_categories");
            runOnMainSync(() -> taxonomy.load(false)); awaitCatalog(taxonomy);
            require(taxonomy.hasMore(), "empty_parent_does_not_end_child_pagination");
            runOnMainSync(() -> taxonomy.load(false)); awaitCatalog(taxonomy);
            require(taxonomy.results.groups.size() == 3, "child_second_page_is_retained");
            MediaCatalog comedy = loadCatalog(engine, scripts, taxonomyLibrary, MediaCatalog.Mode.CATEGORY, "movie:喜剧"); queries.add(comedy);
            require(comedy.results.groups.size() == 1 && comedy.results.groups.values().iterator().next().movie.title.equals("comedy 第1页"),
                    "genre_filter_uses_only_matching_provider_ids");
            runOnMainSync(() -> comedy.load(false)); awaitCatalog(comedy);
            require(comedy.results.groups.size() == 2 && !comedy.hasMore(), "genre_filter_preserves_own_pagination");
            Site taxonomySite = engine.site("taxonomy");
            require(MediaCategories.genres(taxonomySite, "movie", taxonomyLibrary.categories.get("taxonomy")).equals(java.util.List.of("动作", "喜剧")),
                    "genre_choices_exclude_parent_and_normalize_suffixes");
            require(MediaCategories.targets(taxonomySite, "movie:悬疑", taxonomyLibrary.categories.get("taxonomy")).isEmpty(),
                    "missing_genre_does_not_fall_back_to_all_movies");
            java.util.List<Category> aliases = java.util.List.of(new Category(new JSONObject().put("type_id", "alias-action").put("type_name", "动作电影")));
            require(MediaCategories.targets(taxonomySite, "movie:动作", aliases).equals(java.util.List.of("alias-action")),
                    "genre_alias_retains_each_providers_own_id");
            setInTouchMode(false);
            Activity genres = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                awaitText(genres, "电影"); clickText("电影"); awaitText(genres, "动作"); awaitText(genres, "喜剧");
                clickText("喜剧"); awaitText(genres, "comedy 第1页");
                java.lang.reflect.Field grid = MediaActivity.class.getDeclaredField("catalogGrid"); grid.setAccessible(true);
                runOnMainSync(() -> { try { ((android.widget.GridLayout) grid.get(genres)).getChildAt(0).requestFocus(); }
                    catch (IllegalAccessException error) { throw new RuntimeException(error); } });
                sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP); waitForIdleSync();
                final String[] focusedGenre = {""};
                runOnMainSync(() -> {
                    android.view.View focused = genres.getCurrentFocus();
                    focusedGenre[0] = focused instanceof android.widget.Button ? ((android.widget.Button) focused).getText().toString()
                            : focused == null ? "none" : focused.getClass().getSimpleName();
                });
                require(focusedGenre[0].equals("喜剧"), "remote_up_returns_to_selected_genre_" + focusedGenre[0]);
                clickText("动作"); awaitText(genres, "action 第1页");
                require(!containsOnMain(genres, "comedy 第1页"), "genre_switch_replaces_previous_results_without_back");
                capture("media-restored-genres");
                clickText("全部"); awaitText(genres, "action 第1页");
            } finally { runOnMainSync(genres::finish); }
            engine.importSource(base + "/refresh-config.json"); engine.refreshSource();
            require(engine.site("refresh").name.equals("更新后的配置"), "refresh_downloads_original_config_url");
            java.io.File cache = new java.io.File(getTargetContext().getFilesDir(), "media/config.json");
            byte[] before = java.nio.file.Files.readAllBytes(cache.toPath()); boolean rejected = false;
            try { engine.refreshSource(); } catch (Exception expected) { rejected = true; }
            require(rejected && java.util.Arrays.equals(before, java.nio.file.Files.readAllBytes(cache.toPath()))
                    && engine.site("refresh").name.equals("更新后的配置"), "failed_refresh_preserves_valid_cached_config");
        } finally {
            runOnMainSync(() -> { for (MediaCatalog query : queries) query.cancel(); }); scripts.shutdownNow();
            engine.importSource(base + "/search-config.json"); engine.enableSites(java.util.Set.of("fixture", "second", "failed", "ignored"));
        }
    }
    private boolean containsOnMain(Activity activity, String value) {
        final boolean[] found = {false}; runOnMainSync(() -> found[0] = contains(activity.getWindow().getDecorView(), value)); return found[0];
    }
    private JSONObject fixtureStats() throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(base + "/stats").openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            return new JSONObject(reader.readLine());
        } finally { connection.disconnect(); }
    }
    private void panelCheck(MediaEngine engine) throws Exception {
        engine.importSource(base + "/search-config.json"); engine.enableSites(java.util.Set.of("fixture"));
        Activity browser = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity player = null;
        try {
            awaitText(browser, "2019 · 1 个来源");
            clickText(getTargetContext().getString(R.string.media_settings)); capture("media-panel-settings");
            touchText(getTargetContext().getString(R.string.media_source_config));
            awaitNode(getTargetContext().getString(R.string.media_scan_source), false);
            require(true, "unfocused_panel_item_opens_with_one_touch");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); clickText(getTargetContext().getString(R.string.media_settings));
            clickText(getTargetContext().getString(R.string.media_content_sources)); capture("media-panel-sources");
            clickText("第二片库"); clickText(getTargetContext().getString(R.string.cancel)); waitForIdleSync();
            require(engine.enabledSites().size() == 1, "source_panel_cancel_does_not_save_toggles");
            clickText(getTargetContext().getString(R.string.media_settings)); clickText(getTargetContext().getString(R.string.media_content_sources));
            clickText("第二片库"); clickText(getTargetContext().getString(R.string.media_save));
            awaitText(browser, "2019 · 2 个来源"); require(engine.enabledSites().size() == 2, "source_panel_save_commits_toggles");
            clickText(getTargetContext().getString(R.string.media_search)); capture("media-panel-search");
            enterText("Spring"); clickText(getTargetContext().getString(R.string.media_search));
            awaitText(browser, "Spring · 播放测试"); require(true, "custom_search_panel_submits_query");
            clickText(getTargetContext().getString(R.string.media_settings)); clickText(getTargetContext().getString(R.string.media_source_config));
            capture("media-panel-config"); clickText(getTargetContext().getString(R.string.media_add_source)); capture("media-panel-input");
            clickText(getTargetContext().getString(R.string.media_import));
            awaitNode(getTargetContext().getString(R.string.media_invalid_address), false);
            require(true, "source_input_uses_inline_validation");
            enterText(base + "/search-config.json"); clickText(getTargetContext().getString(R.string.media_import));
            awaitText(browser, "2019 · 2 个来源");
            player = startActivitySync(new Intent(getTargetContext(), MediaPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("site", "fixture").putExtra("id", "sample").putExtra("line", 0).putExtra("episode", 0));
            awaitText(player, "Spring · 播放测试  1");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP); waitForIdleSync();
            clickText(getTargetContext().getString(R.string.media_lines));
            capture("media-panel-lines"); waitForIdleSync();
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            waitForIdleSync();
            Field selected = MediaPlayerActivity.class.getDeclaredField("line"); selected.setAccessible(true);
            require(selected.getInt(player) == 1, "custom_line_panel_supports_direction_and_confirm_keys");
            Field playback = MediaPlayerActivity.class.getDeclaredField("player"); playback.setAccessible(true);
            final boolean[] advancing = {false}; Activity owner = player;
            for (int i = 0; i < 50 && !advancing[0]; i++) {
                runOnMainSync(() -> {
                    try {
                        ExoPlayer current = (ExoPlayer) playback.get(owner);
                        advancing[0] = current != null && current.isPlaying() && current.getCurrentPosition() > 500;
                    } catch (IllegalAccessException error) { throw new AssertionError(error); }
                });
                Thread.sleep(200);
            }
            require(advancing[0], "playback_continues_after_custom_line_selection");
        } finally {
            if (player != null) { Activity owner = player; runOnMainSync(owner::finish); }
            runOnMainSync(browser::finish);
        }
    }
    private ExoPlayer playback(Activity activity) throws Exception {
        Field field = MediaPlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
        return (ExoPlayer) field.get(activity);
    }
    private void playerRemoteCheck(MediaEngine engine) throws Exception {
        engine.importSource(base + "/search-config.json"); setInTouchMode(false);
        Activity activity = startActivitySync(new Intent(getTargetContext(), MediaPlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("site", "fixture").putExtra("id", "sample"));
        try {
            final boolean[] ready = {false};
            for (int i = 0; i < 100 && !ready[0]; i++) {
                runOnMainSync(() -> { try { ExoPlayer p = playback(activity); ready[0] = p != null && p.isPlaying(); }
                    catch (Exception e) { throw new AssertionError(e); } });
                Thread.sleep(100);
            }
            require(ready[0], "remote_fixture_video_is_playing");
            Field field = MediaPlayerActivity.class.getDeclaredField("video"); field.setAccessible(true);
            androidx.media3.ui.PlayerView video = (androidx.media3.ui.PlayerView) field.get(activity);
            runOnMainSync(() -> { video.hideController(); video.requestFocus(); });
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER); waitForIdleSync();
            runOnMainSync(() -> { try { require(!playback(activity).getPlayWhenReady(), "confirm_pauses_without_first_opening_controls"); }
                catch (Exception e) { throw new AssertionError(e); } });
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER); waitForIdleSync();
            runOnMainSync(() -> { try { require(playback(activity).getPlayWhenReady(), "confirm_resumes_playback");
                playback(activity).pause(); playback(activity).seekTo(2000); video.hideController(); video.requestFocus(); }
                catch (Exception e) { throw new AssertionError(e); } });
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT); waitForIdleSync();
            runOnMainSync(() -> { try { require(playback(activity).getCurrentPosition() < 500, "left_seeks_backward_without_focus_navigation"); }
                catch (Exception e) { throw new AssertionError(e); } });
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); waitForIdleSync();
            runOnMainSync(() -> { try { require(playback(activity).getCurrentPosition() > 2000, "right_seeks_forward");
                require(!playback(activity).getPlayWhenReady(), "seek_preserves_paused_state"); }
                catch (Exception e) { throw new AssertionError(e); } });
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP); waitForIdleSync();
            clickText(getTargetContext().getString(R.string.media_episodes));
            awaitNode("2", false); waitForIdleSync(); capture("media-episode-picker");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            awaitText(activity, "Spring · 播放测试  2");
            Field selected = MediaPlayerActivity.class.getDeclaredField("episode"); selected.setAccessible(true);
            require(selected.getInt(activity) == 1, "episode_picker_supports_remote_keys");
            runOnMainSync(video::showController); Thread.sleep(350); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); waitForIdleSync();
            require(!activity.isFinishing(), "first_back_hides_controls_without_exiting");
            runOnMainSync(() -> require(!video.isControllerFullyVisible(), "back_hides_controller"));
            capture("media-remote-player");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); waitForIdleSync();
            require(activity.isFinishing(), "second_back_exits_player");
        } finally { runOnMainSync(activity::finish); }
    }
    private void startupCacheCheck(MediaEngine engine) throws Exception {
        engine.importSource(base + "/slow-entry-config.json"); engine.autoRefresh(true);
        // The fixture's first import is fast; subsequent checks deliberately stall.
        android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences("media", 0);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("source_checked:") || key.startsWith("source_updated:")) editor.putLong(key, 1);
        }
        editor.commit();
        long started = android.os.SystemClock.elapsedRealtime();
        Activity browser = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            awaitText(browser, "片库 · 已加载 1 部影片");
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            require(elapsed < 2000, "slow_config_update_does_not_delay_catalog_" + elapsed + "ms");
        } finally { runOnMainSync(browser::finish); waitForIdleSync(); }
        engine.importSource(base + "/stage-entry-config.json");
        forceSourceUpdateDue();
        engine.prefetchSourceUpdate(new MediaEngine.Cancellation());
        require(engine.site("stage").name.equals("初始来源"), "prefetch_does_not_replace_live_sources");
        engine.applyPendingSourceUpdate();
        require(engine.site("stage").name.equals("更新来源"), "staged_update_activates_on_next_entry");
        forceSourceUpdateDue(); engine.prefetchSourceUpdate(new MediaEngine.Cancellation());
        engine.importSource(base + "/search-config.json"); engine.applyPendingSourceUpdate();
        require(engine.site("fixture") != null, "stale_update_cannot_overwrite_new_import");
        java.nio.file.Files.write(new java.io.File(getTargetContext().getFilesDir(), "media/pending-config.json").toPath(), new byte[]{1, 2, 3});
        engine.applyPendingSourceUpdate();
        require(engine.site("fixture") != null, "damaged_pending_update_keeps_valid_library");
    }
    private void waitPlaying(Activity activity) throws Exception {
        final boolean[] ready = {false};
        for (int i = 0; i < 150 && !ready[0]; i++) {
            runOnMainSync(() -> { try { ExoPlayer p = playback(activity); ready[0] = p != null && p.isPlaying() && p.getCurrentPosition() > 500; }
                catch (Exception error) { throw new AssertionError(error); } });
            Thread.sleep(100);
        }
        require(ready[0], "reliability_actual_video_advances");
    }
    private void reliabilityCheck(MediaEngine engine) throws Exception {
        getTargetContext().getSharedPreferences("media_quality", 0).edit().clear().commit();
        engine.importSource(base + "/reliability-config.json"); setInTouchMode(false);
        Site slow = engine.site("slow"), fast = engine.site("fast");
        MediaSourceQuality transport = new MediaSourceQuality(getTargetContext(), engine);
        JSONObject hls = new JSONObject().put("url", base + "/quality-master.m3u8");
        transport.probe(slow, "HLS测试", hls, 4000);
        require(fixtureStats().optInt("/quality-master.m3u8") == 1 && fixtureStats().optInt("/quality-list.m3u8") == 1
                && fixtureStats().optInt("/quality-segment.bin") == 1, "hls_probe_follows_playlists_and_reads_bounded_media");
        transport.probe(slow, "HLS测试", hls, 4000);
        require(fixtureStats().optInt("/quality-segment.bin") == 1, "fresh_probe_cache_avoids_duplicate_downloads");
        boolean invalid = false;
        try { transport.probe(slow, "空播放清单", new JSONObject().put("url", base + "/quality-broken.m3u8"), 4000); }
        catch (java.io.IOException expected) { invalid = true; }
        require(invalid, "empty_hls_playlist_is_not_a_successful_probe");
        Movie movie = engine.detail(slow, "slow-film");
        require(MediaPlaybackSources.matchEpisode(movie.lines.get(0), 1, movie.lines.get(1), false) == 1,
                "episode_label_normalizes_second_episode");
        Line otherEpisode = new Line("different"); otherEpisode.episodes.add(new Episode("第03集", "unused"));
        Line oneEpisode = new Line("current"); oneEpisode.episodes.add(new Episode("第02集", "unused"));
        require(MediaPlaybackSources.matchEpisode(oneEpisode, 0, otherEpisode, false) == -1,
                "single_episode_series_does_not_match_a_different_episode");
        String references = new org.json.JSONArray().put(new JSONObject().put("site", "fast").put("id", "fast-film"))
                .put(new JSONObject().put("site", "wrong").put("id", "wrong-film")).toString();
        java.util.List<MediaPlaybackSources.Candidate> candidates = MediaPlaybackSources.candidates(engine, slow, movie, 0, 1, references);
        require(candidates.stream().noneMatch(candidate -> candidate.site.key.equals("wrong")), "different_year_is_never_a_switch_candidate");
        Activity activity = startActivitySync(new Intent(getTargetContext(), MediaPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("site", "slow").putExtra("id", "slow-film").putExtra("episode", 1).putExtra("sources", references));
        try {
            waitPlaying(activity);
            Field siteKey = MediaPlayerActivity.class.getDeclaredField("siteKey"); siteKey.setAccessible(true);
            require(siteKey.get(activity).equals("fast"), "automatic_probe_prefers_the_faster_source");
            MediaSourceQuality quality = new MediaSourceQuality(getTargetContext(), engine);
            require(quality.score(fast, "原画") == new MediaSourceQuality(getTargetContext(), engine).score(fast, "原画"),
                    "local_quality_records_persist_across_instances");
            Field videoField = MediaPlayerActivity.class.getDeclaredField("video"); videoField.setAccessible(true);
            androidx.media3.ui.PlayerView video = (androidx.media3.ui.PlayerView) videoField.get(activity);
            runOnMainSync(() -> { try {
                ExoPlayer player = playback(activity); player.pause(); player.seekTo(2500);
                java.lang.reflect.Method remember = MediaPlayerActivity.class.getDeclaredMethod("remember"); remember.setAccessible(true); remember.invoke(activity);
                // A real HTTP 404 goes through Media3's error listener, rather than calling recovery directly.
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(base + "/unavailable-video.mp4"), 2500);
                player.prepare(); player.play();
            } catch (Exception error) { throw new AssertionError(error); } });
            awaitNode(getTargetContext().getString(R.string.media_stay), false);
            require(siteKey.get(activity).equals("fast"), "playback_error_does_not_switch_without_confirmation");
            Field panelField = MediaPlayerActivity.class.getDeclaredField("recoveryPanel"); panelField.setAccessible(true);
            android.app.Dialog panel = (android.app.Dialog) panelField.get(activity);
            final boolean[] reminder = {false};
            runOnMainSync(() -> reminder[0] = contains(panel.getWindow().getDecorView(),
                    getTargetContext().getString(R.string.media_switch_source_description, "00:02")));
            require(reminder[0], "switch_prompt_displays_saved_progress"); capture("media-source-confirmation");
            clickText(getTargetContext().getString(R.string.media_stay)); waitForIdleSync();
            require(siteKey.get(activity).equals("fast") && panelField.get(activity) == null, "cancel_keeps_current_source");
            runOnMainSync(video::showController); clickText(getTargetContext().getString(R.string.media_switch_source));
            awaitNode("慢片库 · 备用", false); waitForIdleSync();
            clickText("慢片库 · 备用");
            waitPlaying(activity);
            require(siteKey.get(activity).equals("slow"), "confirmed_switch_uses_other_provider");
            Field episodeField = MediaPlayerActivity.class.getDeclaredField("episode"); episodeField.setAccessible(true);
            require(episodeField.getInt(activity) == 1, "confirmed_switch_preserves_episode_not_array_guess");
            final long[] resumed = {0}; final boolean[] decoded = {false};
            runOnMainSync(() -> { try {
                ExoPlayer player = playback(activity); resumed[0] = player.getCurrentPosition();
                DecoderCounters counters = player.getVideoDecoderCounters();
                decoded[0] = counters != null && counters.renderedOutputBufferCount > 0;
            } catch (Exception error) { throw new AssertionError(error); } });
            require(resumed[0] >= 2500 && resumed[0] < 7000, "confirmed_switch_resumes_saved_position");
            require(decoded[0], "confirmed_switch_decodes_real_video");
            capture("media-source-restored");
        } finally { runOnMainSync(activity::finish); }
        MediaSourceQuality quality = new MediaSourceQuality(getTargetContext(), engine);
        quality.automatic(false);
        require(!new MediaSourceQuality(getTargetContext(), engine).automatic(), "automatic_preference_persists");
        quality.automatic(true);
        Activity browser = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity launched = null;
        ActivityMonitor monitor = addMonitor(MediaPlayerActivity.class.getName(), null, false);
        try {
            awaitText(browser, "片库 · 已加载 2 部影片");
            awaitNode("2019 · 2 个来源", false); clickText("2019 · 2 个来源"); awaitNode("2", false); waitForIdleSync();
            clickText("2"); launched = waitForMonitorWithTimeout(monitor, 10000);
            require(launched != null, "library_episode_opens_real_player");
            String passed = launched.getIntent().getStringExtra("sources");
            require(passed != null && passed.contains("fast") && passed.contains("slow"), "library_passes_matched_sources_before_cancelling_lookup");
            Activity firstLaunch = launched; runOnMainSync(firstLaunch::finish); launched = null;
            waitForIdleSync(); monitor = addMonitor(MediaPlayerActivity.class.getName(), null, false);
            clickText("2"); launched = waitForMonitorWithTimeout(monitor, 10000);
            require(launched != null, "returning_to_detail_can_play_again");
            passed = launched.getIntent().getStringExtra("sources");
            require(passed != null && passed.contains("fast") && passed.contains("slow"), "replaying_detail_preserves_alternative_sources");
        } finally {
            removeMonitor(monitor);
            if (launched != null) { Activity owner = launched; runOnMainSync(owner::finish); }
            runOnMainSync(browser::finish);
        }
    }
    private void stallCheck(MediaEngine engine) throws Exception {
        engine.importSource(base + "/stall-config.json"); new MediaSourceQuality(getTargetContext(), engine).automatic(false);
        Activity activity = startActivitySync(new Intent(getTargetContext(), MediaPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("site", "stall").putExtra("id", "stall-film"));
        try {
            awaitNode(getTargetContext().getString(R.string.media_stay), false);
            Field field = MediaPlayerActivity.class.getDeclaredField("recoveryPanel"); field.setAccessible(true);
            require(field.get(activity) != null, "initial_buffer_timeout_opens_confirmation");
            final boolean[] paused = {false};
            runOnMainSync(() -> { try { paused[0] = !playback(activity).getPlayWhenReady(); } catch (Exception error) { throw new AssertionError(error); } });
            require(paused[0], "stalled_playback_pauses_while_awaiting_confirmation");
            awaitNode(getTargetContext().getString(R.string.media_no_alternative), false);
            require(true, "no_matching_alternative_has_explicit_recovery_options");
            clickText(getTargetContext().getString(R.string.media_stay)); waitForIdleSync();
            require(field.get(activity) == null, "stalled_playback_can_cancel_without_switching");
        } finally { runOnMainSync(activity::finish); new MediaSourceQuality(getTargetContext(), engine).automatic(true); }
    }
    private void forceSourceUpdateDue() {
        android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences("media", 0);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("source_checked:") || key.startsWith("source_updated:")) editor.putLong(key, 1);
        }
        editor.commit();
    }
    private void sourceUpdateCheck(MediaEngine engine) throws Exception {
        engine.importSource(base + "/auto-config.json");
        engine.autoRefresh(true);
        engine.select("keep"); engine.enableSites(java.util.Set.of("keep"));
        engine.remember(engine.site("keep"), new Movie(new JSONObject().put("vod_id", "sample").put("vod_name", "Spring")), 0, 0, 60000, 120000);
        long now = System.currentTimeMillis();
        MediaEngine.Cancellation cancellation = new MediaEngine.Cancellation();
        require(!engine.refreshSourceIfDue(cancellation, now) && fixtureStats().optInt("/auto-config.json") == 1,
                "fresh_import_does_not_download_again");
        now += java.util.concurrent.TimeUnit.HOURS.toMillis(25);
        require(engine.refreshSourceIfDue(cancellation, now) && engine.site("keep").name.equals("更新来源"),
                "daily_check_fetches_fixed_url");
        require(engine.site("keep").api.equals(base + "/api"), "automatic_update_resolves_relative_api");
        require(engine.selected().equals("keep") && engine.enabledSites().size() == 1 && engine.enabledSites().get(0).key.equals("keep"),
                "automatic_update_preserves_selection_and_disabled_sources");
        require(engine.history().getJSONObject(0).getLong("position") == 60000, "automatic_update_preserves_playback_history");
        require(!engine.refreshSourceIfDue(cancellation, now + 1000) && fixtureStats().optInt("/auto-config.json") == 2,
                "successful_update_waits_24_hours");
        now += java.util.concurrent.TimeUnit.HOURS.toMillis(25);
        java.io.File cache = new java.io.File(getTargetContext().getFilesDir(), "media/config.json");
        byte[] before = java.nio.file.Files.readAllBytes(cache.toPath());
        require(!engine.refreshSourceIfDue(cancellation, now), "empty_update_is_rejected_quietly");
        require(!engine.refreshSourceIfDue(cancellation, now + 1000) && fixtureStats().optInt("/auto-config.json") == 3,
                "failed_update_waits_one_hour_before_retry");
        now += java.util.concurrent.TimeUnit.HOURS.toMillis(1);
        require(!engine.refreshSourceIfDue(cancellation, now), "malformed_update_is_rejected_quietly");
        now += java.util.concurrent.TimeUnit.HOURS.toMillis(1);
        require(!engine.refreshSourceIfDue(cancellation, now), "http_failure_keeps_cached_sources");
        now += java.util.concurrent.TimeUnit.HOURS.toMillis(1);
        long start = android.os.SystemClock.elapsedRealtime();
        require(!engine.refreshSourceIfDue(cancellation, now) && android.os.SystemClock.elapsedRealtime() - start < 4500,
                "slow_update_cannot_block_browsing_beyond_timeout");
        require(java.util.Arrays.equals(before, java.nio.file.Files.readAllBytes(cache.toPath())) && engine.site("keep").name.equals("更新来源"),
                "all_failed_updates_preserve_cached_file_and_active_sources");
        now += java.util.concurrent.TimeUnit.HOURS.toMillis(1);
        engine.autoRefresh(false);
        require(!engine.refreshSourceIfDue(cancellation, now) && fixtureStats().optInt("/auto-config.json") == 6,
                "automatic_updates_can_be_disabled");
        engine.autoRefresh(true);
        MediaEngine.Cancellation cancelled = new MediaEngine.Cancellation(); cancelled.cancel();
        boolean rejected = false;
        try { engine.refreshSourceIfDue(cancelled, now); } catch (java.io.InterruptedIOException expected) { rejected = true; }
        require(rejected && fixtureStats().optInt("/auto-config.json") == 6, "cancelled_entry_does_not_download_update");
        require(engine.refreshSourceIfDue(cancellation, now) && fixtureStats().optInt("/auto-config.json") == 7,
                "automatic_update_recovers_after_server_failure");
        engine.useBuiltIn(true);
        require(!engine.refreshSourceIfDue(cancellation, now + java.util.concurrent.TimeUnit.DAYS.toMillis(2))
                && fixtureStats().optInt("/auto-config.json") == 7, "bundled_catalog_never_contacts_saved_custom_url");
        engine.useBuiltIn(false);
        engine.refreshSource();
        Activity browser = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            awaitText(browser, "片库 · 已加载 1 部影片");
            clickText(getTargetContext().getString(R.string.media_settings)); clickText(getTargetContext().getString(R.string.media_source_config));
            clickText(getTargetContext().getString(R.string.media_auto_refresh_on));
            require(!engine.autoRefresh(), "settings_turns_off_automatic_source_updates");
            clickText(getTargetContext().getString(R.string.media_auto_refresh_off));
            require(engine.autoRefresh(), "settings_turns_on_automatic_source_updates");
            clickText(getTargetContext().getString(R.string.media_scan_source));
            Field field = MediaActivity.class.getDeclaredField("phoneServer"); field.setAccessible(true);
            com.sogud.bananatv.PhoneServer phone = (com.sogud.bananatv.PhoneServer) field.get(browser);
            require(phone != null && phone.url().length() > 45, "source_pairing_starts_only_on_request_with_random_token");
            final android.graphics.Bitmap[] qr = {null};
            runOnMainSync(() -> qr[0] = sourceQr(browser.getWindow().getDecorView()));
            if (qr[0] == null) {
                Field dialog = MediaActivity.class.getDeclaredField("phoneDialog"); dialog.setAccessible(true);
                android.app.Dialog panel = (android.app.Dialog) dialog.get(browser);
                runOnMainSync(() -> qr[0] = sourceQr(panel.getWindow().getDecorView()));
            }
            require(qr[0] != null, "source_pairing_displays_qr_bitmap");
            int[] pixels = new int[qr[0].getWidth() * qr[0].getHeight()];
            qr[0].getPixels(pixels, 0, qr[0].getWidth(), 0, 0, qr[0].getWidth(), qr[0].getHeight());
            com.google.zxing.BinaryBitmap code = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
                    new com.google.zxing.RGBLuminanceSource(qr[0].getWidth(), qr[0].getHeight(), pixels)));
            require(new com.google.zxing.qrcode.QRCodeReader().decode(code).getText().equals(phone.url()),
                    "source_qr_decodes_to_current_private_pairing_url");
            capture("media-source-qr");
            String[] page = phoneRequest(phone.url(), "GET", null, null);
            require(page[0].equals("200") && page[1].contains("配置地址") && !page[1].contains(base),
                    "phone_page_has_empty_address_field");
            require(phoneRequest(phone.url().replaceFirst("/[^/]+/$", "/wrong-token/"), "GET", null, null)[0].equals("404"),
                    "source_pairing_rejects_wrong_token");
            require(phoneRequest(phone.url() + "api/source", "POST", base + "/config.json", "https://example.invalid")[0].equals("403"),
                    "source_pairing_rejects_cross_origin_submission");
            require(phoneRequest(phone.url() + "api/source", "POST", "file:///private/config.json", null)[0].equals("400"),
                    "source_pairing_rejects_non_http_address");
            byte[] saved = java.nio.file.Files.readAllBytes(cache.toPath());
            require(phoneRequest(phone.url() + "api/source", "POST", base + "/invalid.json", null)[0].equals("200"),
                    "phone_submits_source_for_tv_validation");
            awaitPhoneState(phone, "error");
            require(java.util.Arrays.equals(saved, java.nio.file.Files.readAllBytes(cache.toPath())),
                    "invalid_phone_source_preserves_previous_config");
            require(phoneRequest(phone.url() + "api/source", "POST", base + "/search-config.json", null)[0].equals("200"),
                    "phone_can_retry_with_valid_address");
            awaitPhoneState(phone, "saved");
            require(engine.sites().size() == 4 && engine.site("fixture") != null, "phone_source_is_downloaded_validated_and_activated");
            clickText(getTargetContext().getString(R.string.media_done));
            waitForIdleSync();
            require(field.get(browser) == null, "closing_qr_dialog_stops_source_pairing_server");
        } finally { runOnMainSync(browser::finish); }
    }
    private android.graphics.Bitmap sourceQr(android.view.View view) {
        if (view instanceof android.widget.ImageView && getTargetContext().getString(R.string.media_scan_source).equals(view.getContentDescription())) {
            android.graphics.drawable.Drawable drawable = ((android.widget.ImageView) view).getDrawable();
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) return ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.graphics.Bitmap bitmap = sourceQr(group.getChildAt(i));
                if (bitmap != null) return bitmap;
            }
        }
        return null;
    }
    private String[] phoneRequest(String address, String method, String value, String origin) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(3000); connection.setRequestMethod(method);
        if (origin != null) connection.setRequestProperty("Origin", origin);
        if (value != null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = ("value=" + java.net.URLEncoder.encode(value, "UTF-8")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
        }
        int status = connection.getResponseCode();
        java.io.InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = "";
        if (stream != null) try (java.io.InputStream input = stream) { body = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        connection.disconnect(); return new String[]{String.valueOf(status), body};
    }
    private void awaitPhoneState(com.sogud.bananatv.PhoneServer phone, String expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            JSONObject response = new JSONObject(phoneRequest(phone.url() + "api/source", "GET", null, null)[1]);
            if (response.optString("state").equals(expected)) { require(true, "phone_source_state_" + expected); return; }
            Thread.sleep(100);
        }
        throw new AssertionError("Phone source import did not finish");
    }
    private void nativeUiCheck(Bundle result) throws Exception {
        // Exercise the shipped process layout through its public Home UI, with no exported media Activity.
        if (!android.os.Build.PRODUCT.startsWith("sdk_"))
            throw new IllegalStateException("Native UI smoke check is emulator-only");
        Intent home = new Intent().setClassName("com.sogud.bananatv", "com.sogud.bananatv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getTargetContext().startActivity(home);
        clickText("影视");
        Thread.sleep(1500);
        clickText(getTargetContext().getString(R.string.media_settings)); clickText(getTargetContext().getString(R.string.media_source_config));
        if (arguments.getString("native_builtin") != null) {
            Thread.sleep(350);
            if (findNode(getUiAutomation().getRootInActiveWindow(), getTargetContext().getString(R.string.media_use_builtin), false) != null)
                clickText(getTargetContext().getString(R.string.media_use_builtin));
            else sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            Thread.sleep(500);
            clickText(getTargetContext().getString(R.string.media_settings)); clickText(getTargetContext().getString(R.string.media_source_config));
            awaitNode(getTargetContext().getString(R.string.media_use_custom), false);
            require(true, "release_builtin_mode_and_saved_custom_source"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            clickText(getTargetContext().getString(R.string.media_settings)); clickText(getTargetContext().getString(R.string.media_content_sources));
            awaitNode("量子", false); awaitNode("暴风", false); awaitNode("非凡", false); capture("media-native-sources");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        } else {
            clickText(getTargetContext().getString(R.string.media_add_source));
            enterText(arguments.getString("source")); clickText(getTargetContext().getString(R.string.media_import));
        }
        waitCatalogUi(); Thread.sleep(3000); capture("media-native-browser");
        require(findNode(getUiAutomation().getRootInActiveWindow(), "量子", false) == null
                && findNode(getUiAutomation().getRootInActiveWindow(), "非凡", false) == null, "release_home_has_no_provider_picker");
        clickText(getTargetContext().getString(R.string.media_movies)); Thread.sleep(400); waitCatalogUi(); capture("media-native-movies");
        clickText(getTargetContext().getString(R.string.media_series)); Thread.sleep(400); waitCatalogUi(); capture("media-native-series");
        clickText(getTargetContext().getString(R.string.media_search)); enterText(arguments.getString("word"));
        clickText(getTargetContext().getString(R.string.media_search));
        if (arguments.getString("detail_source") != null) {
            for (int i = 0; i < 150; i++) {
                if (searchComplete(getUiAutomation().getRootInActiveWindow())) break;
                Thread.sleep(200);
            }
            require(searchComplete(getUiAutomation().getRootInActiveWindow()), "release_cross_source_search_complete");
            capture("media-native-search");
        }
        if (arguments.getString("year") == null) clickText(arguments.getString("title"));
        else clickDatedMovie(arguments.getString("title"), arguments.getString("year"));
        if (arguments.getString("detail_source") != null) {
            clickText(arguments.getString("detail_source"));
            awaitNode(arguments.getString("episode_name"), false); capture("media-native-detail");
        }
        clickText(arguments.getString("episode_name"));
        Thread.sleep(12000); capture("media-native-player-1");
        Thread.sleep(10000); capture("media-native-player-2");
        getTargetContext().startActivity(home); awaitNode("影视", false);
        result.putString("result", "PASS"); finish(Activity.RESULT_OK, result);
    }
    private android.view.accessibility.AccessibilityNodeInfo datedMovie(android.view.accessibility.AccessibilityNodeInfo node, String title, String year) {
        if (node == null) return null;
        if (node.isClickable() && node.isVisibleToUser() && title.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) {
            for (int i = 0; i < node.getChildCount(); i++) {
                android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
                String text = child == null || child.getText() == null ? "" : child.getText().toString();
                java.util.regex.Matcher count = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(year) + " · ([0-9]+)").matcher(text);
                if (count.find() && Integer.parseInt(count.group(1)) >= 2) return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = datedMovie(node.getChild(i), title, year);
            if (found != null) return found;
        }
        return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo posterScroll(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.ScrollView".contentEquals(node.getClassName()) && node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = posterScroll(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }
    private void clickDatedMovie(String title, String year) throws Exception {
        for (int i = 0; i < 10; i++) {
            android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            android.view.accessibility.AccessibilityNodeInfo card = datedMovie(root, title, year);
            if (card != null && card.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                require(true, "ui_click_dated_group_" + year); return;
            }
            android.view.accessibility.AccessibilityNodeInfo scroll = posterScroll(root);
            if (scroll == null || !scroll.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break;
            Thread.sleep(350);
        }
        throw new AssertionError("Missing dated cross-source movie group");
    }
    private void waitCatalogUi() throws Exception {
        for (int i = 0; i < 200; i++) {
            if (catalogComplete(getUiAutomation().getRootInActiveWindow())) {
                Thread.sleep(350);
                if (catalogComplete(getUiAutomation().getRootInActiveWindow())) return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Release catalog did not finish");
    }
    private boolean catalogComplete(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().startsWith("片库 ·")) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (catalogComplete(node.getChild(i))) return true;
        return false;
    }
    private boolean searchComplete(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().startsWith("已搜索 ")) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (searchComplete(node.getChild(i))) return true;
        return false;
    }
    private void sourceCheck(Bundle result) throws Exception {
        String url = arguments.getString("source");
        MediaEngine engine = MediaEngine.get(getTargetContext());
        if (arguments.getString("built_in") != null) engine.useBuiltIn(true);
        else {
            if (url == null || !url.startsWith(base + "/")) throw new IllegalArgumentException("Source must be provided by the private fixture server");
            engine.importSource(url);
        }
        Site site = engine.site(arguments.getString("site"));
        int endpoint = Integer.parseInt(arguments.getString("endpoint_index", "0"));
        if (endpoint > 0) site.activeApi = site.mirrors.get(endpoint - 1);
        Page home = engine.home(site);
        require(!home.categories.isEmpty() && !home.movies.isEmpty() && !home.movies.get(0).poster.isEmpty(), "source_home_categories_and_posters");
        Page found = engine.search(site, arguments.getString("word"), 1);
        Movie target = null;
        for (Movie movie : found.movies) if (movie.title.equals(arguments.getString("title"))) { target = movie; break; }
        require(target != null, "source_search_exact_title");
        Movie detail = engine.detail(site, target.id); require(!detail.lines.isEmpty(), "source_detail_episodes");
        int line = Integer.parseInt(arguments.getString("line", "0"));
        JSONObject stream = engine.resolve(site, detail, line, 0);
        require(stream.optInt("parse") == 0 && !stream.optString("url").isEmpty(), "source_native_stream_resolved");
        Activity owner = startActivitySync(new Intent(getTargetContext(), MediaPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("site", site.key).putExtra("id", detail.id).putExtra("line", line).putExtra("episode", 0));
        Field field = MediaPlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
        final long[] position = {0}; final int[] width = {0};
        for (int i = 0; i < 150 && position[0] < 3000; i++) {
            Thread.sleep(100);
            runOnMainSync(() -> {
                try { ExoPlayer player = (ExoPlayer) field.get(owner);
                    if (player != null) { position[0] = player.getCurrentPosition(); width[0] = player.getVideoSize().width; }
                } catch (Exception error) { throw new RuntimeException(error); }
            });
        }
        require(position[0] >= 3000 && width[0] > 0, "source_playback_progress_and_video_size");
        final long[] start = {0}, duration = {0};
        final int[] frames = {0}, audio = {0}, buffering = {0}, playing = {0};
        runOnMainSync(() -> {
            try {
                ExoPlayer player = (ExoPlayer) field.get(owner);
                duration[0] = player.getDuration();
                if (duration[0] > 90000) player.seekTo(60000);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        for (int i = 0; i < 60; i++) {
            Thread.sleep(500);
            runOnMainSync(() -> {
                try {
                    ExoPlayer player = (ExoPlayer) field.get(owner);
                    if (player == null) return;
                    if (player.isPlaying()) {
                        playing[0]++;
                        if (start[0] == 0) start[0] = player.getCurrentPosition();
                    }
                    if (player.getPlaybackState() == Player.STATE_BUFFERING) buffering[0]++;
                    position[0] = player.getCurrentPosition();
                    DecoderCounters video = player.getVideoDecoderCounters();
                    DecoderCounters sound = player.getAudioDecoderCounters();
                    if (video != null) { video.ensureUpdated(); frames[0] = video.renderedOutputBufferCount; }
                    if (sound != null) { sound.ensureUpdated(); audio[0] = sound.renderedOutputBufferCount; }
                    if (player.getPlayerError() != null) throw new AssertionError(player.getPlayerError().getErrorCodeName());
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            });
        }
        require(playing[0] >= 30 && position[0] - start[0] >= 15000, "source_sustained_playback_after_seek");
        require(frames[0] > 100 && audio[0] > 100, "source_video_frames_and_audio_decoded");
        result.putString("title", detail.title);
        result.putString("api_host", android.net.Uri.parse(site.activeApi).getHost());
        result.putLong("duration_ms", duration[0]); result.putLong("position_ms", position[0]);
        result.putInt("video_width", width[0]); result.putInt("video_frames", frames[0]); result.putInt("audio_buffers", audio[0]);
        result.putInt("playing_samples", playing[0]); result.putInt("buffering_samples", buffering[0]);
        android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot != null) try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(), "media-source-player.png"))) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
        runOnMainSync(owner::finish); waitForIdleSync();
        result.putString("result", "PASS"); finish(Activity.RESULT_OK, result);
    }
    private void formatsCheck(MediaEngine engine) throws Exception {
        engine.importSource(base + "/formats-config.json");
        Site xml = engine.site("xml");
        require(xml.jarHost.equals("catvod-v1") && engine.site("web").jarHost.equals("native"), "jar_host_profile_inheritance_and_override");
        Page home = engine.home(xml);
        require(home.categories.size() == 1 && home.movies.size() == 1, "xml_categories_and_detailed_home");
        Page category = engine.category(xml, "1", 2);
        require(category.page == 2 && category.pages == 3, "xml_pagination_and_ac_query_replacement");
        require(engine.latest(xml, 3, new MediaEngine.Cancellation()).page == 3, "xml_latest_uses_requested_page");
        require(engine.search(xml, "Spring", 1).movies.size() == 1, "xml_search");
        Movie detail = engine.detail(xml, "xml-sample");
        require(detail.title.equals("A & B") && detail.summary.equals("<p>Spring</p>"), "xml_entities_and_cdata");
        require(detail.lines.size() == 2 && detail.lines.get(0).episodes.size() == 2, "xml_flags_and_episodes");
        require(engine.resolve(xml, detail, 1, 0).getString("url").endsWith("/clip2.mp4"), "xml_native_resolution");
        boolean rejected = false;
        try { MediaXml.parse("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/hosts'>]><rss><list><video><name>&x;</name></video></list></rss>"); }
        catch (org.xmlpull.v1.XmlPullParserException expected) { rejected = true; }
        require(rejected, "xml_dtd_rejected");
        rejected = false;
        try { MediaXml.parse("<html>source unavailable</html>"); }
        catch (org.xmlpull.v1.XmlPullParserException expected) { rejected = true; }
        require(rejected, "xml_html_response_rejected");
        Site web = engine.site("web");
        JSONObject stream = engine.resolve(web, engine.detail(web, "sample"), 0, 0);
        require(stream.getString("url").equals(base + "/clip1.mp4") && stream.optInt("parse") == 0, "web_page_resolves_native_stream");
        require(stream.getJSONObject("header").getString("Referer").equals(base + "/web-player"), "web_stream_retains_referer");
        boolean timedOut = false;
        try { MediaWebResolver.resolve(getTargetContext(), base + "/empty-web-player", null, 1000); }
        catch (java.util.concurrent.TimeoutException expected) { timedOut = true; }
        require(timedOut, "empty_web_player_times_out");
        Site jx = engine.site("jx");
        JSONObject parsed = engine.resolve(jx, engine.detail(jx, "other-id"), 0, 0);
        require(parsed.getString("url").equals(base + "/clip1.mp4") && parsed.optInt("parse") == 0,
                "configured_json_parser_fallback_and_url_array");
        require(!parsed.getJSONObject("header").has("Authorization") && fixtureStats().getInt("/parser-failed") == 1,
                "parser_does_not_receive_source_credentials");
        rejected = false;
        try { engine.importSource(base + "/unknown-host-config.json"); }
        catch (MediaEngine.Failure expected) { rejected = true; }
        require(rejected && engine.site("xml") != null, "unknown_host_profile_preserves_configuration");
        proxyCheck(engine);
        folderCheck(engine);
    }
    private void folderCheck(MediaEngine engine) throws Exception {
        engine.importSource(base + "/folder-config.json"); engine.enableSites(java.util.Set.of("folders")); engine.autoRefresh(false);
        Activity host = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            clickText("收藏合集"); clickText("二级目录"); clickText("Spring · 文件夹测试");
            require(awaitNode("片段", false) != null, "nested_folder_opens_native_episodes");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            require(awaitNode("Spring · 文件夹测试", false) != null, "folder_back_restores_child_listing");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            require(awaitNode("二级目录", false) != null, "folder_back_restores_parent_listing");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            require(awaitNode("收藏合集", false) != null, "folder_back_restores_unified_library");
        } finally { runOnMainSync(host::finish); waitForIdleSync(); }
    }
    public static Object[] legacyProxy(java.util.Map<String, String> params) {
        if (!"bytes=1-2".equals(params.get("range"))) throw new AssertionError("Missing Range header");
        return new Object[]{206, "application/octet-stream", new byte[]{1, (byte) 255},
                java.util.Map.of("Content-Range", "bytes 1-2/3", "Content-Length", "999", "Transfer-Encoding", "identity")};
    }
    private void proxyCheck(MediaEngine engine) throws Exception {
        Field proxies = MediaEngine.class.getDeclaredField("jarProxies"), sites = MediaEngine.class.getDeclaredField("siteJars");
        proxies.setAccessible(true); sites.setAccessible(true);
        ((java.util.Map<String, java.lang.reflect.Method>) proxies.get(engine)).put("fixture.jar",
                MediaIntegrationProbe.class.getMethod("legacyProxy", java.util.Map.class));
        ((java.util.Map<String, String>) sites.get(engine)).put("legacy", "fixture.jar");
        java.lang.reflect.Method start = MediaEngine.class.getDeclaredMethod("startProxy"); start.setAccessible(true); start.invoke(engine);
        java.net.HttpURLConnection request = (java.net.HttpURLConnection) new java.net.URL(com.github.catvod.Proxy.getUrl(true) + "?siteKey=legacy").openConnection();
        request.setReadTimeout(2000); request.setRequestProperty("Range", "bytes=1-2");
        try {
            require(request.getResponseCode() == 206 && request.getContentLength() == -1, "legacy_proxy_range_and_chunked_framing");
            byte[] bytes;
            try (java.io.InputStream input = request.getInputStream()) { bytes = input.readAllBytes(); }
            require(java.util.Arrays.equals(bytes, new byte[]{1, (byte) 255}), "legacy_proxy_preserves_binary_body");
            require("bytes 1-2/3".equals(request.getHeaderField("Content-Range")), "legacy_proxy_preserves_content_range");
        } finally { request.disconnect(); }
        java.lang.reflect.Method extension = MediaEngine.class.getDeclaredMethod("localExtension", String.class); extension.setAccessible(true);
        JSONObject adjusted = new JSONObject((String) extension.invoke(engine, "{\"cookie\":\"http://localhost:9995/file/TVBox/bili_cookie.txt\",\"remote\":\"https://example.com/file/x\",\"nested\":[\"http://127.0.0.1:9990/proxy?do=jar\"]}"));
        String host = "http://127.0.0.1:" + com.github.catvod.Proxy.getPort();
        require(adjusted.getString("cookie").equals(host + "/file/TVBox/bili_cookie.txt")
                && adjusted.getJSONArray("nested").getString(0).equals(host + "/proxy?do=jar"), "legacy_local_links_use_own_host");
        require(adjusted.getString("remote").equals("https://example.com/file/x"), "external_extension_links_unchanged");
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); Activity screen = null;
        try {
            if (!getTargetContext().getPackageName().endsWith(".mediatest") || base == null || !base.startsWith("http://127.0.0.1:"))
                throw new IllegalStateException("Only an isolated fixture build is allowed");
            new MediaSourceQuality(getTargetContext(), MediaEngine.get(getTargetContext())).automatic(false);
            if (arguments.getString("audit") != null) { SourceCompatibilityProbe.run(this, arguments, base); return; }
            if (arguments.getString("native_ui") != null) { nativeUiCheck(result); return; }
            if (arguments.getString("source") != null || arguments.getString("built_in") != null) { sourceCheck(result); return; }
            MediaEngine engine = MediaEngine.get(getTargetContext());
            if (arguments.getString("usage") != null) {
                String usage = arguments.getString("usage");
                if (usage.equals("wallpaper")) com.sogud.bananatv.StaticWallpaperProbe.run(this);
                else if (usage.equals("player")) playerRemoteCheck(engine);
                else if (usage.equals("reliability")) reliabilityCheck(engine);
                else if (usage.equals("stall")) stallCheck(engine);
                else startupCacheCheck(engine);
                result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); return;
            }
            if (arguments.getString("posters_only") != null) {
                posterQueueCheck(); visiblePosterCheck(engine);
                result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); return;
            }
            if (arguments.getString("cache_only") != null) {
                catalogCacheCheck(engine);
                result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); return;
            }
            if (arguments.getString("genres_only") != null) {
                engine.importSource(base + "/search-config.json"); unifiedLibraryCheck(engine);
                result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); return;
            }
            if (arguments.getString("formats_only") != null) {
                formatsCheck(engine); result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); return;
            }
            if (arguments.getString("panel_only") != null) {
                panelCheck(engine); result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); return;
            }
            if (arguments.getString("catalog_url") != null) {
                String url = arguments.getString("catalog_url");
                if (!url.startsWith("https://")) throw new IllegalArgumentException("Use an HTTPS test deployment");
                engine.importSource(url);
                require(engine.sites().size() == Integer.parseInt(arguments.getString("expected_sites")), "deployed_catalog_imports_over_https");
                require(!engine.refreshSourceIfDue(new MediaEngine.Cancellation()), "deployed_catalog_uses_fresh_local_cache");
                engine.useBuiltIn(true); engine.useBuiltIn(false);
                require(!engine.usingBuiltIn() && engine.sites().size() == Integer.parseInt(arguments.getString("expected_sites")), "deployed_catalog_can_be_restored_from_cache");
                result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); return;
            }
            if (arguments.getString("source_update_only") != null) {
                sourceUpdateCheck(engine);
                result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); return;
            }
            if (arguments.getString("navigation_only") != null) {
                engine.importSource(base + "/search-config.json"); engine.enableSites(java.util.Set.of("fixture", "second", "failed")); setInTouchMode(false);
                Activity browser = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                awaitText(browser, "2019 · 2 个来源"); clickText("Spring · 播放测试"); awaitText(browser, "第二片库");
                clickText("本机测试片库"); awaitText(browser, "✓ 原画");
                categoryAndSearchUi(browser); runOnMainSync(browser::finish);
                result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); return;
            }
            engine.importSource(base + "/config.json");
            java.io.File cached = new java.io.File(getTargetContext().getFilesDir(), "media/config.json");
            byte[] originalConfig = java.nio.file.Files.readAllBytes(cached.toPath());
            engine.useBuiltIn(true);
            require(engine.usingBuiltIn() && engine.sites().size() == 6 && engine.site("ffzy").mirrors.size() == 2, "bundled_sources_available_without_download");
            require(java.util.Arrays.equals(originalConfig, java.nio.file.Files.readAllBytes(cached.toPath())), "builtin_switch_preserves_custom_config");
            engine.useBuiltIn(false);
            require(!engine.usingBuiltIn() && engine.site("fixture") != null, "saved_custom_source_restored");
            Site mirrored = engine.site("mirror");
            require(engine.home(mirrored).movies.size() == 1 && mirrored.activeApi.equals(base + "/api"), "http_and_html_failures_switch_to_mirror");
            java.net.HttpURLConnection stats = (java.net.HttpURLConnection) new java.net.URL(base + "/stats").openConnection();
            stats.setConnectTimeout(2000); stats.setReadTimeout(2000);
            JSONObject counts;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(stats.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                counts = new JSONObject(reader.readLine());
            } finally { stats.disconnect(); }
            require(counts.getInt("/unavailable") == 1 && counts.getInt("/malformed") == 1, "working_mirror_reused_for_following_request");
            boolean unavailable = false;
            try { engine.home(engine.site("dead-mirrors")); } catch (Exception expected) { unavailable = true; }
            require(unavailable, "all_mirrors_failed_is_explicit");
            Site site = engine.site("fixture");
            Page home = engine.home(site);
            require(home.categories.size() == 4 && home.movies.size() == 1 && home.movies.get(0).poster.endsWith("/poster.jpg"), "home_categories_and_posters");
            require(engine.search(site, "sample", 1).movies.size() == 1, "search_results");
            require(engine.search(site, "missing", 1).movies.isEmpty(), "empty_search");
            require(engine.category(site, "film", 2).page == 2, "pagination");
            Movie movie = engine.detail(site, "sample");
            require(movie.lines.size() == 2 && movie.lines.get(0).episodes.size() == 2, "lines_and_episodes");
            require(engine.resolve(site, movie, 0, 0).getString("url").endsWith("/clip1.mp4"), "native_playback_url");
            boolean malformed = false;
            try { engine.importSource(base + "/invalid.json"); } catch (Exception expected) { malformed = true; }
            require(malformed && engine.site("fixture") != null, "invalid_config_preserves_previous");
            Site parser = engine.site("parser");
            JSONObject web = engine.resolve(parser, engine.detail(parser, "sample"), 0, 0);
            require(web.optInt("parse") == 0 && web.getString("url").equals(base + "/clip1.mp4"), "ordinary_web_player_resolves_native_media");
            engine.importSource(base + "/bad-jar.json");
            boolean mismatch = false;
            try { engine.home(engine.site("bad-jar")); }
            catch (MediaEngine.Failure failure) { mismatch = failure.message == R.string.media_integrity_failed; }
            require(mismatch, "jar_checksum_rejection");
            engine.importSource(base + "/config.json");
            if (!com.github.catvod.utils.RuntimeModule.PYTHON.isInstalled()) {
                boolean missing = false;
                try { engine.home(engine.site("python")); }
                catch (Exception error) { missing = com.github.catvod.utils.RuntimeModule.MissingException.find(error) != null; }
                require(missing, "missing_runtime_is_explicit");
            }
            if (com.github.catvod.utils.RuntimeModule.QUICKJS.isInstalled()) {
                Site javascript = engine.site("javascript");
                require(engine.home(javascript).movies.size() == 1, "quickjs_runtime_home");
                require(engine.search(javascript, "sample", 1).movies.size() == 1, "quickjs_runtime_search");
                Movie scripted = engine.detail(javascript, "sample");
                require(engine.resolve(javascript, scripted, 0, 0).getString("url").endsWith("/clip1.mp4"), "quickjs_runtime_resolve");
            }
            engine.remember(engine.site("fixture"), engine.detail(engine.site("fixture"), "sample"), 0, 0, 1500, 9000);
            require(engine.history().length() == 1, "history_storage_roundtrip");
            getTargetContext().getSharedPreferences("media", 0).edit().remove("history").commit();
            Intent intent = new Intent(getTargetContext(), MediaPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("site", "fixture").putExtra("id", "sample").putExtra("line", 0).putExtra("episode", 0);
            screen = startActivitySync(intent);
            final Activity owner = screen;
            Field field = MediaPlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
            final long[] position = {0}; final int[] state = {0};
            for (int i = 0; i < 80 && position[0] < 2000; i++) {
                Thread.sleep(100);
                runOnMainSync(() -> {
                    try { ExoPlayer player = (ExoPlayer) field.get(owner);
                        if (player != null) { position[0] = player.getCurrentPosition(); state[0] = player.getPlaybackState(); }
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
            }
            require(position[0] >= 2000 && state[0] == 3, "player_progress_advances");
            runOnMainSync(owner::finish); waitForIdleSync(); Thread.sleep(200);
            for (int i = 0; i < 30 && engine.history().length() == 0; i++) Thread.sleep(100);
            require(engine.history().length() > 0, "history_written_on_exit");
            JSONObject saved = engine.history().getJSONObject(0);
            require(saved.getLong("position") >= 2000 && saved.getInt("episode") == 0, "history_from_actual_playback");
            Intent resume = new Intent(getTargetContext(), MediaPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("site", "fixture").putExtra("id", "sample").putExtra("line", 0).putExtra("episode", 0)
                    .putExtra("position", saved.getLong("position"));
            screen = startActivitySync(resume); final Activity resumed = screen;
            final long[] continued = {0};
            for (int i = 0; i < 80 && continued[0] < saved.getLong("position") + 800; i++) {
                Thread.sleep(100);
                runOnMainSync(() -> {
                    try { ExoPlayer player = (ExoPlayer) field.get(resumed); if (player != null) continued[0] = player.getCurrentPosition(); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
            }
            require(continued[0] >= saved.getLong("position") + 800, "resume_seek_and_progress");
            runOnMainSync(resumed::finish); waitForIdleSync();
            engine.importSource(base + "/search-config.json"); engine.select("fixture");
            aggregationCheck(engine);
            unifiedLibraryCheck(engine);
            engine.enableSites(java.util.Set.of("fixture", "second", "failed"));
            setInTouchMode(false);
            Activity browser = startActivitySync(new Intent(getTargetContext(), MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitText(browser, "2019 · 2 个来源"); waitForIdleSync();
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(), "media-browser.png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                }
            }
            final boolean[] button = {false};
            runOnMainSync(() -> button[0] = contains(browser.getWindow().getDecorView(), "Spring · 播放测试"));
            require(button[0], "native_browser_title_visible");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN); waitForIdleSync();
            final boolean[] focused = {false};
            runOnMainSync(() -> {
                android.view.View view = browser.getCurrentFocus();
                focused[0] = view != null && view.getContentDescription() != null && view.getContentDescription().toString().contains("Spring");
            });
            require(focused[0], "remote_focus_reaches_movie");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            Thread.sleep(1000); waitForIdleSync();
            awaitText(browser, "第二片库"); clickText("本机测试片库"); awaitText(browser, "✓ 原画");
            final boolean[] detail = {false};
            runOnMainSync(() -> detail[0] = contains(browser.getWindow().getDecorView(), "✓ 原画"));
            require(detail[0], "remote_opens_native_details");
            categoryAndSearchUi(browser);
            runOnMainSync(browser::finish); waitForIdleSync();
            detailDiscoveryCheck(engine);
            result.putString("result", "PASS"); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            try { capture("media-failure"); } catch (Exception ignored) { }
            if (screen != null) { final Activity owner = screen; runOnMainSync(owner::finish); }
            result.putString("result", "FAIL"); result.putString("reason", error.toString()); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
