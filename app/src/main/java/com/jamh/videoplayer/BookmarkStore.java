package com.jamh.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1.6: per-video bookmarks, stored in SharedPreferences "bookmarks" under the video's
 * {@link VideoKey} (content fingerprint), so they follow the video in and out of Private.
 * Each value is a JSON array of {id, t (ms), name, created}. Kept sorted by time.
 */
final class BookmarkStore {

    static final String FILE = "bookmarks";
    /** Two bookmarks closer than this are treated as the same spot (the newer one is not added). */
    static final long MERGE_WINDOW_MS = 1_000;
    static final int MAX_PER_VIDEO = 500;
    static final int MAX_NAME = 80;

    static final class Bookmark {
        final long id;
        final long timeMs;
        final String name;
        final long createdMs;

        Bookmark(long id, long timeMs, String name, long createdMs) {
            this.id = id;
            this.timeMs = Math.max(0, timeMs);
            this.name = name == null ? "" : name;
            this.createdMs = createdMs;
        }

        /** The name, or "Bookmark at 12:34" when it has none. */
        String label() {
            return name.isEmpty() ? "Bookmark at " + Fmt.time(timeMs) : name;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("t", timeMs).put("name", name).put("created", createdMs);
        }
    }

    private final SharedPreferences prefs;

    BookmarkStore(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    List<Bookmark> list(@Nullable String videoKey) {
        List<Bookmark> out = new ArrayList<>();
        if (videoKey == null) return out;
        String raw = prefs.getString(videoKey, null);
        if (raw == null) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !o.has("t")) continue;
                out.add(new Bookmark(o.optLong("id", i + 1), o.optLong("t"), o.optString("name", ""),
                        o.optLong("created")));
            }
        } catch (JSONException ignored) {
            // Corrupt value: treat as empty rather than crash the player.
        }
        sort(out);
        return out;
    }

    /** Adds a bookmark; returns it, or the existing one if there is already one within a second. */
    @Nullable
    Bookmark add(@Nullable String videoKey, long timeMs, @Nullable String name) {
        if (videoKey == null) return null;
        List<Bookmark> l = list(videoKey);
        for (Bookmark b : l) {
            if (Math.abs(b.timeMs - timeMs) < MERGE_WINDOW_MS) {
                String n = cleanName(name);
                if (!n.isEmpty() && b.name.isEmpty()) return rename(videoKey, b.id, n);
                return b;
            }
        }
        if (l.size() >= MAX_PER_VIDEO) return null;
        long id = 1;
        for (Bookmark b : l) id = Math.max(id, b.id + 1);
        Bookmark nb = new Bookmark(id, timeMs, cleanName(name), System.currentTimeMillis());
        l.add(nb);
        save(videoKey, l);
        return nb;
    }

    @Nullable
    Bookmark rename(@Nullable String videoKey, long id, @Nullable String name) {
        List<Bookmark> l = list(videoKey);
        for (int i = 0; i < l.size(); i++) {
            Bookmark b = l.get(i);
            if (b.id == id) {
                Bookmark nb = new Bookmark(b.id, b.timeMs, cleanName(name), b.createdMs);
                l.set(i, nb);
                save(videoKey, l);
                return nb;
            }
        }
        return null;
    }

    boolean delete(@Nullable String videoKey, long id) {
        List<Bookmark> l = list(videoKey);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id == id) {
                l.remove(i);
                save(videoKey, l);
                return true;
            }
        }
        return false;
    }

    /** Puts a deleted bookmark back (Undo). */
    void restore(@Nullable String videoKey, Bookmark b) {
        if (videoKey == null || b == null) return;
        List<Bookmark> l = list(videoKey);
        for (Bookmark x : l) if (x.id == b.id) return;
        l.add(b);
        save(videoKey, l);
    }

    static String cleanName(@Nullable String name) {
        if (name == null) return "";
        String n = name.replaceAll("\\s+", " ").trim();
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }

    static void sort(List<Bookmark> l) {
        Collections.sort(l, (a, b) -> a.timeMs != b.timeMs ? Long.compare(a.timeMs, b.timeMs) : Long.compare(a.id, b.id));
    }

    private void save(String videoKey, List<Bookmark> l) {
        if (l.isEmpty()) {
            prefs.edit().remove(videoKey).apply();
            return;
        }
        sort(l);
        JSONArray a = new JSONArray();
        try {
            for (Bookmark b : l) a.put(b.toJson());
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(videoKey, a.toString()).apply();
    }
}
