package com.jamh.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;

/**
 * 1.7.0: when each video was last opened, for "Recently watched" sorting and the
 * Unwatched / In progress filters. Gallery videos are keyed like their resume points
 * ({@link VideoItem#resumeKey()}); Private videos live in a separate file keyed by the vault
 * entry's logical name (excluded from backup, like {@link PrivateResume}).
 *
 * <p>State: <b>in progress</b> = has a resume point; <b>watched</b> = opened before and no
 * resume point (finished, or stopped in the first seconds); <b>unwatched</b> = never opened.
 */
final class WatchHistory {

    static final String FILE = "watch_history";
    static final String PRIVATE_FILE = "private_history";

    static final int UNWATCHED = 0, IN_PROGRESS = 1, WATCHED = 2;

    private WatchHistory() {
    }

    private static SharedPreferences p(Context c, boolean priv) {
        return c.getApplicationContext().getSharedPreferences(priv ? PRIVATE_FILE : FILE, Context.MODE_PRIVATE);
    }

    static void mark(Context c, @Nullable String key, boolean priv) {
        mark(c, key, priv, System.currentTimeMillis());
    }

    static void mark(Context c, @Nullable String key, boolean priv, long atMs) {
        if (key == null) return;
        p(c, priv).edit().putLong(key, atMs).apply();
    }

    static void clear(Context c, @Nullable String key, boolean priv) {
        if (key != null) p(c, priv).edit().remove(key).apply();
    }

    /** One read of both prefs files, reused for a whole sort / filter pass. */
    static Snapshot gallery(Context c) {
        return new Snapshot(p(c, false).getAll(),
                c.getApplicationContext().getSharedPreferences("resume", Context.MODE_PRIVATE).getAll());
    }

    static Snapshot vault(Context c) {
        return new Snapshot(p(c, true).getAll(),
                c.getApplicationContext().getSharedPreferences(PrivateResume.FILE, Context.MODE_PRIVATE).getAll());
    }

    static Snapshot empty() {
        return new Snapshot(Collections.emptyMap(), Collections.emptyMap());
    }

    static final class Snapshot {
        private final Map<String, ?> history;
        private final Map<String, ?> resume;

        Snapshot(Map<String, ?> history, Map<String, ?> resume) {
            this.history = history;
            this.resume = resume;
        }

        /** Last time the video was opened or its resume point saved; 0 = never. */
        long lastWatched(String key) {
            long t = 0;
            Object h = history.get(key);
            if (h instanceof Long) t = (Long) h;
            Object r = resume.get(key);
            if (r instanceof String) {
                String[] s = ((String) r).split(",");
                if (s.length > 2) {
                    try {
                        t = Math.max(t, Long.parseLong(s[2]));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return t;
        }

        int state(String key) {
            if (resume.containsKey(key)) return IN_PROGRESS;
            return history.containsKey(key) ? WATCHED : UNWATCHED;
        }
    }
}
