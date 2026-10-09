package com.jamh.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 1.7.0: how one library screen is sorted and filtered (Videos, a folder's contents, Clips, the
 * Vault, and the Folders list). Immutable; remembered per screen in the "settings" prefs.
 *
 * <p>Filters are a bit mask. Chips in the same group are OR-ed (Short or Long), groups are AND-ed
 * (Long and 4K). The "Largest first" storage view overrides the sort with size, descending.
 */
final class LibraryView {

    // Sort keys
    static final int DATE = 0, NAME = 1, LENGTH = 2, SIZE = 3, RESOLUTION = 4, WATCHED = 5;
    static final int SORT_COUNT = 6;

    // Filter bits
    static final int F_SHORT = 1, F_MEDIUM = 1 << 1, F_LONG = 1 << 2;
    static final int F_SD = 1 << 3, F_HD = 1 << 4, F_FHD = 1 << 5, F_4K = 1 << 6;
    static final int F_LANDSCAPE = 1 << 7, F_PORTRAIT = 1 << 8;
    static final int F_UNWATCHED = 1 << 9, F_IN_PROGRESS = 1 << 10;

    static final int G_DURATION = F_SHORT | F_MEDIUM | F_LONG;
    static final int G_RESOLUTION = F_SD | F_HD | F_FHD | F_4K;
    static final int G_ORIENTATION = F_LANDSCAPE | F_PORTRAIT;
    static final int G_WATCH = F_UNWATCHED | F_IN_PROGRESS;
    static final int ALL_FILTERS = G_DURATION | G_RESOLUTION | G_ORIENTATION | G_WATCH;
    static final int[] FILTER_BITS = {F_SHORT, F_MEDIUM, F_LONG, F_SD, F_HD, F_FHD, F_4K,
            F_LANDSCAPE, F_PORTRAIT, F_UNWATCHED, F_IN_PROGRESS};

    /** Short: under 4 min. Medium: 4–20 min. Long: over 20 min (YouTube's buckets). */
    static final long SHORT_MAX_MS = 4 * 60_000L, LONG_MIN_MS = 20 * 60_000L;

    // Screens (one remembered view each)
    static final String VIDEOS = "videos", FOLDER = "folder", CLIPS = "clips", VAULT = "vault", FOLDERS = "folders";

    final int sort;
    final boolean desc;
    /** "Largest first" storage view. */
    final boolean storage;
    final int filters;

    LibraryView(int sort, boolean desc, boolean storage, int filters) {
        this.sort = sort < 0 || sort >= SORT_COUNT ? DATE : sort;
        this.desc = desc;
        this.storage = storage;
        this.filters = filters & ALL_FILTERS;
    }

    static LibraryView defaults() {
        return new LibraryView(DATE, true, false, 0);
    }

    /** Natural direction for a key: names A–Z, everything else biggest / newest first. */
    static boolean defaultDesc(int key) {
        return key != NAME;
    }

    int effectiveSort() {
        return storage ? SIZE : sort;
    }

    boolean effectiveDesc() {
        return storage || desc;
    }

    LibraryView withSort(int key) {
        return new LibraryView(key, key == sort ? desc : defaultDesc(key), false, filters);
    }

    LibraryView withDesc(boolean d) {
        return new LibraryView(sort, d, false, filters);
    }

    LibraryView withStorage(boolean on) {
        return new LibraryView(sort, desc, on, filters);
    }

    LibraryView withFilter(int bit, boolean on) {
        return new LibraryView(sort, desc, storage, on ? filters | bit : filters & ~bit);
    }

    LibraryView withoutFilters() {
        return new LibraryView(sort, desc, storage, 0);
    }

    boolean has(int bit) {
        return (filters & bit) != 0;
    }

    int filterCount() {
        return Integer.bitCount(filters);
    }

    // ------------------------------------------------------------------ labels

    static String sortName(int key, boolean folders) {
        switch (key) {
            case NAME: return "Name";
            case LENGTH: return folders ? "Number of videos" : "Length";
            case SIZE: return "Size";
            case RESOLUTION: return "Resolution";
            case WATCHED: return "Recently watched";
            default: return "Date added";
        }
    }

    static String directionName(int key, boolean desc) {
        switch (key) {
            case NAME: return desc ? "Z–A" : "A–Z";
            case LENGTH: return desc ? "Longest first" : "Shortest first";
            case SIZE: return desc ? "Largest first" : "Smallest first";
            case RESOLUTION: return desc ? "Highest first" : "Lowest first";
            case WATCHED: return desc ? "Most recent first" : "Least recent first";
            default: return desc ? "Newest first" : "Oldest first";
        }
    }

    /** Folders list: "Number of videos" reads better as "Most / Fewest first". */
    static String directionName(int key, boolean desc, boolean folders) {
        if (folders && key == LENGTH) return desc ? "Most first" : "Fewest first";
        return directionName(key, desc);
    }

    /** Short label for the sort chip, e.g. "Date added ↓". */
    String chipLabel(boolean folders) {
        if (storage) return "Size ↓";
        return sortName(sort, folders) + (desc ? " ↓" : " ↑");
    }

    static String filterName(int bit) {
        switch (bit) {
            case F_SHORT: return "Short";
            case F_MEDIUM: return "Medium";
            case F_LONG: return "Long";
            case F_SD: return "SD";
            case F_HD: return "HD";
            case F_FHD: return "Full HD";
            case F_4K: return "4K";
            case F_LANDSCAPE: return "Landscape";
            case F_PORTRAIT: return "Portrait";
            case F_UNWATCHED: return "Unwatched";
            case F_IN_PROGRESS: return "In progress";
            default: return "";
        }
    }

    static String filterHint(int bit) {
        switch (bit) {
            case F_SHORT: return "Under 4 min";
            case F_MEDIUM: return "4–20 min";
            case F_LONG: return "Over 20 min";
            case F_SD: return "Below 720p";
            case F_HD: return "720p";
            case F_FHD: return "1080p";
            case F_4K: return "2160p and up";
            default: return null;
        }
    }

    // ------------------------------------------------------------------ persistence

    String encode() {
        return sort + "," + (desc ? 1 : 0) + "," + (storage ? 1 : 0) + "," + filters;
    }

    @NonNull
    static LibraryView decode(@Nullable String s, @NonNull LibraryView fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            String[] p = s.split(",");
            return new LibraryView(Integer.parseInt(p[0]), "1".equals(p[1]), "1".equals(p[2]),
                    Integer.parseInt(p[3]));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** The screen's remembered view; screens never set before start from the pre-1.7 sort menu choice. */
    static LibraryView load(Context c, String screen) {
        LibraryView fallback = VAULT.equals(screen) ? defaults() : fromLegacy(Prefs.sort(c));
        return decode(prefs(c).getString("view_" + screen, null), fallback);
    }

    static void save(Context c, String screen, LibraryView v) {
        prefs(c).edit().putString("view_" + screen, v.encode()).apply();
    }

    /** Pre-1.7 global sort ({@link Sort}) to a view. */
    static LibraryView fromLegacy(int legacy) {
        switch (legacy) {
            case Sort.NAME: return new LibraryView(NAME, false, false, 0);
            case Sort.SIZE: return new LibraryView(SIZE, true, false, 0);
            case Sort.DURATION: return new LibraryView(LENGTH, true, false, 0);
            default: return defaults();
        }
    }

    /** Closest pre-1.7 sort constant (kept in old intents / the legacy pref). */
    int toLegacy() {
        switch (effectiveSort()) {
            case NAME: return Sort.NAME;
            case SIZE: return Sort.SIZE;
            case LENGTH: return Sort.DURATION;
            default: return Sort.DATE;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LibraryView)) return false;
        LibraryView v = (LibraryView) o;
        return v.sort == sort && v.desc == desc && v.storage == storage && v.filters == filters;
    }

    @Override
    public int hashCode() {
        return encode().hashCode();
    }

    @Override
    public String toString() {
        return "LibraryView(" + encode() + ")";
    }
}
