package com.jamh.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1.6.0: resume points for Private videos, kept apart from the gallery ones in their own
 * SharedPreferences file (app-private, excluded from backup and device transfer). Keyed by the
 * entry's logical file name (the last segment of its vault Uri: a random id, no title).
 */
final class PrivateResume {

    static final String FILE = "private_resume";

    private PrivateResume() {
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** {positionMs, durationMs, savedAtMs} or null. */
    @Nullable
    static long[] get(Context c, @Nullable String key) {
        if (key == null) return null;
        String v = p(c).getString(key, null);
        if (v == null) return null;
        try {
            String[] s = v.split(",");
            return new long[]{Long.parseLong(s[0]), Long.parseLong(s[1]), s.length > 2 ? Long.parseLong(s[2]) : 0};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Same rules as the gallery: nothing under 3 s, cleared near the end. */
    static void save(Context c, @Nullable String key, long posMs, long durMs) {
        if (key == null) return;
        boolean nearEnd = durMs > 0 && (durMs - posMs < 5000 || posMs > durMs * 0.97);
        if (posMs < 3000 || nearEnd) {
            p(c).edit().remove(key).apply();
        } else {
            p(c).edit().putString(key, posMs + "," + Math.max(durMs, 0) + "," + System.currentTimeMillis()).apply();
        }
    }

    static void clear(Context c, @Nullable String key) {
        if (key != null) p(c).edit().remove(key).apply();
    }

    /** Entries with a resume point, most recently watched first ("Continue watching"). */
    static List<Vault.Entry> continueWatching(Context c, List<Vault.Entry> entries, int max) {
        Map<String, ?> all = p(c).getAll();
        List<Vault.Entry> out = new ArrayList<>();
        for (Vault.Entry e : entries) if (all.containsKey(e.file)) out.add(e);
        out.sort((a, b) -> Long.compare(savedAt(c, b), savedAt(c, a)));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private static long savedAt(Context c, Vault.Entry e) {
        long[] r = get(c, e.file);
        return r == null ? 0 : r[2];
    }
}
