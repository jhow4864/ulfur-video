package com.jamh.videoplayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 1.7.0: filters and sorts library items for a {@link LibraryView}.
 *
 * <p>Large libraries: the MediaStore is queried once per refresh with a narrow projection;
 * changing the sort or a filter never re-queries. Each pass reads the watch history once
 * ({@link WatchHistory.Snapshot}), extracts the sort keys of every item once into a flat
 * {@link Facts} array, filters on those and sorts the array (no per-comparison lookups), so
 * 10k videos sort in a few milliseconds.
 */
final class LibrarySort {

    private LibrarySort() {
    }

    /** Sort / filter keys of one item, extracted once per pass. */
    static final class Facts {
        Object item;
        String name = "";
        long dateMs;
        long durationMs;
        long size;
        int width, height;
        boolean portrait;
        long lastWatchedMs;
        int watch = WatchHistory.UNWATCHED;

        int shortSide() {
            return width > 0 && height > 0 ? Math.min(width, height) : 0;
        }

        long pixels() {
            return (long) Math.max(0, width) * Math.max(0, height);
        }
    }

    interface Extractor<T> {
        void fill(T item, Facts f);
    }

    static Extractor<VideoItem> videos(WatchHistory.Snapshot w) {
        return (v, f) -> {
            f.name = v.title;
            f.dateMs = v.dateAddedSec * 1000L;
            f.durationMs = v.durationMs;
            f.size = v.sizeBytes;
            f.width = v.width;
            f.height = v.height;
            f.portrait = v.isPortrait();
            String key = v.resumeKey();
            f.lastWatchedMs = w.lastWatched(key);
            f.watch = w.state(key);
        };
    }

    static Extractor<Vault.Entry> vault(WatchHistory.Snapshot w) {
        return (e, f) -> {
            String n = e.name == null ? "" : e.name;
            int dot = n.lastIndexOf('.');
            f.name = dot > 0 ? n.substring(0, dot) : n;
            f.dateMs = e.addedMs;
            f.durationMs = e.durationMs;
            f.size = e.size;
            f.width = e.width;
            f.height = e.height;
            f.portrait = e.width > 0 && e.height > e.width;
            f.lastWatchedMs = w.lastWatched(e.file);
            f.watch = w.state(e.file);
        };
    }

    /** Resolution class of the short side: 0 SD (&lt;720), 1 HD, 2 Full HD, 3 4K (≥2160), -1 unknown. */
    static int resolutionClass(int w, int h) {
        if (w <= 0 || h <= 0) return -1;
        int s = Math.min(w, h);
        if (s < 720) return 0;
        if (s < 1080) return 1;
        if (s < 2160) return 2;
        return 3;
    }

    static boolean matches(Facts f, int filters) {
        if (filters == 0) return true;
        if ((filters & LibraryView.G_DURATION) != 0) {
            long d = f.durationMs;
            if (d <= 0) return false;
            int bit = d < LibraryView.SHORT_MAX_MS ? LibraryView.F_SHORT
                    : d <= LibraryView.LONG_MIN_MS ? LibraryView.F_MEDIUM : LibraryView.F_LONG;
            if ((filters & bit) == 0) return false;
        }
        if ((filters & LibraryView.G_RESOLUTION) != 0) {
            int c = resolutionClass(f.width, f.height);
            if (c < 0) return false;
            int bit = new int[]{LibraryView.F_SD, LibraryView.F_HD, LibraryView.F_FHD, LibraryView.F_4K}[c];
            if ((filters & bit) == 0) return false;
        }
        if ((filters & LibraryView.G_ORIENTATION) != 0) {
            if (f.width <= 0 || f.height <= 0) return false;
            int bit = f.portrait ? LibraryView.F_PORTRAIT : LibraryView.F_LANDSCAPE;
            if ((filters & bit) == 0) return false;
        }
        if ((filters & LibraryView.G_WATCH) != 0) {
            int bit = f.watch == WatchHistory.UNWATCHED ? LibraryView.F_UNWATCHED
                    : f.watch == WatchHistory.IN_PROGRESS ? LibraryView.F_IN_PROGRESS : 0;
            if ((filters & bit) == 0) return false;
        }
        return true;
    }

    /** Filters and sorts {@code items} (the input list is not modified). */
    @SuppressWarnings("unchecked")
    static <T> List<T> apply(List<T> items, LibraryView v, Extractor<T> x) {
        List<Facts> kept = new ArrayList<>(items.size());
        for (T item : items) {
            Facts f = new Facts();
            f.item = item;
            x.fill(item, f);
            if (matches(f, v.filters)) kept.add(f);
        }
        Facts[] arr = kept.toArray(new Facts[0]);
        Arrays.sort(arr, comparator(v.effectiveSort(), v.effectiveDesc()));
        List<T> out = new ArrayList<>(arr.length);
        for (Facts f : arr) out.add((T) f.item);
        return out;
    }

    /** Sort only (no filters), e.g. the player's folder playlist in the library's order. */
    static <T> List<T> order(List<T> items, LibraryView v, Extractor<T> x) {
        return apply(items, v.withoutFilters(), x);
    }

    static Comparator<Facts> comparator(int key, boolean desc) {
        Comparator<Facts> byName = (a, b) -> naturalCompare(a.name, b.name);
        Comparator<Facts> c;
        switch (key) {
            case LibraryView.NAME:
                c = byName;
                break;
            case LibraryView.LENGTH:
                c = Comparator.comparingLong(f -> f.durationMs);
                break;
            case LibraryView.SIZE:
                c = Comparator.comparingLong(f -> f.size);
                break;
            case LibraryView.RESOLUTION:
                c = Comparator.<Facts>comparingInt(Facts::shortSide).thenComparingLong(Facts::pixels);
                break;
            case LibraryView.WATCHED: {
                // Never-watched videos stay at the end in both directions.
                Comparator<Facts> t = Comparator.comparingLong(f -> f.lastWatchedMs);
                Comparator<Facts> dir = desc ? t.reversed() : t;
                return Comparator.<Facts>comparingInt(f -> f.lastWatchedMs > 0 ? 0 : 1)
                        .thenComparing(dir).thenComparing(byName);
            }
            default:
                c = Comparator.comparingLong(f -> f.dateMs);
                break;
        }
        if (desc) c = c.reversed();
        return key == LibraryView.NAME ? c : c.thenComparing(byName);
    }

    /** Folders list: date = newest video, length = number of videos. */
    static List<Folder> folders(List<Folder> list, LibraryView v) {
        Comparator<Folder> byName = (a, b) -> naturalCompare(a.name, b.name);
        Comparator<Folder> c;
        switch (v.effectiveSort()) {
            case LibraryView.NAME: c = byName; break;
            case LibraryView.SIZE: c = Comparator.comparingLong(f -> f.totalSize); break;
            case LibraryView.LENGTH: c = Comparator.comparingInt(f -> f.videos.size()); break;
            default: c = Comparator.comparingLong(f -> f.latestDate); break;
        }
        if (v.effectiveDesc()) c = c.reversed();
        List<Folder> out = new ArrayList<>(list);
        out.sort(v.effectiveSort() == LibraryView.NAME ? c : c.thenComparing(byName));
        return out;
    }

    static long totalSize(List<VideoItem> list) {
        long t = 0;
        for (VideoItem v : list) t += Math.max(0, v.sizeBytes);
        return t;
    }

    /**
     * Case-insensitive "natural" order: digit runs compare as numbers, so "Episode 2" sorts before
     * "Episode 10".
     */
    static int naturalCompare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int i = 0, j = 0, na = a.length(), nb = b.length();
        while (i < na && j < nb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (si < na && a.charAt(si) == '0') si++;
                while (sj < nb && b.charAt(sj) == '0') sj++;
                int ei = si, ej = sj;
                while (ei < na && Character.isDigit(a.charAt(ei))) ei++;
                while (ej < nb && Character.isDigit(b.charAt(ej))) ej++;
                int la = ei - si, lb = ej - sj;
                if (la != lb) return la - lb;
                for (int k = 0; k < la; k++) {
                    int d = a.charAt(si + k) - b.charAt(sj + k);
                    if (d != 0) return d;
                }
                i = ei;
                j = ej;
            } else {
                int d = Character.toLowerCase(Character.toUpperCase(ca)) - Character.toLowerCase(Character.toUpperCase(cb));
                if (d != 0) return d;
                i++;
                j++;
            }
        }
        if (na - i != nb - j) return (na - i) - (nb - j);
        return a.compareTo(b);
    }
}
