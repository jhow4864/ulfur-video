package com.jamh.videoplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.LruCache;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Seek-bar / swipe thumbnails for local videos.
 *
 * <p>Once playback is underway a background-priority thread extracts one small keyframe every
 * {@link #intervalFor N} seconds (coarse-to-fine, so the whole timeline is covered early) with
 * {@code MediaMetadataRetriever} CLOSEST_SYNC, JPEG-compresses it and appends it to a per-video
 * pack file in {@code cacheDir/scrub}. Lookups are memory-only (nearest cached frame, decoded
 * through a small LRU), so scrubbing never waits for I/O. Gaps are filled on demand once the
 * finger rests; the sweep pauses while scrubbing so it doesn't compete with the player's live
 * CLOSEST_SYNC preview. {@link #release()} cancels everything.
 */
final class ScrubThumbnails {

    interface Listener {
        /** Main thread: frame {@code index} became available. */
        void onFrameAvailable(int index);
    }

    static final int FRAME_BOX_PX = 240;          // extracted frames fit in 240x240
    static final int MAX_FRAMES = 600;
    static final long MIN_INTERVAL_MS = 2_000;
    static final long DEMAND_DEBOUNCE_MS = 150;  // fill a gap only when the finger rests
    static final long START_DELAY_MS = 2_000;    // let playback start first
    static final long DISK_BUDGET_BYTES = 64L * 1024 * 1024;
    private static final int MAGIC = 0x56505354; // "VPST"
    private static final int VERSION = 1;
    private static final int MAX_FAILURES_WITHOUT_SUCCESS = 6;

    private final Context app;
    private final Uri uri;
    @Nullable private final File packFile;
    final long intervalMs;
    final int count;
    private final byte[][] jpegs;
    private final boolean[] failed;
    private final int[] order;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> decoded = new LruCache<Integer, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    };
    @Nullable private Listener listener;

    private final Object lock = new Object();
    private boolean cancelled;
    /** 1.4.2: the background sweep stays off until this uptime (set after seeks and while the player buffers). */
    private long holdUntil;
    private boolean scrubbing;
    private boolean unavailable;
    private int demandIndex = -1;
    private long demandAt;
    private int sweepPos;
    private Thread worker;

    /**
     * @param cacheKey stable id of the video (resume key); null disables the disk cache (private videos).
     */
    ScrubThumbnails(Context ctx, Uri uri, @Nullable String cacheKey, long durationMs) {
        this.app = ctx.getApplicationContext();
        this.uri = uri;
        this.intervalMs = intervalFor(durationMs);
        this.count = frameCount(durationMs, intervalMs);
        this.jpegs = new byte[count][];
        this.failed = new boolean[count];
        this.order = sweepOrder(count);
        this.packFile = cacheKey == null ? null
                : new File(new File(app.getCacheDir(), "scrub"), hash(cacheKey + "|" + durationMs) + ".pack");
    }

    // ------------------------------------------------------------------ pure helpers (tested)

    static long intervalFor(long durationMs) {
        if (durationMs <= 0) return MIN_INTERVAL_MS;
        long raw = (durationMs + MAX_FRAMES - 1) / MAX_FRAMES;
        long secs = (raw + 999) / 1000;           // whole seconds
        return Math.max(MIN_INTERVAL_MS, secs * 1000);
    }

    static int frameCount(long durationMs, long intervalMs) {
        if (durationMs <= 0) return 1;
        return (int) Math.min(MAX_FRAMES + 1, durationMs / intervalMs + 1);
    }

    static int indexFor(long positionMs, long intervalMs, int count) {
        long i = Math.round(positionMs / (double) intervalMs);
        return (int) Math.max(0, Math.min(count - 1, i));
    }

    /** Coarse-to-fine order: 0, n/2, n/4, 3n/4... so early frames already span the timeline. */
    static int[] sweepOrder(int count) {
        int[] out = new int[count];
        boolean[] seen = new boolean[count];
        int k = 0;
        int step = Integer.highestOneBit(Math.max(1, count));
        for (; step >= 1; step >>= 1) {
            for (int i = 0; i < count; i += step) {
                if (!seen[i]) {
                    seen[i] = true;
                    out[k++] = i;
                }
            }
        }
        return out;
    }

    /** Nearest index that has a frame, preferring the exact one; -1 if none. */
    static int nearestAvailable(byte[][] frames, int index) {
        if (index >= 0 && index < frames.length && frames[index] != null) return index;
        for (int d = 1; d < frames.length; d++) {
            int lo = index - d, hi = index + d;
            if (lo >= 0 && lo < frames.length && frames[lo] != null) return lo;
            if (hi >= 0 && hi < frames.length && frames[hi] != null) return hi;
            if (lo < 0 && hi >= frames.length) break;
        }
        return -1;
    }

    // ------------------------------------------------------------------ pack file

    @VisibleForTesting
    static void writeHeader(DataOutputStream out, long intervalMs, int count) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(intervalMs);
        out.writeInt(count);
        out.writeInt(FRAME_BOX_PX);
    }

    @VisibleForTesting
    static void writeRecord(DataOutputStream out, int index, byte[] jpeg) throws IOException {
        out.writeInt(index);
        out.writeInt(jpeg.length);
        out.write(jpeg);
    }

    /**
     * Reads the frames of a pack written with the same layout. A truncated tail (process killed
     * mid-append) is ignored. Returns false when the file is missing or incompatible.
     */
    @VisibleForTesting
    static boolean readPack(File f, long intervalMs, byte[][] into) {
        if (f == null || !f.isFile()) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readLong() != intervalMs
                    || in.readInt() != into.length || in.readInt() != FRAME_BOX_PX) {
                return false;
            }
            while (true) {
                int idx, len;
                try {
                    idx = in.readInt();
                    len = in.readInt();
                } catch (EOFException eof) {
                    break;
                }
                if (idx < 0 || idx >= into.length || len <= 0 || len > 512 * 1024) break;
                byte[] b = new byte[len];
                try {
                    in.readFully(b);
                } catch (EOFException eof) {
                    break;
                }
                into[idx] = b;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @MainThread
    void start(Listener l) {
        listener = l;
        synchronized (lock) {
            if (worker != null || cancelled) return;
            worker = new Thread(this::run, "scrub-thumbs");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** Stops extraction; safe to call more than once. */
    @MainThread
    void release() {
        listener = null;
        synchronized (lock) {
            cancelled = true;
            lock.notifyAll();
        }
        main.removeCallbacksAndMessages(null);
        decoded.evictAll();
    }

    /** While true the background sweep pauses (on-demand gap filling continues). */
    /**
     * 1.4.2: keeps the background sweep (MediaMetadataRetriever: its own decoder + disk reads) out of the way for
     * {@code ms} so a seek gets the decoder and storage to itself. On-demand frames while scrubbing still work.
     */
    void holdOff(long ms) {
        synchronized (lock) {
            holdUntil = Math.max(holdUntil, SystemClock.uptimeMillis() + ms);
            lock.notifyAll();
        }
    }

    void setScrubbing(boolean on) {
        synchronized (lock) {
            scrubbing = on;
            lock.notifyAll();
        }
    }

    boolean isUnavailable() {
        synchronized (lock) {
            return unavailable;
        }
    }

    int indexFor(long positionMs) {
        return indexFor(positionMs, intervalMs, count);
    }

    /** Result of {@link #lookup}: the frame shown and whether it is the exact one for the position. */
    static final class Frame {
        final Bitmap bitmap;
        final int index;
        final boolean exact;

        Frame(Bitmap bitmap, int index, boolean exact) {
            this.bitmap = bitmap;
            this.index = index;
            this.exact = exact;
        }
    }

    /** Main thread, memory only: nearest cached frame (null if none yet); requests the exact one. */
    @Nullable
    @MainThread
    Frame lookup(long positionMs) {
        int want = indexFor(positionMs);
        int have;
        byte[] bytes;
        synchronized (lock) {
            have = nearestAvailable(jpegs, want);
            bytes = have >= 0 ? jpegs[have] : null;
            if (have != want && !unavailable && !failed[want]) {
                demandIndex = want;
                demandAt = SystemClock.uptimeMillis();
                lock.notifyAll();
            }
        }
        if (bytes == null) return null;
        Bitmap bm = decoded.get(have);
        if (bm == null) {
            bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length); // ~5 KB JPEG, <1 ms
            if (bm == null) return null;
            decoded.put(have, bm);
        }
        return new Frame(bm, have, have == want);
    }

    @VisibleForTesting
    int cachedFrameCount() {
        synchronized (lock) {
            int n = 0;
            for (byte[] b : jpegs) if (b != null) n++;
            return n;
        }
    }

    @VisibleForTesting
    @Nullable File packFile() {
        return packFile;
    }

    // ------------------------------------------------------------------ worker

    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        long startAt = SystemClock.uptimeMillis() + START_DELAY_MS;
        boolean loaded = loadFromDisk();
        if (loaded) postAvailable(-1);
        MediaMetadataRetriever mmr = null;
        java.io.Closeable mmrSource = null;
        DataOutputStream pack = null;
        int failures = 0, successes = 0;
        try {
            while (true) {
                int idx;
                synchronized (lock) {
                    while (true) {
                        if (cancelled) return;
                        long now = SystemClock.uptimeMillis();
                        if (demandIndex >= 0 && (jpegs[demandIndex] != null || failed[demandIndex])) demandIndex = -1;
                        if (demandIndex >= 0) {
                            long wait = demandAt + DEMAND_DEBOUNCE_MS - now;
                            if (wait <= 0) {
                                idx = demandIndex;
                                demandIndex = -1;
                                break;
                            }
                            waitQuietly(wait);
                            continue;
                        }
                        if (now < startAt) {
                            waitQuietly(startAt - now);
                            continue;
                        }
                        if (!scrubbing && now < holdUntil) {
                            waitQuietly(holdUntil - now);
                            continue;
                        }
                        if (!scrubbing) {
                            while (sweepPos < order.length
                                    && (jpegs[order[sweepPos]] != null || failed[order[sweepPos]])) sweepPos++;
                            if (sweepPos < order.length) {
                                idx = order[sweepPos++];
                                break;
                            }
                        }
                        waitQuietly(0); // done or paused: sleep until a demand / resume / cancel
                    }
                }
                if (mmr == null) {
                    mmr = new MediaMetadataRetriever();
                    try {
                        mmrSource = VaultMediaDataSource.setDataSource(app, mmr, uri);
                    } catch (RuntimeException | java.io.IOException e) {
                        markUnavailable();
                        return;
                    }
                }
                byte[] jpeg = extract(mmr, idx * intervalMs * 1000L);
                if (jpeg == null) {
                    if (++failures >= MAX_FAILURES_WITHOUT_SUCCESS && successes == 0) {
                        markUnavailable(); // e.g. a container the retriever can't read
                        return;
                    }
                    synchronized (lock) {
                        failed[idx] = true; // don't ask for this one again
                    }
                    continue;
                }
                successes++;
                synchronized (lock) {
                    if (cancelled) return;
                    jpegs[idx] = jpeg;
                }
                if (packFile != null) {
                    try {
                        if (pack == null) pack = openPackForAppend(loaded);
                        writeRecord(pack, idx, jpeg);
                        pack.flush();
                    } catch (IOException ignored) {
                        // memory cache still works
                    }
                }
                postAvailable(idx);
                if (!isDemandPending()) SystemClock.sleep(15); // stay light on the CPU
            }
        } finally {
            if (mmr != null) {
                try {
                    mmr.release();
                } catch (Exception ignored) {
                }
            }
            VaultMediaDataSource.closeQuietly(mmrSource);
            if (pack != null) {
                try {
                    pack.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean isDemandPending() {
        synchronized (lock) {
            return demandIndex >= 0;
        }
    }

    private void waitQuietly(long ms) {
        try {
            lock.wait(ms);
        } catch (InterruptedException e) {
            cancelled = true;
        }
    }

    private void markUnavailable() {
        synchronized (lock) {
            unavailable = true;
        }
    }

    private boolean loadFromDisk() {
        if (packFile == null) return false;
        File dir = packFile.getParentFile();
        if (dir != null && !dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        pruneDisk(dir, packFile);
        byte[][] tmp = new byte[count][];
        boolean ok = readPack(packFile, intervalMs, tmp);
        if (ok) {
            //noinspection ResultOfMethodCallIgnored
            packFile.setLastModified(System.currentTimeMillis());
            synchronized (lock) {
                for (int i = 0; i < count; i++) if (jpegs[i] == null) jpegs[i] = tmp[i];
            }
        }
        return ok;
    }

    private DataOutputStream openPackForAppend(boolean validExisting) throws IOException {
        boolean append = validExisting && packFile.isFile();
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(packFile, append)));
        if (!append) writeHeader(out, intervalMs, count);
        return out;
    }

    /** Keeps the thumbnail cache under {@link #DISK_BUDGET_BYTES}, oldest first. */
    static void pruneDisk(@Nullable File dir, @Nullable File keep) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_BUDGET_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_BUDGET_BYTES) break;
            if (f.equals(keep)) continue;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Nullable
    private static byte[] extract(MediaMetadataRetriever mmr, long timeUs) {
        Bitmap bm = null;
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                bm = mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        FRAME_BOX_PX, FRAME_BOX_PX);
            } else {
                Bitmap full = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (full != null) {
                    float s = Math.min(1f, FRAME_BOX_PX / (float) Math.max(full.getWidth(), full.getHeight()));
                    bm = Bitmap.createScaledBitmap(full, Math.max(1, Math.round(full.getWidth() * s)),
                            Math.max(1, Math.round(full.getHeight() * s)), true);
                    if (bm != full) full.recycle();
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (bm == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8 * 1024);
        bm.compress(Bitmap.CompressFormat.JPEG, 72, bos);
        bm.recycle();
        return bos.toByteArray();
    }

    private void postAvailable(int index) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onFrameAvailable(index);
        });
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
