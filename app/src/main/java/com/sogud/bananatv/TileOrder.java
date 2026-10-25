package com.sogud.bananatv;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Retain missing packages so reinstalling an app restores its place. */
final class TileOrder {
    static List<String> reconcile(List<String> saved, List<String> discovered) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(saved);
        keys.addAll(discovered);
        return new ArrayList<>(keys);
    }

    static List<String> move(List<String> saved, String moving, String target) {
        List<String> result = new ArrayList<>(saved);
        int from = result.indexOf(moving);
        int to = result.indexOf(target);
        if (from < 0 || to < 0 || from == to) return result;
        result.remove(from);
        result.add(to, moving);
        return result;
    }
}
