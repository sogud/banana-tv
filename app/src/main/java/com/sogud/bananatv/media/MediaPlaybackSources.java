package com.sogud.bananatv.media;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.sogud.bananatv.media.MediaModels.*;

/** Only verified same-title/year/type groups and unambiguous episode matches can be candidates. */
final class MediaPlaybackSources {
    static final class Candidate {
        final Site site; final Movie movie; final int line, episode;
        JSONObject stream;
        Candidate(Site site, Movie movie, int line, int episode) { this.site = site; this.movie = movie; this.line = line; this.episode = episode; }
        String label() { return site.name + " · " + movie.lines.get(line).name; }
        String identity() { return site.key + ":" + movie.id + ":" + line + ":" + episode; }
    }
    static int matchEpisode(Line original, int episode, Line target, boolean film) {
        if (film && original.episodes.size() == 1 && target.episodes.size() == 1) return 0;
        String name = episodeName(original.episodes.get(episode).name);
        int found = -1;
        for (int i = 0; i < target.episodes.size(); i++) if (episodeName(target.episodes.get(i).name).equals(name)) {
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }
    private static String episodeName(String name) {
        String value = Normalizer.normalize(name, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        if (value.matches("(?:第\\s*)?0*[0-9]+\\s*(?:集|话|期)?")) return value.replaceAll("[^0-9]", "").replaceFirst("^0+(?!$)", "");
        return value.replaceAll("\\s+", "");
    }
    private static void add(List<Candidate> candidates, Candidate candidate) {
        if (candidate.site.type == 0 || candidate.site.type == 1) {
            String target = candidate.movie.lines.get(candidate.line).episodes.get(candidate.episode).id;
            for (Candidate existing : candidates) {
                String current = existing.movie.lines.get(existing.line).episodes.get(existing.episode).id;
                if (existing.site.key.equals(candidate.site.key) && target.startsWith("http") && target.equals(current)) return;
            }
        }
        candidates.add(candidate);
    }
    static List<Candidate> candidates(MediaEngine engine, Site site, Movie movie, int line, int episode, String references) throws Exception {
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate(site, movie, line, episode));
        boolean film = MediaCategories.kind(movie.type).equals("movie");
        for (int i = 0; i < movie.lines.size(); i++) {
            if (i == line) continue;
            int match = matchEpisode(movie.lines.get(line), episode, movie.lines.get(i), film);
            if (match >= 0) add(candidates, new Candidate(site, movie, i, match));
        }
        JSONArray list = new JSONArray(references == null ? "[]" : references);
        String identity = MediaCatalog.Results.identity(site, movie);
        for (int i = 0; i < list.length() && i < 3; i++) {
            JSONObject reference = list.getJSONObject(i);
            if (reference.optString("site").equals(site.key)) continue;
            try {
                Site other = engine.site(reference.getString("site")); if (!engine.enabled(other)) continue;
                Movie detail = engine.detail(other, reference.getString("id"));
                if (!MediaCatalog.Results.identity(other, detail).equals(identity)) continue;
                for (int j = 0; j < detail.lines.size(); j++) {
                    int match = matchEpisode(movie.lines.get(line), episode, detail.lines.get(j), film);
                    if (match >= 0) add(candidates, new Candidate(other, detail, j, match));
                }
            } catch (Exception | LinkageError ignored) { /* Other matched sources remain available. */ }
        }
        return candidates;
    }
}
