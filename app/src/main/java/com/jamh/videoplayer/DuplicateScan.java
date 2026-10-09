package com.jamh.videoplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 1.9.0: the background duplicate scan. One scan at a time for the whole app (it keeps running if the
 * screen is closed or rotated; the screen re-attaches and shows progress / the result). Hashes are
 * cached (see {@link HashCache}), so a re-scan only reads new or changed videos.
 */
final class DuplicateScan {

    interface Listener {
        void onProgress(String phase, int done, int total);

        void onFinished(@Nullable Result result, @Nullable String error, boolean cancelled);
    }

    /** A finished scan. */
    static final class Result {
        final List<DuplicateFinder.Group> groups;
        final int scanned;
        final boolean includedPrivate;
        final long finishedAtMs;
        final DuplicateFinder.Stats stats;

        Result(List<DuplicateFinder.Group> groups, int scanned, boolean includedPrivate, long finishedAtMs,
               DuplicateFinder.Stats stats) {
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
            this.scanned = scanned;
            this.includedPrivate = includedPrivate;
            this.finishedAtMs = finishedAtMs;
            this.stats = stats;
        }

        /** Private locked again: drop Private videos (and groups left with one video). */
        Result withoutPrivate() {
            List<DuplicateFinder.Group> out = new ArrayList<>();
            for (DuplicateFinder.Group g : groups) {
                List<DuplicateFinder.Item> keep = new ArrayList<>();
                for (DuplicateFinder.Item it : g.items) if (!it.isPrivate()) keep.add(it);
                if (keep.size() >= 2) out.add(keep.size() == g.items.size() ? g : new DuplicateFinder.Group(g.exact, keep));
            }
            return new Result(out, scanned, false, finishedAtMs, stats);
        }

        /** Videos removed meanwhile (deleted from the duplicates screen): drop them. */
        Result without(Set<String> keys) {
            List<DuplicateFinder.Group> out = new ArrayList<>();
            for (DuplicateFinder.Group g : groups) {
                List<DuplicateFinder.Item> keep = new ArrayList<>();
                for (DuplicateFinder.Item it : g.items) if (!keys.contains(it.key)) keep.add(it);
                if (keep.size() >= 2) out.add(keep.size() == g.items.size() ? g : new DuplicateFinder.Group(g.exact, keep));
            }
            return new Result(out, scanned, includedPrivate, finishedAtMs, stats);
        }
    }

    /** Lists what to scan (tests replace it). */
    interface Source {
        List<DuplicateFinder.Item> list(Context app, boolean includePrivate);
    }

    private static final DuplicateScan INSTANCE = new DuplicateScan();

    static DuplicateScan get() {
        return INSTANCE;
    }

    @VisibleForTesting static Source sourceOverride;
    @VisibleForTesting static DuplicateFinder.Hasher hasherOverride;
    @VisibleForTesting static Executor executorOverride;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ulfur-dupes");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    @Nullable private Listener listener;
    private volatile boolean running, cancelled;
    @Nullable private Result last;
    private String phase = "";
    private int done, total;

    private DuplicateScan() {
    }

    void setListener(@Nullable Listener l) {
        listener = l;
    }

    boolean isRunning() {
        return running;
    }

    @Nullable
    Result last() {
        return last;
    }

    void setLast(@Nullable Result r) {
        last = r;
    }

    String phase() {
        return phase;
    }

    int done() {
        return done;
    }

    int total() {
        return total;
    }

    void cancel() {
        cancelled = true;
    }

    /** Starts a scan unless one is running. Results arrive on the main thread. */
    void start(Context ctx, boolean includePrivate) {
        if (running) return;
        final Context app = ctx.getApplicationContext();
        running = true;
        cancelled = false;
        phase = "Listing videos";
        done = 0;
        total = 0;
        Executor ex = executorOverride != null ? executorOverride : worker;
        ex.execute(() -> run(app, includePrivate));
    }

    @WorkerThread
    private void run(Context app, boolean includePrivate) {
        Result result = null;
        String error = null;
        boolean wasCancelled = false;
        HashCache cache = HashCache.load(app);
        try {
            Source src = sourceOverride != null ? sourceOverride : DuplicateScan::listAll;
            List<DuplicateFinder.Item> items = src.list(app, includePrivate);
            DuplicateFinder.Hasher h = hasherOverride != null ? hasherOverride : new RealHasher(app);
            DuplicateFinder f = new DuplicateFinder(h, cache, () -> cancelled, this::postProgress);
            List<DuplicateFinder.Group> groups = f.find(items, true);
            Set<String> live = new HashSet<>();
            for (DuplicateFinder.Item it : items) live.add(it.key);
            cache.prune(live);
            result = new Result(groups, items.size(), includePrivate, System.currentTimeMillis(), f.stats);
        } catch (DuplicateFinder.CancelledException e) {
            wasCancelled = true;
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            cache.save(app);   // keep what was hashed, even after Cancel
        }
        final Result r = result;
        final String err = error;
        final boolean c = wasCancelled;
        main.post(() -> {
            running = false;
            if (r != null) last = r;
            Listener l = listener;
            if (l != null) l.onFinished(r, err, c);
        });
    }

    private void postProgress(String ph, int d, int t) {
        main.post(() -> {
            phase = ph;
            done = d;
            total = t;
            Listener l = listener;
            if (l != null && running) l.onProgress(ph, d, t);
        });
    }

    /** Library (MediaStore) videos, plus Private (not trashed) when asked and unlocked. */
    @WorkerThread
    static List<DuplicateFinder.Item> listAll(Context app, boolean includePrivate) {
        List<DuplicateFinder.Item> out = new ArrayList<>();
        for (VideoItem v : VideoRepository.all(app)) out.add(new DuplicateFinder.Item(v));
        if (includePrivate && VaultLock.isUnlocked()) {
            for (Vault.Entry e : Vault.list(app)) out.add(new DuplicateFinder.Item(e));
        }
        return out;
    }

    // ------------------------------------------------------------------ real hashing

    static final class RealHasher implements DuplicateFinder.Hasher {
        private final Context app;

        RealHasher(Context app) {
            this.app = app;
        }

        @Override
        public String partial(DuplicateFinder.Item it) throws IOException {
            if (it.vault != null) {
                String k = it.vault.videoKey;
                if (k == null || k.isEmpty()) k = Vault.computeVideoKey(app, it.vault);
                return k == null ? "" : k;
            }
            String k = VideoKey.readContentKey(app, it.video.uri);
            return k == null ? "" : k;
        }

        @Override
        public String full(DuplicateFinder.Item it, DuplicateFinder.Cancel cancel) throws IOException {
            if (it.vault != null && it.vault.sha256 != null && !it.vault.sha256.isEmpty()) {
                return it.vault.sha256;   // plaintext SHA-256, stored when it moved into Private
            }
            InputStream in = it.vault != null ? Vault.openStream(app, it.vault)
                    : app.getContentResolver().openInputStream(it.video.uri);
            if (in == null) return "";
            try (InputStream s = in) {
                return sha256(s, cancel);
            }
        }

        @Nullable
        @Override
        public DuplicateFinder.FrameHash frames(DuplicateFinder.Item it) throws IOException {
            Uri uri = it.vault != null ? Vault.uriFor(app, it.vault) : it.video.uri;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            Closeable c = null;
            try {
                c = VaultMediaDataSource.setDataSource(app, mmr, uri);
                double[] pos = DuplicateFinder.FRAME_POSITIONS;
                long[] h = new long[pos.length];
                boolean[] ok = new boolean[pos.length];
                int got = 0;
                for (int i = 0; i < pos.length; i++) {
                    long us = (long) (it.durationMs * pos[i]) * 1000L;
                    Bitmap b = frameAt(mmr, us);
                    if (b == null) continue;
                    long[] d = DuplicateFinder.dhash(b);
                    b.recycle();
                    h[i] = d[0];
                    ok[i] = d[1] == 1;
                    got++;
                }
                return got == 0 ? null : new DuplicateFinder.FrameHash(h, ok);
            } catch (RuntimeException e) {
                return null;
            } finally {
                try {
                    mmr.release();
                } catch (IOException | RuntimeException ignored) {
                }
                VaultMediaDataSource.closeQuietly(c);
            }
        }

        @Nullable
        private static Bitmap frameAt(MediaMetadataRetriever mmr, long us) {
            try {
                // Nearest keyframe: no decoding up to the exact time, and the same frame for re-muxed copies.
                if (Build.VERSION.SDK_INT >= 27) {
                    return mmr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 64);
                }
                return mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Streaming SHA-256 (1 MiB reads); returns "" when cancelled. */
    static String sha256(InputStream in, DuplicateFinder.Cancel cancel) throws IOException {
        MessageDigest md = Vault.sha256();
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (cancel.isCancelled()) return "";
            md.update(buf, 0, n);
        }
        return Vault.hex(md.digest());
    }

    // ------------------------------------------------------------------ cache

    /**
     * Hash cache, keyed by the item key and invalidated by a stamp (size, date, duration, name).
     * Library hashes are stored in app-private storage ({@code files/dupes/hashes.tsv}); hashes of
     * Private videos are kept in memory only (exact hashes of Private videos already live in the
     * encrypted index; picture fingerprints of Private videos never touch the disk).
     */
    static final class HashCache implements DuplicateFinder.Cache {
        static final String FILE = "dupes/hashes.tsv";
        private static final Map<String, String[]> PRIVATE_MEMORY = new HashMap<>();

        private final Map<String, String[]> rows = new HashMap<>();   // key -> {stamp, p, f, d}
        private boolean dirty;

        static File file(Context ctx) {
            return new File(ctx.getFilesDir(), FILE);
        }

        static synchronized HashCache load(Context ctx) {
            HashCache c = new HashCache();
            File f = file(ctx);
            if (f.isFile()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String[] p = line.split("\t", -1);
                        if (p.length != 5 || !p[0].startsWith("ms:")) continue;
                        c.rows.put(p[0], new String[]{p[1], p[2], p[3], p[4]});
                    }
                } catch (IOException ignored) {
                    c.rows.clear();
                }
            }
            synchronized (PRIVATE_MEMORY) {
                c.rows.putAll(PRIVATE_MEMORY);
            }
            return c;
        }

        static int field(String name) {
            switch (name) {
                case DuplicateFinder.F_PARTIAL: return 1;
                case DuplicateFinder.F_FULL: return 2;
                default: return 3;
            }
        }

        @Nullable
        @Override
        public synchronized String get(DuplicateFinder.Item it, String name) {
            String[] r = rows.get(it.key);
            if (r == null || !r[0].equals(it.stamp())) return null;
            String v = r[field(name)];
            return v == null || v.isEmpty() ? null : v;
        }

        @Override
        public synchronized void put(DuplicateFinder.Item it, String name, String value) {
            String[] r = rows.get(it.key);
            if (r == null || !r[0].equals(it.stamp())) {
                r = new String[]{it.stamp(), "", "", ""};
                rows.put(it.key, r);
            }
            // An unreadable file caches as "-" for partial/full ("" would mean "not computed").
            r[field(name)] = value == null || value.isEmpty() ? "-" : value;
            dirty = true;
            if (it.isPrivate()) {
                synchronized (PRIVATE_MEMORY) {
                    PRIVATE_MEMORY.put(it.key, r);
                }
            }
        }

        int size() {
            return rows.size();
        }

        /** Forget videos that are gone (only after a complete listing). */
        synchronized void prune(Set<String> live) {
            if (rows.keySet().retainAll(live)) dirty = true;
        }

        /** Atomic write (temp file + rename) of the library rows. */
        synchronized void save(Context ctx) {
            if (!dirty) return;
            File f = file(ctx);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, "hashes.tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String[]> e : rows.entrySet()) {
                    if (!e.getKey().startsWith("ms:")) continue;
                    String[] r = e.getValue();
                    w.write(e.getKey() + "\t" + r[0] + "\t" + r[1] + "\t" + r[2] + "\t" + r[3] + "\n");
                }
            } catch (IOException ex) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return;
            }
            if (tmp.renameTo(f)) dirty = false;
        }

        @VisibleForTesting
        static void clearPrivateMemory() {
            synchronized (PRIVATE_MEMORY) {
                PRIVATE_MEMORY.clear();
            }
        }
    }
}
