package com.sogud.bananatv.media;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sogud.bananatv.R;
import com.sogud.bananatv.PhoneServer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import static com.sogud.bananatv.media.MediaModels.*;
import static com.sogud.bananatv.media.MediaViews.*;

/** TV-native browsing UI. All source execution stays off the main thread. */
public final class MediaActivity extends Activity {
    private final MediaWorkQueue worker = new MediaWorkQueue();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaEngine engine;
    private LinearLayout content;
    private Runnable parent;
    private Runnable listing;
    private int generation;
    private Future<?> requestTask;
    private MediaEngine.Cancellation requestCancellation;
    private MediaCatalog.Library library = new MediaCatalog.Library();
    private LinearLayout categoryBar;
    private HorizontalScrollView genreScroll;
    private LinearLayout genreTabs;
    private final List<String> shownGenres = new ArrayList<>();
    private Button selectedTopCategory;
    private Button selectedCategory;
    private MediaCatalog catalog;
    private MediaCatalog detailLookup;
    private MediaCatalog.Group detailGroup;
    private HorizontalScrollView detailSources;
    private boolean catalogVisible;
    private boolean checkSourceUpdate = true;
    private final java.util.concurrent.ExecutorService sourceUpdates = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final MediaEngine.Cancellation sourceUpdateCancellation = new MediaEngine.Cancellation();
    private PhoneServer phoneServer;
    private MediaPanel phoneDialog;
    private volatile String phoneSourceState = "ready";
    private TextView catalogStatus, catalogFailures, catalogEmpty;
    private GridLayout catalogGrid;
    private LinearLayout catalogActions;
    private final Map<String, LinearLayout> catalogCards = new LinkedHashMap<>();
    private final Map<ImageView, String> posterUrls = new LinkedHashMap<>();
    private final java.util.Set<ImageView> visiblePosters = new java.util.LinkedHashSet<>();
    interface Work<T> { T run(MediaEngine.Cancellation cancellation) throws Exception; }
    interface Done<T> { void accept(T value); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        engine = MediaEngine.get(this); home();
    }
    @Override protected void onResume() {
        super.onResume(); MediaEngine.foreground(this);
    }
    private void frame(String title) {
        MediaImages.cancelPending();
        for (ImageView poster : visiblePosters) { poster.setTag(null); poster.setImageDrawable(null); }
        posterUrls.clear(); visiblePosters.clear();
        cancelRequest(); catalogVisible = false;
        cancelDetailLookup();
        generation++;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xff10141d);
        root.setPadding(dp(this, 44), dp(this, 24), dp(this, 44), dp(this, 20));
        LinearLayout nav = row(this);
        TextView name = text(this, title, 27, Color.WHITE); name.setTypeface(null, android.graphics.Typeface.BOLD);
        nav.addView(name, new LinearLayout.LayoutParams(0, dp(this, 54), 1));
        addButton(this, nav, getString(R.string.media_search), this::searchDialog);
        addButton(this, nav, getString(R.string.media_continue), this::history);
        addButton(this, nav, getString(R.string.media_settings), this::settings);
        root.addView(nav);
        categoryBar = new LinearLayout(this); categoryBar.setOrientation(LinearLayout.VERTICAL);
        categoryBar.setClipToPadding(false); categoryBar.setPadding(0, dp(this, 14), 0, dp(this, 8));
        categoryBar.setVisibility(View.GONE); selectedCategory = null; selectedTopCategory = null;
        genreScroll = null; genreTabs = null; shownGenres.clear(); catalogGrid = null; root.addView(categoryBar);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> refreshPosters());
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(this::refreshPosters);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, dp(this, 24), 0, dp(this, 12));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    private void message(String message) {
        content.removeAllViews(); TextView text = text(this, message, 19, 0xffb9c4d5);
        text.setPadding(0, dp(this, 30), 0, dp(this, 24)); content.addView(text);
    }
    private <T> void request(Work<T> action, Done<T> done) {
        request(action, done, () -> { });
    }
    private <T> void request(Work<T> action, Done<T> done, Runnable failureControls) {
        cancelRequest(); requestCancellation = new MediaEngine.Cancellation();
        MediaEngine.Cancellation cancellation = requestCancellation;
        int ticket = ++generation;
        message(getString(R.string.media_loading));
        requestTask = worker.submitFirst(() -> {
            try {
                T value = action.run(cancellation);
                main.post(() -> { if (!isDestroyed() && ticket == generation) done.accept(value); });
            } catch (Exception | LinkageError error) {
                main.post(() -> {
                    if (isDestroyed() || ticket != generation) return;
                    message(engine.error(error));
                    LinearLayout controls = row(this);
                    addButton(this, controls, getString(R.string.media_retry), () -> request(action, done, failureControls));
                    addButton(this, controls, getString(R.string.media_settings), this::settings); content.addView(controls);
                    failureControls.run();
                });
            }
        });
    }
    private void cancelRequest() {
        if (requestCancellation != null) requestCancellation.cancel();
        if (requestTask != null) requestTask.cancel(true);
    }
    private void cancelCatalog() {
        if (catalog != null) catalog.cancel();
        catalog = null;
    }
    private void cancelDetailLookup() {
        if (detailLookup != null) detailLookup.cancel();
        detailLookup = null; detailGroup = null; detailSources = null;
    }
    private void home() {
        parent = null; frame(getString(R.string.media_title));
        boolean update = checkSourceUpdate; checkSourceUpdate = false;
        request(cancellation -> {
            if (update) engine.applyPendingSourceUpdate();
            engine.load();
            cancellation.check();
            return engine.sites();
        }, sites -> {
            if (sites.isEmpty()) {
                message(getString(R.string.media_no_source));
                Button add = button(this, getString(R.string.media_add_source), this::sourceDialog);
                content.addView(add); add.requestFocus(); return;
            }
            showLibrary("");
            if (update) sourceUpdates.execute(() -> {
                try { engine.prefetchSourceUpdate(sourceUpdateCancellation); }
                catch (Exception ignored) { /* Keep the last valid local configuration. */ }
            });
        });
    }
    private void settings() {
        MediaPanel panel = new MediaPanel(this, getString(R.string.media_settings), null);
        panel.choice(getString(R.string.media_content_sources), false, () -> { panel.dismiss(); manageSources(); });
        panel.choice(getString(R.string.media_source_config), false, () -> { panel.dismiss(); configuration(); });
        MediaSourceQuality quality = new MediaSourceQuality(this, engine);
        panel.choice(getString(quality.automatic() ? R.string.media_auto_source_on : R.string.media_auto_source_off), false,
                () -> { quality.automatic(!quality.automatic()); panel.dismiss(); settings(); });
        panel.show();
    }
    private void manageSources() {
        List<Site> sites = engine.sites();
        if (sites.isEmpty()) { sourceDialog(); return; }
        MediaPanel panel = new MediaPanel(this, getString(R.string.media_content_sources), getString(R.string.media_sources_description));
        java.util.Set<String> enabled = new java.util.HashSet<>();
        for (Site site : sites) {
            if (engine.enabled(site)) enabled.add(site.key);
            panel.toggle(site.name, engine.enabled(site), checked -> {
                if (checked) enabled.add(site.key); else enabled.remove(site.key);
            });
        }
        panel.action(getString(R.string.cancel), panel::dismiss);
        panel.action(getString(R.string.media_save), () -> {
            panel.dismiss(); cancelCatalog(); engine.enableSites(enabled); library = new MediaCatalog.Library(); home();
        });
        panel.show();
    }
    private void configuration() {
        List<String> names = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        if (!engine.usingBuiltIn()) {
            names.add(getString(R.string.media_use_builtin)); actions.add(() -> changeConfiguration(cancellation -> { engine.useBuiltIn(true); return true; }));
            names.add(getString(R.string.media_refresh_config)); actions.add(() -> changeConfiguration(cancellation -> { engine.refreshSource(cancellation); return true; }));
            names.add(getString(engine.autoRefresh() ? R.string.media_auto_refresh_on : R.string.media_auto_refresh_off));
            actions.add(() -> { engine.autoRefresh(!engine.autoRefresh()); configuration(); });
        } else if (engine.hasCustomSource()) {
            names.add(getString(R.string.media_use_custom)); actions.add(() -> changeConfiguration(cancellation -> { engine.useBuiltIn(false); return true; }));
        }
        names.add(getString(R.string.media_add_source)); actions.add(this::sourceDialog);
        names.add(getString(R.string.media_scan_source)); actions.add(this::phoneSourceDialog);
        MediaPanel panel = new MediaPanel(this, getString(R.string.media_source_config), null);
        for (int i = 0; i < names.size(); i++) {
            Runnable action = actions.get(i);
            panel.choice(names.get(i), false, () -> { panel.dismiss(); action.run(); });
        }
        panel.show();
    }
    private void changeConfiguration(Work<Boolean> action) {
        cancelCatalog(); library = new MediaCatalog.Library(); parent = this::home; frame(getString(R.string.media_title));
        request(action, result -> home());
    }
    private void sourceDialog() {
        MediaPanel panel = new MediaPanel(this, getString(R.string.media_add_source), getString(R.string.media_source_trust));
        EditText input = panel.input("https://…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        panel.action(getString(R.string.cancel), panel::dismiss);
        panel.action(getString(R.string.media_import), () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { panel.error(getString(R.string.media_invalid_address)); input.requestFocus(); return; }
            panel.dismiss(); changeConfiguration(cancellation -> { engine.importSource(value, cancellation); return true; });
        });
        panel.show();
    }
    private void phoneSourceDialog() {
        String address = PhoneServer.lanAddress(this);
        if (address == null) { android.widget.Toast.makeText(this, R.string.phone_unavailable, android.widget.Toast.LENGTH_LONG).show(); return; }
        if (phoneServer != null) phoneServer.stop();
        phoneSourceState = "ready";
        PhoneServer server = new PhoneServer(this, address, new PhoneServer.SourceHost() {
            @Override public synchronized boolean submit(String url) {
                if (phoneSourceState.equals("loading") || isDestroyed() || isFinishing()) return false;
                phoneSourceState = "loading";
                main.post(() -> {
                    cancelCatalog(); library = new MediaCatalog.Library(); parent = MediaActivity.this::home;
                    frame(getString(R.string.media_title));
                    request(cancellation -> { engine.importSource(url, cancellation); return true; }, result -> {
                        phoneSourceState = "saved"; home();
                    }, () -> phoneSourceState = "error");
                });
                return true;
            }
            @Override public String state() { return phoneSourceState; }
        });
        try { server.start(5000, true); phoneServer = server; }
        catch (java.io.IOException error) { android.widget.Toast.makeText(this, R.string.phone_start_failed, android.widget.Toast.LENGTH_LONG).show(); return; }
        MediaPanel dialog = new MediaPanel(this, getString(R.string.media_scan_source), null); dialog.width(620);
        LinearLayout pairing = row(this); pairing.setGravity(android.view.Gravity.CENTER_VERTICAL);
        try {
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(server.url(),
                    com.google.zxing.BarcodeFormat.QR_CODE, 320, 320);
            android.graphics.Bitmap qr = android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) qr.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView image = new ImageView(this); image.setImageBitmap(qr);
            image.setContentDescription(getString(R.string.media_scan_source));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(this, 192), dp(this, 192));
            image.setBackground(shape(Color.WHITE, dp(this, 16))); image.setClipToOutline(true);
            pairing.addView(image, size);
        } catch (com.google.zxing.WriterException ignored) { }
        LinearLayout instructions = new LinearLayout(this); instructions.setOrientation(LinearLayout.VERTICAL);
        instructions.setPadding(dp(this, 28), 0, 0, 0);
        TextView description = text(this, getString(R.string.media_scan_source_description), 17, 0xffd7dce5);
        description.setLineSpacing(dp(this, 5), 1); instructions.addView(description);
        TextView addressText = text(this, server.url(), 12, 0xffa6afbd);
        addressText.setPadding(0, dp(this, 20), 0, 0); instructions.addView(addressText);
        pairing.addView(instructions, new LinearLayout.LayoutParams(0, -2, 1)); dialog.content(pairing);
        dialog.action(getString(R.string.media_done), dialog::dismiss);
        phoneDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            server.stop(); if (phoneServer == server) phoneServer = null;
            if (phoneDialog == dialog) phoneDialog = null;
        });
        dialog.show();
        main.postDelayed(() -> { if (phoneServer == server) dialog.dismiss(); }, 10 * 60_000L);
    }
    private void searchDialog() {
        MediaPanel panel = new MediaPanel(this, getString(R.string.media_search_all), null);
        EditText input = panel.input(getString(R.string.media_search_hint), InputType.TYPE_CLASS_TEXT);
        panel.action(getString(R.string.cancel), panel::dismiss);
        Runnable search = () -> {
            String word = input.getText().toString().trim(); if (word.isEmpty()) return;
            panel.dismiss(); startSearch(word);
        };
        panel.action(getString(R.string.media_search), search);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            search.run(); return true;
        });
        panel.show();
    }
    private void categories(String active) {
        String section = MediaCategories.section(active);
        LinearLayout tabs = row(this);
        addCategory(tabs, "", getString(R.string.media_recommended), section);
        for (int i = 0; i < MediaCategories.IDS.length; i++)
            addCategory(tabs, MediaCategories.IDS[i], getString(MediaCategories.LABELS[i]), section);
        selectedTopCategory = selectedCategory;
        categoryBar.addView(categoryScroll(tabs)); categoryBar.setVisibility(View.VISIBLE);
        if (!section.isEmpty()) {
            genreTabs = row(this); genreScroll = categoryScroll(genreTabs);
            genreScroll.setPadding(0, dp(this, 10), 0, 0); genreScroll.setVisibility(View.GONE);
            categoryBar.addView(genreScroll); updateGenres();
        }
    }
    private HorizontalScrollView categoryScroll(LinearLayout tabs) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false); scroll.setClipToPadding(false); scroll.addView(tabs);
        return scroll;
    }
    private void updateGenres() {
        if (genreTabs == null || catalog == null) return;
        String section = MediaCategories.section(catalog.query);
        List<String> available = new ArrayList<>();
        for (Site site : engine.enabledSites()) {
            List<Category> classes = library.categories.get(site.key);
            if (classes == null) continue;
            for (String name : MediaCategories.genres(site, section, classes))
                if (!available.contains(name)) available.add(name);
        }
        if (available.equals(shownGenres)) return;
        View focused = genreTabs.findFocus();
        String focusName = focused instanceof Button ? ((Button) focused).getText().toString() : "";
        shownGenres.clear(); shownGenres.addAll(available); genreTabs.removeAllViews();
        selectedCategory = selectedTopCategory;
        genreScroll.setVisibility(available.isEmpty() ? View.GONE : View.VISIBLE);
        if (available.isEmpty()) return;
        addCategory(genreTabs, section, getString(R.string.media_all_genres), catalog.query);
        for (String name : available) addCategory(genreTabs, section + ":" + name, name, catalog.query);
        for (int i = 0; i < genreTabs.getChildCount(); i++) {
            Button tab = (Button) genreTabs.getChildAt(i);
            tab.setNextFocusUpId(selectedTopCategory.getId());
            if (tab.getText().toString().equals(focusName)) tab.requestFocus();
        }
        selectedTopCategory.setNextFocusDownId(selectedCategory.getId());
        if (catalogGrid != null && catalogGrid.getChildCount() > 0) {
            selectedCategory.setNextFocusDownId(catalogGrid.getChildAt(0).getId());
            for (int i = 0; i < Math.min(5, catalogGrid.getChildCount()); i++)
                catalogGrid.getChildAt(i).setNextFocusUpId(selectedCategory.getId());
        }
    }
    private void addCategory(LinearLayout tabs, String id, String name, String active) {
        Button tab = button(this, name, () -> showLibrary(id));
        tab.setId(View.generateViewId()); tab.setSelected(id.equals(active));
        tab.setContentDescription(getString(R.string.media_category_label, name));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(this, 44));
        params.setMargins(0, 0, dp(this, 12), 0); tabs.addView(tab, params);
        if (id.equals(active)) selectedCategory = tab;
    }
    private void showLibrary(String category) {
        cancelCatalog();
        MediaCatalog.Mode mode = category.isEmpty() ? MediaCatalog.Mode.HOME : MediaCatalog.Mode.CATEGORY;
        catalog = new MediaCatalog(engine, worker, library, mode, category, () -> { if (catalogVisible) updateCatalog(); });
        restoreCatalog(""); catalog.load(false);
    }
    private LinearLayout movieCard(Movie movie, String image, String metadata, Runnable action) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setId(View.generateViewId());
        ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(shape(0xff252d3c, dp(this, 12))); poster.setClipToOutline(true);
        card.addView(poster, new LinearLayout.LayoutParams(-1, dp(this, 208))); posterUrls.put(poster, image);
        TextView title = text(this, movie.title, 16, Color.WHITE);
        title.setPadding(dp(this, 6), dp(this, 8), dp(this, 6), 0);
        title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, new LinearLayout.LayoutParams(-1, dp(this, 34)));
        TextView subtitle = text(this, metadata, 13, 0xffb9c4d5); subtitle.setSingleLine(true);
        subtitle.setPadding(dp(this, 6), 0, dp(this, 6), 0);
        card.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(this, 24)));
        card.setFocusable(true); card.setClickable(true); card.setContentDescription(movie.title);
        card.setPadding(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        android.graphics.drawable.StateListDrawable focus = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable outline = shape(0xff252d3c, dp(this, 16));
        outline.setStroke(dp(this, 2), 0xfff4f5f8);
        focus.addState(new int[]{android.R.attr.state_focused}, outline);
        focus.addState(new int[]{}, shape(Color.TRANSPARENT, dp(this, 16))); card.setBackground(focus);
        card.setOnClickListener(view -> action.run()); return card;
    }
    private GridLayout.LayoutParams cardLayout() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = (getResources().getDisplayMetrics().widthPixels - dp(this, 88)) / 5 - dp(this, 16);
        params.setMargins(dp(this, 3), 0, dp(this, 13), dp(this, 24)); return params;
    }
    private void refreshPosters() {
        java.util.Set<ImageView> visible = new java.util.LinkedHashSet<>();
        android.graphics.Rect bounds = new android.graphics.Rect();
        boolean changed = false;
        for (Map.Entry<ImageView, String> entry : posterUrls.entrySet()) {
            ImageView poster = entry.getKey();
            if (poster.isAttachedToWindow() && poster.getGlobalVisibleRect(bounds)) {
                visible.add(poster);
                if (!entry.getValue().equals(poster.getTag())) changed = true;
            }
        }
        if (!changed && visible.equals(visiblePosters)) return;
        MediaImages.cancelPending();
        for (ImageView poster : visiblePosters) if (!visible.contains(poster)) {
            poster.setTag(null); poster.setImageDrawable(null);
        }
        visiblePosters.clear(); visiblePosters.addAll(visible);
        for (ImageView poster : visible) MediaImages.load(poster, posterUrls.get(poster));
    }
    private void categoryNavigation(View card) {
        if (selectedCategory == null) return;
        card.setNextFocusUpId(selectedCategory.getId());
        card.setOnKeyListener((view, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_DPAD_UP || selectedCategory == null) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) selectedCategory.requestFocus();
            return true;
        });
    }
    private void startSearch(String word) {
        cancelCatalog();
        catalog = new MediaCatalog(engine, worker, library, MediaCatalog.Mode.SEARCH, word, () -> { if (catalogVisible) updateCatalog(); });
        restoreCatalog(""); catalog.load(false);
    }
    private void restoreCatalog(String focusKey) {
        boolean searching = catalog.mode == MediaCatalog.Mode.SEARCH;
        parent = searching || !catalog.query.isEmpty() ? () -> showLibrary("") : null;
        listing = () -> restoreCatalog(focusKey);
        frame(searching ? catalog.query : getString(R.string.media_title)); catalogVisible = true; catalogCards.clear();
        if (!searching) {
            categories(catalog.query);
            if (selectedCategory != null) selectedCategory.requestFocus();
        }
        catalogStatus = text(this, "", 17, 0xffb9c4d5); content.addView(catalogStatus);
        catalogFailures = text(this, "", 15, 0xffb9c4d5); catalogFailures.setMaxLines(2);
        catalogFailures.setEllipsize(android.text.TextUtils.TruncateAt.END);
        catalogFailures.setPadding(0, dp(this, 8), 0, dp(this, 12)); content.addView(catalogFailures);
        catalogEmpty = text(this, "", 18, 0xffb9c4d5); catalogEmpty.setVisibility(View.GONE); content.addView(catalogEmpty);
        catalogGrid = new GridLayout(this); catalogGrid.setColumnCount(5); content.addView(catalogGrid);
        catalogActions = row(this); content.addView(catalogActions); updateCatalog();
        View card = catalogCards.get(focusKey); if (card != null) card.post(card::requestFocus);
    }
    private void updateCatalog() {
        if (catalog == null) return;
        updateGenres();
        boolean searching = catalog.mode == MediaCatalog.Mode.SEARCH;
        int status = catalog.loading() ? (searching ? R.string.media_search_progress : R.string.media_catalog_progress)
                : (searching ? R.string.media_search_complete : R.string.media_catalog_complete);
        int completed = catalog.loading() ? catalog.total() - catalog.pending : catalog.completed;
        if (status == R.string.media_catalog_complete) catalogStatus.setText(getString(status, catalog.results.groups.size()));
        else catalogStatus.setText(getString(status, completed, catalog.total(), catalog.results.groups.size()));
        if (catalog.showingCache()) catalogStatus.append(" · " + getString(R.string.media_cached_content));
        List<String> failed = catalog.failures();
        catalogFailures.setText(failed.isEmpty() ? "" : getString(R.string.media_search_failures, android.text.TextUtils.join("、", failed)));
        boolean removedFocus = false;
        java.util.Iterator<Map.Entry<String, LinearLayout>> cards = catalogCards.entrySet().iterator();
        while (cards.hasNext()) {
            Map.Entry<String, LinearLayout> entry = cards.next();
            if (!catalog.results.groups.containsKey(entry.getKey())) {
                removedFocus |= entry.getValue().hasFocus();
                posterUrls.remove((ImageView) entry.getValue().getChildAt(0));
                catalogGrid.removeView(entry.getValue()); cards.remove();
            }
        }
        for (MediaCatalog.Group group : catalog.results.groups.values()) {
            LinearLayout card = catalogCards.get(group.key);
            String label = group.movie.year + " · " + getString(R.string.media_source_count, group.sources.size());
            if (card == null) {
                card = movieCard(group.movie, group.poster(), label, () -> {
                    listing = () -> restoreCatalog(group.key); groupedDetail(group, 0);
                });
                catalogCards.put(group.key, card); catalogGrid.addView(card, cardLayout());
                int index = catalogCards.size() - 1;
                if (index < 5) categoryNavigation(card);
                if (index == 0) {
                    if (selectedCategory != null) selectedCategory.setNextFocusDownId(card.getId());
                    else card.post(card::requestFocus);
                }
            } else {
                ((TextView) card.getChildAt(2)).setText(label);
                ImageView poster = (ImageView) card.getChildAt(0);
                posterUrls.put(poster, group.poster());
            }
        }
        if (selectedCategory != null) {
            selectedCategory.setNextFocusDownId(catalogGrid.getChildCount() == 0 ? View.NO_ID : catalogGrid.getChildAt(0).getId());
            for (int i = 0; i < Math.min(5, catalogGrid.getChildCount()); i++) categoryNavigation(catalogGrid.getChildAt(i));
        }
        if (removedFocus && catalogGrid.getChildCount() > 0) catalogGrid.getChildAt(0).requestFocus();
        content.post(this::refreshPosters);
        catalogActions.removeAllViews();
        catalogEmpty.setVisibility(!catalog.loading() && catalog.results.groups.isEmpty() ? View.VISIBLE : View.GONE);
        catalogEmpty.setText(getString(catalog.total() == 0 ? R.string.media_no_enabled_sources : R.string.media_empty));
        if (!catalog.loading()) {
            if (!failed.isEmpty()) addButton(this, catalogActions, getString(R.string.media_retry_sources), () -> catalog.load(true));
            if (catalog.hasMore()) addButton(this, catalogActions, getString(R.string.media_load_more), () -> catalog.load(false));
        }
    }
    private void groupedDetail(MediaCatalog.Group group, int index) {
        MediaCatalog.Reference reference = group.sources.get(index);
        if (reference.movie.folder) { folder(reference.site, reference.movie, listing); return; }
        parent = listing; frame(getString(R.string.media_title));
        request(cancellation -> engine.detail(reference.site, reference.movie.id), movie -> renderGroupedDetail(group, index, movie, 0),
                () -> {
                    detailGroup = group; detailSources = sourceChoices(group, index); content.addView(detailSources);
                    discoverSources(group);
                });
    }
    private void folder(Site site, Movie folder, Runnable back) {
        parent = back; frame(folder.title);
        if (catalog != null && catalog.mode != MediaCatalog.Mode.SEARCH) categories(catalog.query);
        request(cancellation -> engine.category(site, folder.id, 1, cancellation),
                page -> renderFolder(site, folder, back, page.movies, page.page, page.pages));
    }
    private void renderFolder(Site site, Movie folder, Runnable back, List<Movie> movies, int page, int pages) {
        parent = back; frame(folder.title);
        listing = () -> renderFolder(site, folder, back, movies, page, pages);
        if (catalog != null && catalog.mode != MediaCatalog.Mode.SEARCH) categories(catalog.query);
        if (movies.isEmpty()) content.addView(text(this, getString(R.string.media_folder_empty), 18, 0xffb9c4d5));
        MediaCatalog.Results entries = new MediaCatalog.Results();
        for (Movie movie : movies) entries.add(site, java.util.Collections.singletonList(movie));
        GridLayout grid = new GridLayout(this); grid.setColumnCount(5); content.addView(grid);
        Runnable restore = listing;
        for (MediaCatalog.Group entry : entries.groups.values()) {
            LinearLayout card = movieCard(entry.movie, entry.poster(), entry.movie.folder ? getString(R.string.media_folder) : site.name,
                    () -> { listing = restore; groupedDetail(entry, 0); });
            grid.addView(card, cardLayout());
            if (grid.getChildCount() <= 5) categoryNavigation(card);
            if (grid.getChildCount() == 1) {
                card.post(card::requestFocus);
                if (selectedCategory != null) selectedCategory.setNextFocusDownId(card.getId());
            }
        }
        if (page < pages) content.addView(button(this, getString(R.string.media_load_more), () -> {
            request(cancellation -> engine.category(site, folder.id, page + 1, cancellation), next -> {
                Map<String, Movie> accumulated = new LinkedHashMap<>();
                for (Movie movie : movies) accumulated.put(movie.id, movie);
                for (Movie movie : next.movies) accumulated.put(movie.id, movie);
                renderFolder(site, folder, back, new ArrayList<>(accumulated.values()), next.page, next.pages);
            });
        }));
    }
    private void renderGroupedDetail(MediaCatalog.Group group, int index, Movie movie, int line) {
        MediaCatalog.Reference reference = group.sources.get(index);
        renderDetail(reference.site, movie, line, selectedLine -> renderGroupedDetail(group, index, movie, selectedLine));
        detailGroup = group; detailSources = sourceChoices(group, index); content.addView(detailSources, 2);
        if (detailLookup == null) discoverSources(group);
    }
    private HorizontalScrollView sourceChoices(MediaCatalog.Group group, int index) {
        LinearLayout choices = row(this);
        for (int i = 0; i < group.sources.size(); i++) {
            final int source = i;
            Button choice = button(this, group.sources.get(i).site.name, () -> groupedDetail(group, source));
            choice.setSelected(i == index);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(this, 44)); params.setMargins(0, 0, dp(this, 12), 0);
            choices.addView(choice, params);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.addView(choices);
        scroll.setPadding(0, dp(this, 16), 0, dp(this, 16));
        choices.getChildAt(index).requestFocus();
        return scroll;
    }
    private void discoverSources(MediaCatalog.Group group) {
        boolean missing = false;
        for (Site site : engine.enabledSites()) {
            if (!site.searchable) continue;
            boolean known = false;
            for (MediaCatalog.Reference reference : group.sources) if (reference.site.key.equals(site.key)) known = true;
            if (!known) missing = true;
        }
        if (!missing) return;
        int ticket = generation;
        detailLookup = new MediaCatalog(engine, worker, library, MediaCatalog.Mode.SEARCH, group.movie.title, () -> {
            if (isDestroyed() || generation != ticket || detailGroup != group || detailLookup == null) return;
            MediaCatalog.Group match = detailLookup.results.groups.get(group.key);
            if (match == null) return;
            for (MediaCatalog.Reference reference : match.sources) group.add(reference.site, reference.movie);
            LinearLayout choices = (LinearLayout) detailSources.getChildAt(0);
            while (choices.getChildCount() < group.sources.size()) {
                final int index = choices.getChildCount();
                addButton(this, choices, group.sources.get(index).site.name, () -> groupedDetail(group, index));
            }
        });
        detailLookup.load(false);
    }
    private void renderDetail(Site site, Movie movie, int selectedLine, java.util.function.IntConsumer changeLine) {
        content.removeAllViews();
        TextView title = text(this, movie.title, 36, Color.WHITE); content.addView(title);
        content.addView(text(this, movie.year + "  " + movie.remark + "  " + site.name, 16, 0xffb9c4d5));
        TextView description = text(this, Html.fromHtml(movie.summary, Html.FROM_HTML_MODE_LEGACY).toString(), 17, 0xffb9c4d5);
        description.setMaxLines(3); description.setPadding(0, dp(this, 18), 0, dp(this, 22)); content.addView(description);
        if (movie.lines.isEmpty()) {
            content.addView(text(this, getString(R.string.media_no_episodes), 18, Color.WHITE)); return;
        }
        LinearLayout lines = row(this);
        for (int i = 0; i < movie.lines.size(); i++) {
            final int index = i; addButton(this, lines, (i == selectedLine ? "✓ " : "") + movie.lines.get(i).name, () -> changeLine.accept(index));
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this); scroll.addView(lines); content.addView(scroll);
        GridLayout episodes = new GridLayout(this); episodes.setColumnCount(6); episodes.setPadding(0, dp(this, 24), 0, 0);
        Line line = movie.lines.get(selectedLine);
        for (int i = 0; i < line.episodes.size(); i++) {
            final int index = i;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(); params.width = dp(this, 118); params.height = dp(this, 46); params.setMargins(0, 0, dp(this, 12), dp(this, 14));
            episodes.addView(button(this, line.episodes.get(i).name, () -> play(site.key, movie.id, selectedLine, index, 0)), params);
        }
        content.addView(episodes);
    }
    private void history() {
        cancelCatalog(); parent = this::home; frame(getString(R.string.media_continue)); content.removeAllViews(); JSONArray items = engine.history();
        if (items.length() == 0) { message(getString(R.string.media_no_history)); return; }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            String label = item.optString("title") + "  " + getString(R.string.media_history_position, item.optInt("episode") + 1, item.optLong("position") / 60000);
            content.addView(button(this, label, () -> play(item.optString("site"), item.optString("id"), item.optInt("line"), item.optInt("episode"), item.optLong("position"))));
        }
    }
    private void play(String site, String id, int line, int episode, long position) {
        JSONArray references = new JSONArray();
        if (detailGroup != null) for (MediaCatalog.Reference reference : detailGroup.sources) {
            try { references.put(new JSONObject().put("site", reference.site.key).put("id", reference.movie.id)); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
        if (detailLookup != null) detailLookup.cancel();
        detailLookup = null;
        startActivity(new Intent(this, MediaPlayerActivity.class).putExtra("site", site).putExtra("id", id)
                .putExtra("line", line).putExtra("episode", episode).putExtra("position", position).putExtra("sources", references.toString()));
    }
    @Override public void onBackPressed() {
        generation++; if (parent == null) super.onBackPressed(); else parent.run();
    }
    @Override protected void onDestroy() {
        if (phoneDialog != null) phoneDialog.dismiss();
        sourceUpdateCancellation.cancel(); sourceUpdates.shutdownNow();
        generation++; cancelRequest(); cancelDetailLookup(); cancelCatalog(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
    @Override protected void onStop() {
        if (phoneDialog != null) phoneDialog.dismiss();
        super.onStop();
    }
}
