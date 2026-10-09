package com.jamh.videoplayer;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.9.0 duplicate finder: the pure part (no Android I/O), so it can be tested with fake hashes.
 *
 * <p><b>Exact duplicates</b> (byte-identical files), cheapest test first:
 * <ol>
 *   <li>group by file size (free, from MediaStore / the Private index): only sizes seen twice go on;</li>
 *   <li>partial hash: SHA-256 of size + first and last 64 KiB ({@link VideoKey}, the same key Private
 *       already stores), at most 128 KiB read per file;</li>
 *   <li>full SHA-256 only for files whose partial hashes collide, to confirm.</li>
 * </ol>
 * <p><b>Near duplicates</b> (re-encodes, other resolutions, re-downloads): same duration ±1 s (a sorted
 * sliding window, so no all-pairs comparison), then a 64-bit dHash of the keyframe nearest 10 %, 50 %
 * and 90 % of the video. Hashes are only computed for videos that have a duration neighbour.
 * Two videos look alike when at least two frames are informative (not flat/black) in both and every
 * such frame differs by at most {@link #NEAR_MAX_BITS} bits.
 *
 * <p>Groups are connected components (union-find) of both relations. A group is "exact" when every
 * member has the same full hash. The suggested keeper is the highest resolution, then the largest
 * file, then the oldest; nothing is ever deleted without the user ticking it.
 */
final class DuplicateFinder {

    /** Duration tolerance for near duplicates. */
    static final long DURATION_TOLERANCE_MS = 1000;
    /** Max differing bits per frame (of 64) for frames to count as the same picture. */
    static final int NEAR_MAX_BITS = 10;
    /** Frame positions for the perceptual hash. */
    static final double[] FRAME_POSITIONS = {0.10, 0.50, 0.90};
    /** Videos shorter than this have no reliable keyframes: exact matching only. */
    static final long MIN_NEAR_DURATION_MS = 2000;

    /** One video, from the library or from Private. */
    static final class Item {
        final String key;              // "ms:<id>" or "pv:<id>": also the cache key
        @Nullable final VideoItem video;
        @Nullable final Vault.Entry vault;
        final String name;
        final long size;
        final long durationMs;
        final int width, height;
        final long dateMs;
        final String where;

        Item(VideoItem v) {
            key = "ms:" + v.id;
            video = v;
            vault = null;
            name = v.title;
            size = v.sizeBytes;
            durationMs = v.durationMs;
            width = v.width;
            height = v.height;
            dateMs = v.dateAddedSec * 1000L;
            where = v.bucketName;
        }

        Item(Vault.Entry e) {
            key = "pv:" + e.id;
            video = null;
            vault = e;
            int dot = e.name == null ? -1 : e.name.lastIndexOf('.');
            name = e.name == null ? "Video" : dot > 0 ? e.name.substring(0, dot) : e.name;
            size = e.size;
            durationMs = e.durationMs;
            width = e.width;
            height = e.height;
            dateMs = e.addedMs;
            where = "Private";
        }

        boolean isPrivate() {
            return vault != null;
        }

        long pixels() {
            return (long) Math.max(0, width) * Math.max(0, height);
        }

        /** Cache validity stamp: a changed file gets new hashes. */
        String stamp() {
            return size + "|" + dateMs + "|" + durationMs + "|" + name.hashCode();
        }

        @Override
        public String toString() {
            return key;
        }
    }

    /** Perceptual hash of the three frames; {@code informative[i]} false for flat frames. */
    static final class FrameHash {
        final long[] hash;
        final boolean[] informative;

        FrameHash(long[] hash, boolean[] informative) {
            this.hash = hash;
            this.informative = informative;
        }

        /** Serialised as "h,h,h" with "-" for flat/unreadable frames. */
        String encode() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hash.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(informative[i] ? Long.toHexString(hash[i]) : "-");
            }
            return sb.toString();
        }

        @Nullable
        static FrameHash decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split(",", -1);
            long[] h = new long[p.length];
            boolean[] ok = new boolean[p.length];
            try {
                for (int i = 0; i < p.length; i++) {
                    ok[i] = !"-".equals(p[i]);
                    h[i] = ok[i] ? Long.parseUnsignedLong(p[i], 16) : 0;
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return new FrameHash(h, ok);
        }
    }

    /** Where hashes come from (real: {@link DuplicateScan}; tests: fakes). Called on a worker thread. */
    interface Hasher {
        /** Size + head/tail key; "" if unreadable. */
        String partial(Item it) throws IOException;

        /** Whole-file SHA-256; "" if unreadable. */
        String full(Item it, Cancel cancel) throws IOException;

        /** Frame hashes, or null if no frames could be decoded. */
        @Nullable
        FrameHash frames(Item it) throws IOException;
    }

    interface Cancel {
        boolean isCancelled();
    }

    /** Per-item hash cache (see {@link DuplicateScan.HashCache}). */
    interface Cache {
        @Nullable
        String get(Item it, String field);

        void put(Item it, String field, String value);
    }

    static final String F_PARTIAL = "p", F_FULL = "f", F_FRAMES = "d";

    interface Progress {
        /** @param phase short label, e.g. "Comparing file contents" */
        void onProgress(String phase, int done, int total);
    }

    static final class CancelledException extends Exception {
    }

    /** A set of videos that are the same. */
    static final class Group {
        final boolean exact;
        final List<Item> items;         // keeper first
        final Item keep;

        Group(boolean exact, List<Item> items) {
            this.exact = exact;
            List<Item> sorted = new ArrayList<>(items);
            Collections.sort(sorted, KEEP_ORDER);
            this.items = Collections.unmodifiableList(sorted);
            this.keep = sorted.get(0);
        }

        /** Bytes freed by removing every item except the suggested keeper. */
        long reclaimableBytes() {
            long b = 0;
            for (Item it : items) if (it != keep) b += Math.max(0, it.size);
            return b;
        }
    }

    /** Keep the highest resolution, then the largest file, then the oldest, then the library copy. */
    static final Comparator<Item> KEEP_ORDER = (a, b) -> {
        int c = Long.compare(b.pixels(), a.pixels());
        if (c != 0) return c;
        c = Long.compare(b.size, a.size);
        if (c != 0) return c;
        long da = a.dateMs > 0 ? a.dateMs : Long.MAX_VALUE, db = b.dateMs > 0 ? b.dateMs : Long.MAX_VALUE;
        c = Long.compare(da, db);
        if (c != 0) return c;
        c = Boolean.compare(a.isPrivate(), b.isPrivate());
        if (c != 0) return c;
        return a.key.compareTo(b.key);
    };

    /** Statistics of one run (also used by tests to prove the cheap-first ordering). */
    static final class Stats {
        int partialHashed, fullHashed, framesHashed, cacheHits, nearPairsCompared;
    }

    private final Hasher hasher;
    private final Cache cache;
    private final Cancel cancel;
    private final Progress progress;
    final Stats stats = new Stats();

    DuplicateFinder(Hasher hasher, Cache cache, Cancel cancel, Progress progress) {
        this.hasher = hasher;
        this.cache = cache;
        this.cancel = cancel;
        this.progress = progress;
    }

    /** Runs both passes; biggest reclaimable groups first. */
    List<Group> find(List<Item> all, boolean near) throws CancelledException {
        List<Item> items = new ArrayList<>();
        for (Item it : all) if (it != null && it.size > 0) items.add(it);
        UnionFind uf = new UnionFind(items.size());
        Map<Item, Integer> index = new HashMap<>();
        for (int i = 0; i < items.size(); i++) index.put(items.get(i), i);
        Map<Item, String> fullOf = exactPass(items, uf, index);
        if (near) nearPass(items, uf, index, fullOf);

        Map<Integer, List<Item>> comps = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            int r = uf.find(i);
            List<Item> l = comps.get(r);
            if (l == null) comps.put(r, l = new ArrayList<>());
            l.add(items.get(i));
        }
        List<Group> groups = new ArrayList<>();
        for (List<Item> l : comps.values()) {
            if (l.size() < 2) continue;
            String f0 = fullOf.get(l.get(0));
            boolean exact = f0 != null;
            for (Item it : l) if (f0 == null || !f0.equals(fullOf.get(it))) exact = false;
            groups.add(new Group(exact, l));
        }
        Collections.sort(groups, (a, b) -> {
            int c = Long.compare(b.reclaimableBytes(), a.reclaimableBytes());
            return c != 0 ? c : a.keep.key.compareTo(b.keep.key);
        });
        return groups;
    }

    // ------------------------------------------------------------------ exact

    private Map<Item, String> exactPass(List<Item> items, UnionFind uf, Map<Item, Integer> index)
            throws CancelledException {
        Map<Long, List<Item>> bySize = new HashMap<>();
        for (Item it : items) {
            List<Item> l = bySize.get(it.size);
            if (l == null) bySize.put(it.size, l = new ArrayList<>());
            l.add(it);
        }
        List<Item> sameSize = new ArrayList<>();
        for (List<Item> l : bySize.values()) if (l.size() > 1) sameSize.addAll(l);

        Map<String, List<Item>> byPartial = new HashMap<>();
        int n = 0;
        for (Item it : sameSize) {
            checkCancel();
            progress.onProgress("Checking same-size videos", n++, sameSize.size());
            String p = cached(it, F_PARTIAL);
            if (p == null) {
                try {
                    p = hasher.partial(it);
                } catch (IOException e) {
                    p = "";
                }
                stats.partialHashed++;
                cache.put(it, F_PARTIAL, p);
            }
            if (p.isEmpty()) continue;
            List<Item> l = byPartial.get(p);
            if (l == null) byPartial.put(p, l = new ArrayList<>());
            l.add(it);
        }
        List<Item> partialMatches = new ArrayList<>();
        for (List<Item> l : byPartial.values()) if (l.size() > 1) partialMatches.addAll(l);

        Map<Item, String> fullOf = new HashMap<>();
        Map<String, Item> firstWithFull = new HashMap<>();
        n = 0;
        for (Item it : partialMatches) {
            checkCancel();
            progress.onProgress("Confirming identical files", n++, partialMatches.size());
            String f = cached(it, F_FULL);
            if (f == null) {
                try {
                    f = hasher.full(it, cancel);
                } catch (IOException e) {
                    f = "";
                }
                checkCancel();   // a cancelled read returns early: don't cache it
                stats.fullHashed++;
                cache.put(it, F_FULL, f);
            }
            if (f.isEmpty()) continue;
            fullOf.put(it, f);
            Item first = firstWithFull.get(f);
            if (first == null) firstWithFull.put(f, it);
            else uf.union(index.get(first), index.get(it));
        }
        return fullOf;
    }

    // ------------------------------------------------------------------ near

    private void nearPass(List<Item> items, UnionFind uf, Map<Item, Integer> index, Map<Item, String> fullOf)
            throws CancelledException {
        List<Item> timed = new ArrayList<>();
        for (Item it : items) if (it.durationMs >= MIN_NEAR_DURATION_MS) timed.add(it);
        Collections.sort(timed, (a, b) -> Long.compare(a.durationMs, b.durationMs));
        // Which videos have a duration neighbour (and aren't already all exact copies of it)?
        boolean[] needed = new boolean[timed.size()];
        for (int i = 0; i < timed.size(); i++) {
            for (int j = i + 1; j < timed.size()
                    && timed.get(j).durationMs - timed.get(i).durationMs <= DURATION_TOLERANCE_MS; j++) {
                if (sameExact(timed.get(i), timed.get(j), fullOf)) continue;
                needed[i] = needed[j] = true;
            }
        }
        int total = 0;
        for (boolean b : needed) if (b) total++;
        Map<Item, FrameHash> frames = new HashMap<>();
        int n = 0;
        for (int i = 0; i < timed.size(); i++) {
            if (!needed[i]) continue;
            checkCancel();
            Item it = timed.get(i);
            progress.onProgress("Comparing pictures", n++, total);
            String raw = cached(it, F_FRAMES);
            FrameHash fh = FrameHash.decode(raw);
            if (fh == null && !"x".equals(raw)) {
                try {
                    fh = hasher.frames(it);
                } catch (IOException e) {
                    fh = null;
                }
                stats.framesHashed++;
                cache.put(it, F_FRAMES, fh == null ? "x" : fh.encode());
            }
            if (fh != null) frames.put(it, fh);
        }
        for (int i = 0; i < timed.size(); i++) {
            FrameHash a = frames.get(timed.get(i));
            if (a == null) continue;
            for (int j = i + 1; j < timed.size()
                    && timed.get(j).durationMs - timed.get(i).durationMs <= DURATION_TOLERANCE_MS; j++) {
                FrameHash b = frames.get(timed.get(j));
                if (b == null) continue;
                stats.nearPairsCompared++;
                if (looksAlike(a, b)) uf.union(index.get(timed.get(i)), index.get(timed.get(j)));
            }
        }
    }

    private static boolean sameExact(Item a, Item b, Map<Item, String> fullOf) {
        String fa = fullOf.get(a);
        return fa != null && fa.equals(fullOf.get(b));
    }

    /** At least two frames informative in both, and all of those within {@link #NEAR_MAX_BITS}. */
    static boolean looksAlike(FrameHash a, FrameHash b) {
        int usable = 0;
        int n = Math.min(a.hash.length, b.hash.length);
        for (int i = 0; i < n; i++) {
            if (!a.informative[i] || !b.informative[i]) continue;
            if (Long.bitCount(a.hash[i] ^ b.hash[i]) > NEAR_MAX_BITS) return false;
            usable++;
        }
        return usable >= 2;
    }

    @Nullable
    private String cached(Item it, String field) {
        String v = cache.get(it, field);
        if (v == null) return null;
        stats.cacheHits++;
        // "-": known unreadable (partial / full); "x": no frames could be decoded.
        if ("-".equals(v)) return F_FRAMES.equals(field) ? "x" : "";
        return v;
    }

    private void checkCancel() throws CancelledException {
        if (cancel.isCancelled()) throw new CancelledException();
    }

    // ------------------------------------------------------------------ dHash

    /** Flat frame: luminance range below this (0-255) carries no picture information. */
    static final int FLAT_RANGE = 12;

    /**
     * Pure dHash of a 9×8 luminance grid (row-major, 72 values): bit set when a pixel is brighter
     * than its right neighbour. Returns {hash, informative ? 1 : 0}.
     */
    static long[] dhash(int[] luma9x8) {
        if (luma9x8.length != 72) throw new IllegalArgumentException("need 9x8");
        long h = 0;
        int min = 255, max = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int l = luma9x8[y * 9 + x], r = luma9x8[y * 9 + x + 1];
                h = (h << 1) | (l > r ? 1 : 0);
            }
            for (int x = 0; x < 9; x++) {
                min = Math.min(min, luma9x8[y * 9 + x]);
                max = Math.max(max, luma9x8[y * 9 + x]);
            }
        }
        return new long[]{h, max - min >= FLAT_RANGE ? 1 : 0};
    }

    /** dHash of a decoded frame (scaled to 9×8 with filtering, BT.601 luma). */
    static long[] dhash(Bitmap frame) {
        Bitmap small = Bitmap.createScaledBitmap(frame, 9, 8, true);
        int[] px = new int[72];
        small.getPixels(px, 0, 9, 0, 0, 9, 8);
        if (small != frame) small.recycle();
        int[] luma = new int[72];
        for (int i = 0; i < 72; i++) {
            int c = px[i];
            luma[i] = (299 * ((c >> 16) & 0xFF) + 587 * ((c >> 8) & 0xFF) + 114 * (c & 0xFF)) / 1000;
        }
        return dhash(luma);
    }

    // ------------------------------------------------------------------ helpers

    @VisibleForTesting
    static final class UnionFind {
        private final int[] parent;

        UnionFind(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        void union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    /** Why the keeper was suggested, e.g. "Suggested: keep the highest resolution (1920×1080)". */
    static String keepReason(Group g) {
        Item k = g.keep;
        boolean higherRes = true, larger = true, older = true;
        for (Item it : g.items) {
            if (it == k) continue;
            if (it.pixels() >= k.pixels()) higherRes = false;
            if (it.size >= k.size) larger = false;
            if (k.dateMs <= 0 || (it.dateMs > 0 && it.dateMs <= k.dateMs)) older = false;
        }
        if (higherRes && k.width > 0) return "Suggested: keep the highest resolution (" + k.width + "\u00d7" + k.height + ")";
        if (larger) return "Suggested: keep the largest file (" + Fmt.size(k.size) + ")";
        if (older) return "Suggested: keep the oldest copy";
        return "Suggested: keep one copy";
    }

    /** "Exact copies · 3 videos · 1.2 GB to free" */
    static String groupTitle(Group g) {
        String kind = g.exact ? "Exact copies" : "Look alike";
        String free = Fmt.size(g.reclaimableBytes());
        return kind + " · " + g.items.size() + " videos" + (free.isEmpty() ? "" : " · " + free + " to free");
    }

    /** "1920×1080 · 1.2 GB · 3 Mar 2024 · Camera" */
    static String describe(Item it) {
        StringBuilder sb = new StringBuilder();
        if (it.width > 0 && it.height > 0) sb.append(it.width).append('\u00d7').append(it.height);
        String size = Fmt.size(it.size);
        if (!size.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(size);
        if (it.dateMs > 0) sb.append(" · ").append(Fmt.date(it.dateMs / 1000));
        sb.append(" · ").append(it.where);
        return sb.toString();
    }
}
