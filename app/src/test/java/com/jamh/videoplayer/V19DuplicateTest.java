package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.save;
import static com.jamh.videoplayer.TestUtil.await;
import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.util.LruCache;
import android.view.Menu;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 1.9.0: duplicate finder (exact + look-alike), cache, background scan and Trash-with-undo. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class V19DuplicateTest {

    private final Application app = ApplicationProvider.getApplicationContext();
    private DeleteVideosFlow.RequestMaker real;

    @Before
    public void setUp() {
        real = DeleteVideosFlow.requestMaker;
        V151FeatureTest.stubRequests(app);
        for (Vault.Entry e : Vault.readIndex(app)) Vault.delete(app, e);
        DuplicateScan.HashCache.file(app).delete();
        DuplicateScan.HashCache.clearPrivateMemory();
        DuplicateScan.get().setLast(null);
    }

    @After
    public void tearDown() {
        DeleteVideosFlow.requestMaker = real;
        MediaAccess.overrideForTest = null;
        DuplicateScan.sourceOverride = null;
        DuplicateScan.hasherOverride = null;
        DuplicateScan.executorOverride = null;
        DuplicateScan.get().setLast(null);
        VaultLock.lock();
        for (Vault.Entry e : Vault.readIndex(app)) Vault.delete(app, e);
    }

    // ------------------------------------------------------------------ fakes

    private static VideoItem vid(long id, String name, long size, long durMs, int w, int h, long dateSec, String bucket) {
        return new VideoItem(id, Uri.parse("content://media/external/video/media/" + id), name + ".mp4", durMs, size,
                dateSec, "b" + bucket, bucket, w, h);
    }

    /** Hashes from tables; counts calls so the cheap-first order can be checked. */
    static class FakeHasher implements DuplicateFinder.Hasher {
        final Map<String, String> partial = new HashMap<>(), full = new HashMap<>();
        final Map<String, DuplicateFinder.FrameHash> frames = new HashMap<>();
        final List<String> partialCalls = new ArrayList<>(), fullCalls = new ArrayList<>(), frameCalls = new ArrayList<>();

        @Override public String partial(DuplicateFinder.Item it) {
            partialCalls.add(it.key);
            String p = partial.get(it.key);
            return p == null ? "p-" + it.key : p;
        }

        @Override public String full(DuplicateFinder.Item it, DuplicateFinder.Cancel c) throws IOException {
            fullCalls.add(it.key);
            String f = full.get(it.key);
            if ("io".equals(f)) throw new IOException("unreadable");
            return f == null ? "f-" + it.key : f;
        }

        @Override public DuplicateFinder.FrameHash frames(DuplicateFinder.Item it) {
            frameCalls.add(it.key);
            return frames.get(it.key);
        }
    }

    static final class MapCache implements DuplicateFinder.Cache {
        final Map<String, String> m = new HashMap<>();

        @Override public String get(DuplicateFinder.Item it, String f) {
            return m.get(it.key + "|" + it.stamp() + "|" + f);
        }

        @Override public void put(DuplicateFinder.Item it, String f, String v) {
            m.put(it.key + "|" + it.stamp() + "|" + f, v == null || v.isEmpty() ? "-" : v);
        }
    }

    private static DuplicateFinder finder(DuplicateFinder.Hasher h, DuplicateFinder.Cache c) {
        return new DuplicateFinder(h, c, () -> false, (p, d, t) -> { });
    }

    private static DuplicateFinder.FrameHash fh(long a, long b, long c) {
        return new DuplicateFinder.FrameHash(new long[]{a, b, c}, new boolean[]{true, true, true});
    }

    private static List<DuplicateFinder.Item> items(VideoItem... v) {
        List<DuplicateFinder.Item> l = new ArrayList<>();
        for (VideoItem x : v) l.add(new DuplicateFinder.Item(x));
        return l;
    }

    // ------------------------------------------------------------------ exact

    @Test
    public void exactPassHashesCheapestFirstAndConfirmsWithFullHash() throws Exception {
        List<DuplicateFinder.Item> l = items(
                vid(1, "A", 5_000_000, 0, 1920, 1080, 100, "Camera"),
                vid(2, "A copy", 5_000_000, 0, 1920, 1080, 50, "Download"),     // identical to 1
                vid(3, "B", 5_000_000, 0, 1280, 720, 70, "Camera"),             // same size, other content
                vid(4, "C", 7_000_000, 0, 1280, 720, 70, "Camera"),             // unique size: never read
                vid(5, "D", 9_000_000, 0, 640, 360, 70, "Camera"),              // same size as 6 ...
                vid(6, "D2", 9_000_000, 0, 640, 360, 80, "Camera"));            // ... same head/tail, different middle
        FakeHasher h = new FakeHasher();
        h.partial.put("ms:1", "P1");
        h.partial.put("ms:2", "P1");
        h.partial.put("ms:5", "P5");
        h.partial.put("ms:6", "P5");
        h.full.put("ms:1", "F1");
        h.full.put("ms:2", "F1");
        h.full.put("ms:5", "F5");
        h.full.put("ms:6", "F6");
        DuplicateFinder f = finder(h, new MapCache());
        List<DuplicateFinder.Group> g = f.find(l, false);
        assertEquals(1, g.size());
        assertTrue(g.get(0).exact);
        assertEquals(2, g.get(0).items.size());
        // Oldest of two identical copies is kept.
        assertEquals("ms:2", g.get(0).keep.key);
        assertEquals(5_000_000, g.get(0).reclaimableBytes());
        // Unique size 4 never read; partial only for same-size; full only for partial collisions.
        assertFalse(h.partialCalls.contains("ms:4"));
        assertEquals(new HashSet<>(Arrays.asList("ms:1", "ms:2", "ms:3", "ms:5", "ms:6")), new HashSet<>(h.partialCalls));
        assertEquals(new HashSet<>(Arrays.asList("ms:1", "ms:2", "ms:5", "ms:6")), new HashSet<>(h.fullCalls));
        assertEquals(0, h.frameCalls.size());
        assertEquals("Exact copies · 2 videos · 4.8 MB to free", DuplicateFinder.groupTitle(g.get(0)));
    }

    @Test
    public void unreadableFilesNeverMatchEachOther() throws Exception {
        List<DuplicateFinder.Item> l = items(vid(1, "A", 1000, 0, 0, 0, 1, "x"), vid(2, "B", 1000, 0, 0, 0, 1, "x"));
        FakeHasher h = new FakeHasher();
        h.partial.put("ms:1", "P");
        h.partial.put("ms:2", "P");
        h.full.put("ms:1", "io");
        h.full.put("ms:2", "io");
        MapCache c = new MapCache();
        assertTrue(finder(h, c).find(l, true).isEmpty());
        // ...also when the "unreadable" marker comes back from the cache.
        assertTrue(finder(h, c).find(l, true).isEmpty());
        assertEquals(2, h.fullCalls.size());
    }

    // ------------------------------------------------------------------ near

    @Test
    public void nearDuplicatesNeedSameDurationAndSamePictures() throws Exception {
        List<DuplicateFinder.Item> l = items(
                vid(1, "Drone 4K", 900_000_000, 120_000, 3840, 2160, 100, "DJI"),
                vid(2, "Drone 720p", 60_000_000, 120_700, 1280, 720, 200, "WhatsApp"),   // +0.7 s, same pictures
                vid(3, "Other clip", 61_000_000, 120_400, 1280, 720, 300, "Camera"),     // same length, other pictures
                vid(4, "Long film", 2_000_000_000, 5_400_000, 1920, 1080, 10, "Movies"), // nobody within 1 s
                vid(5, "Drone 1080p", 200_000_000, 121_800, 1920, 1080, 150, "DJI"),      // +1.8 s from 1: too far
                vid(6, "Black intro A", 10_000_000, 30_000, 1280, 720, 1, "Camera"),
                vid(7, "Black intro B", 11_000_000, 30_200, 1280, 720, 2, "Camera"));
        FakeHasher h = new FakeHasher();
        h.frames.put("ms:1", fh(0x0F0F0F0F0F0F0F0FL, 0x123456789ABCDEF0L, 0x7777000077770000L));
        h.frames.put("ms:2", fh(0x0F0F0F0F0F0F0F1FL, 0x123456789ABCDEF3L, 0x7777000077770001L));  // 1-2 bits apart
        h.frames.put("ms:3", fh(0xF0F0F0F0F0F0F0F0L, 0x123456789ABCDEF0L, 0x0000777700007777L));
        h.frames.put("ms:5", fh(0x0F0F0F0F0F0F0F0FL, 0x123456789ABCDEF0L, 0x7777000077770000L));
        // Flat (black) frames hash identically but carry no information: not a match.
        h.frames.put("ms:6", new DuplicateFinder.FrameHash(new long[]{0, 0, 0x55L}, new boolean[]{false, false, true}));
        h.frames.put("ms:7", new DuplicateFinder.FrameHash(new long[]{0, 0, 0x55L}, new boolean[]{false, false, true}));
        DuplicateFinder f = finder(h, new MapCache());
        List<DuplicateFinder.Group> g = f.find(l, true);
        assertEquals(1, g.size());
        DuplicateFinder.Group d = g.get(0);
        assertFalse(d.exact);
        assertEquals(new HashSet<>(Arrays.asList("ms:1", "ms:2")), keys(d));
        assertEquals("ms:1", d.keep.key);                                        // highest resolution kept
        assertEquals("Suggested: keep the highest resolution (3840×2160)", DuplicateFinder.keepReason(d));
        assertTrue(DuplicateFinder.groupTitle(d).startsWith("Look alike · 2 videos"));
        // Frames only decoded for videos with a duration neighbour (not the long film).
        assertFalse(h.frameCalls.contains("ms:4"));
        // No exact work: all sizes differ.
        assertEquals(0, h.partialCalls.size());
    }

    @Test
    public void exactAndLookAlikeMergeIntoOneGroup() throws Exception {
        List<DuplicateFinder.Item> l = items(
                vid(1, "Talk", 50_000_000, 600_000, 1280, 720, 100, "Camera"),
                vid(2, "Talk (1)", 50_000_000, 600_000, 1280, 720, 200, "Download"),
                vid(3, "Talk 1080p", 90_000_000, 600_300, 1920, 1080, 300, "Movies"));
        FakeHasher h = new FakeHasher();
        h.partial.put("ms:1", "P");
        h.partial.put("ms:2", "P");
        h.full.put("ms:1", "F");
        h.full.put("ms:2", "F");
        h.frames.put("ms:1", fh(1, 2, 3));
        h.frames.put("ms:2", fh(1, 2, 3));
        h.frames.put("ms:3", fh(1, 3, 3));
        List<DuplicateFinder.Group> g = finder(h, new MapCache()).find(l, true);
        assertEquals(1, g.size());
        assertEquals(3, g.get(0).items.size());
        assertFalse(g.get(0).exact);
        assertEquals("ms:3", g.get(0).keep.key);
        assertEquals(100_000_000, g.get(0).reclaimableBytes());
    }

    @Test
    public void keepOrderIsResolutionThenSizeThenOldest() {
        DuplicateFinder.Item hiRes = new DuplicateFinder.Item(vid(1, "a", 10, 0, 1920, 1080, 300, "x"));
        DuplicateFinder.Item big = new DuplicateFinder.Item(vid(2, "b", 99, 0, 1280, 720, 200, "x"));
        DuplicateFinder.Item old = new DuplicateFinder.Item(vid(3, "c", 50, 0, 1280, 720, 100, "x"));
        DuplicateFinder.Item young = new DuplicateFinder.Item(vid(4, "d", 50, 0, 1280, 720, 900, "x"));
        List<DuplicateFinder.Item> l = new ArrayList<>(Arrays.asList(young, old, big, hiRes));
        l.sort(DuplicateFinder.KEEP_ORDER);
        assertEquals(Arrays.asList(hiRes, big, old, young), l);
        assertEquals("Suggested: keep the largest file (99 KB)".replace("99 KB", Fmt.size(99)),
                DuplicateFinder.keepReason(new DuplicateFinder.Group(true, Arrays.asList(big, old))));
        assertEquals("Suggested: keep the oldest copy",
                DuplicateFinder.keepReason(new DuplicateFinder.Group(true, Arrays.asList(young, old))));
    }

    // ------------------------------------------------------------------ dHash

    @Test
    public void dhashIsStableAcrossResolutionsAndRejectsFlatFrames() {
        int[] grad = new int[72];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 9; x++) grad[y * 9 + x] = 250 - x * 20;
        long[] g = DuplicateFinder.dhash(grad);
        assertEquals(-1L, g[0]);          // every pixel brighter than its right neighbour
        assertEquals(1, g[1]);
        int[] flat = new int[72];
        Arrays.fill(flat, 3);
        assertEquals(0, DuplicateFinder.dhash(flat)[1]);

        long a = DuplicateFinder.dhash(scene(1920, 1080, 1))[0];
        long b = DuplicateFinder.dhash(scene(640, 360, 1))[0];
        long c = DuplicateFinder.dhash(scene(1280, 720, 7))[0];
        assertTrue("same picture, other resolution: " + Long.bitCount(a ^ b), Long.bitCount(a ^ b) <= DuplicateFinder.NEAR_MAX_BITS);
        assertTrue("different picture: " + Long.bitCount(a ^ c), Long.bitCount(a ^ c) > DuplicateFinder.NEAR_MAX_BITS);
        assertEquals(0, DuplicateFinder.dhash(solid(320, 180, Color.BLACK))[1]);

        DuplicateFinder.FrameHash x = new DuplicateFinder.FrameHash(new long[]{a, -1L, 5}, new boolean[]{true, false, true});
        DuplicateFinder.FrameHash back = DuplicateFinder.FrameHash.decode(x.encode());
        assertNotNull(back);
        assertEquals(a, back.hash[0]);
        assertFalse(back.informative[1]);
        assertNull(DuplicateFinder.FrameHash.decode("zz,1"));
    }

    static Bitmap scene(int w, int h, int seed) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Random r = new Random(seed);
        c.drawColor(Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 14; i++) {
            p.setColor(Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
            float cx = r.nextFloat() * w, cy = r.nextFloat() * h, rad = (0.08f + r.nextFloat() * 0.25f) * h;
            c.drawCircle(cx, cy, rad, p);
        }
        return b;
    }

    static Bitmap solid(int w, int h, int color) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(color);
        return b;
    }

    // ------------------------------------------------------------------ cache / cancel

    @Test
    public void hashCacheSkipsRereadsInvalidatesChangedFilesAndKeepsPrivateOffDisk() throws Exception {
        VideoItem v1 = vid(1, "A", 5_000, 60_000, 1280, 720, 100, "Camera");
        VideoItem v2 = vid(2, "A copy", 5_000, 60_000, 1280, 720, 100, "Camera");
        Vault.Entry pe = new Vault.Entry();
        pe.id = "secret1";
        pe.name = "Passport scan.mp4";
        pe.size = 5_000;
        pe.durationMs = 60_000;
        List<DuplicateFinder.Item> l = items(v1, v2);
        l.add(new DuplicateFinder.Item(pe));
        FakeHasher h = new FakeHasher();
        for (String k : new String[]{"ms:1", "ms:2", "pv:secret1"}) {
            h.partial.put(k, "P");
            h.full.put(k, "F");
            h.frames.put(k, fh(9, 9, 9));
        }
        DuplicateScan.HashCache c = DuplicateScan.HashCache.load(app);
        assertEquals(1, finder(h, c).find(l, true).size());
        c.save(app);
        String disk = new String(Files.readAllBytes(DuplicateScan.HashCache.file(app).toPath()));
        assertTrue(disk.contains("ms:1\t") && disk.contains("ms:2\t"));
        assertFalse("Private hashes never written to disk", disk.contains("pv:") || disk.contains("secret"));

        // Second scan in a new cache instance (e.g. next app start): nothing read again.
        h.partialCalls.clear();
        h.fullCalls.clear();
        DuplicateScan.HashCache c2 = DuplicateScan.HashCache.load(app);
        DuplicateFinder f2 = finder(h, c2);
        assertEquals(1, f2.find(l, true).size());
        assertEquals(0, h.partialCalls.size());
        assertEquals(0, h.fullCalls.size());
        assertTrue(f2.stats.cacheHits >= 6);

        // A changed file (new size/date) is hashed again.
        VideoItem v2b = vid(2, "A copy", 5_000, 60_000, 1280, 720, 999, "Camera");
        List<DuplicateFinder.Item> l2 = items(v1, v2b);
        finder(h, c2).find(l2, true);
        assertEquals(Arrays.asList("ms:2"), h.partialCalls);

        // Gone videos are pruned.
        c2.prune(new HashSet<>(Arrays.asList("ms:1")));
        c2.save(app);
        disk = new String(Files.readAllBytes(DuplicateScan.HashCache.file(app).toPath()));
        assertFalse(disk.contains("ms:2\t"));
    }

    @Test
    public void cancelStopsTheScan() {
        List<DuplicateFinder.Item> l = items(vid(1, "A", 5, 0, 0, 0, 1, "x"), vid(2, "B", 5, 0, 0, 0, 1, "x"));
        AtomicBoolean cancel = new AtomicBoolean(false);
        FakeHasher h = new FakeHasher() {
            @Override public String partial(DuplicateFinder.Item it) {
                cancel.set(true);
                return super.partial(it);
            }
        };
        try {
            new DuplicateFinder(h, new MapCache(), cancel::get, (p, d, t) -> { }).find(l, true);
            fail("expected cancel");
        } catch (DuplicateFinder.CancelledException expected) {
            assertEquals(1, h.partialCalls.size());
        }
    }

    // ------------------------------------------------------------------ real hashing, library + Private

    @Test
    public void realHasherFindsALibraryVideoThatIsAlsoInPrivate() throws Exception {
        byte[] data = new byte[300_000];
        new Random(42).nextBytes(data);
        File lib = File.createTempFile("lib", ".mp4");
        Files.write(lib.toPath(), data);
        File other = File.createTempFile("other", ".mp4");
        byte[] d2 = data.clone();
        d2[150_000] ^= 1;                     // same size, same head and tail, one byte differs in the middle
        Files.write(other.toPath(), d2);
        Vault.Entry pe = Vault.importFrom(app, Uri.fromFile(lib), "Holiday.mp4", "video/mp4", -1, null);
        VaultLock.unlock();
        VideoItem a = new VideoItem(11, Uri.fromFile(lib), "Holiday.mp4", 0, data.length, 1000, "c", "Camera", 0, 0);
        VideoItem b = new VideoItem(12, Uri.fromFile(other), "Holiday edit.mp4", 0, data.length, 2000, "c", "Camera", 0, 0);
        DuplicateScan.sourceOverride = (ctx, priv) -> {
            List<DuplicateFinder.Item> l = items(a, b);
            if (priv) for (Vault.Entry e : Vault.list(ctx)) l.add(new DuplicateFinder.Item(e));
            return l;
        };
        DuplicateScan.executorOverride = Runnable::run;
        DuplicateScan.get().start(app, true);
        assertTrue(await(() -> !DuplicateScan.get().isRunning(), 10_000));
        DuplicateScan.Result r = DuplicateScan.get().last();
        assertNotNull(r);
        assertEquals(3, r.scanned);
        assertEquals(1, r.groups.size());
        DuplicateFinder.Group g = r.groups.get(0);
        assertTrue(g.exact);
        assertEquals(new HashSet<>(Arrays.asList("ms:11", "pv:" + pe.id)), keys(g));
        assertEquals("ms:11", g.keep.key);   // same size & resolution: the oldest (library, added earlier)
        assertEquals(3, r.stats.partialHashed);
        assertEquals(3, r.stats.fullHashed);
        // Locking Private removes its videos from the result.
        assertTrue(r.withoutPrivate().groups.isEmpty());
        assertEquals(Vault.hex(Vault.sha256().digest(data)), DuplicateScan.sha256(new java.io.ByteArrayInputStream(data), () -> false));
    }

    // ------------------------------------------------------------------ screen

    private DuplicateScan.Result fakeResult(List<VideoItem> lib, Vault.Entry priv) {
        List<DuplicateFinder.Group> groups = new ArrayList<>();
        groups.add(new DuplicateFinder.Group(true, Arrays.asList(new DuplicateFinder.Item(lib.get(0)), new DuplicateFinder.Item(lib.get(1)))));
        groups.add(new DuplicateFinder.Group(false, Arrays.asList(new DuplicateFinder.Item(lib.get(2)),
                new DuplicateFinder.Item(lib.get(3)), new DuplicateFinder.Item(priv))));
        return new DuplicateScan.Result(groups, 1284, true, System.currentTimeMillis(), new DuplicateFinder.Stats());
    }

    @SuppressWarnings("unchecked")
    private List<VideoItem> fakeLibrary() throws Exception {
        List<VideoItem> l = new ArrayList<>();
        l.add(vid(101, "Interstellar (2014) 1080p", 4_812_000_000L, 169 * 60_000L, 1920, 1080, 1_700_000_000L, "Movies"));
        l.add(vid(102, "Interstellar (2014) 1080p", 4_812_000_000L, 169 * 60_000L, 1920, 1080, 1_760_000_000L, "Download"));
        l.add(vid(103, "Drone flight Snowdonia", 1_530_000_000L, 4 * 60_000L + 12_000, 3840, 2160, 1_720_000_000L, "DJI"));
        l.add(vid(104, "WhatsApp Video 2026-09-28", 41_000_000L, 4 * 60_000L + 12_400, 848, 480, 1_780_000_000L, "WhatsApp Video"));
        Field f = ThumbnailLoader.class.getDeclaredField("CACHE");
        f.setAccessible(true);
        LruCache<Long, Bitmap> cache = (LruCache<Long, Bitmap>) f.get(null);
        cache.put(101L, PreviewScreenshotTest.fakeFrame(384, 216, 6));
        cache.put(102L, PreviewScreenshotTest.fakeFrame(384, 216, 6));
        cache.put(103L, PreviewScreenshotTest.fakeFrame(384, 216, 10));
        cache.put(104L, PreviewScreenshotTest.fakeFrame(384, 216, 10));
        return l;
    }

    @Test
    public void screenSuggestsTicksGuardsLastCopyAndTrashesWithOneUndo() throws Exception {
        MediaAccess.overrideForTest = true;
        List<VideoItem> lib = fakeLibrary();
        byte[] data = new byte[20_000];
        new Random(5).nextBytes(data);
        File src = File.createTempFile("priv", ".mp4");
        Files.write(src.toPath(), data);
        Vault.Entry pe = Vault.importFrom(app, Uri.fromFile(src), "Drone flight (private copy).mp4", "video/mp4", -1, null);
        pe.width = 1920;
        pe.height = 1080;
        pe.durationMs = 4 * 60_000L + 12_100;
        VaultLock.unlock();
        DuplicateScan.Result r = fakeResult(lib, pe);
        DuplicateScan.get().setLast(r);

        ActivityController<DuplicatesActivity> ctl = Robolectric.buildActivity(DuplicatesActivity.class).setup();
        DuplicatesActivity a = ctl.get();
        idle();
        // Opened with a remembered result: suggestions ticked, but nothing deleted.
        assertEquals(7, a.rows().size());                     // 2 headers + 5 videos
        assertEquals(new HashSet<>(Arrays.asList("ms:102", "ms:104", "pv:" + pe.id)), a.ticked());
        assertEquals(0, V151FeatureTest.requests.size());
        assertEquals(1, Vault.list(app).size());
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        TextView info = a.findViewById(R.id.dupes_info);
        assertTrue(info.getText().toString(), info.getText().toString().startsWith("Scanned 1,284 videos (incl. Private) · 2 groups"));
        save(a.getWindow().getDecorView(), "v19_duplicates.png", 1080, 2340);

        // Ticking every copy of a group is refused.
        DuplicateFinder.Group g0 = r.groups.get(0);
        a.toggle(g0.keep);
        assertEquals(1, a.fullyTickedGroups().size());
        AlertDialog guard = a.confirmDelete();
        assertNotNull(guard);
        assertEquals("Keep at least one copy", ((TextView) guard.findViewById(androidx.appcompat.R.id.alertTitle)).getText().toString());
        guard.getButton(DialogInterface.BUTTON_POSITIVE).performClick();   // "Use suggestions"
        idle();
        assertTrue(a.fullyTickedGroups().isEmpty());
        assertEquals(0, V151FeatureTest.requests.size());

        // Confirm → one trash request for the 2 library copies; the Private copy goes to the Private trash.
        AlertDialog d = a.confirmDelete();
        assertNotNull(d);
        assertEquals("Remove 3 duplicates?", ((TextView) d.findViewById(androidx.appcompat.R.id.alertTitle)).getText().toString());
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(1, V151FeatureTest.requests.size());
        assertEquals(DeleteVideosFlow.REQ_TRASH, V151FeatureTest.requests.get(0)[0]);
        assertEquals(2, V151FeatureTest.requests.get(0)[1]);
        assertEquals(1, Vault.list(app).size());   // not before the library part went through
        a.deleteFlow().onSystemResult(true);
        assertTrue(await(() -> Vault.list(app).isEmpty() && Vault.listTrashed(app).size() == 1, 5000));
        assertTrue(a.rows().isEmpty());
        assertNotNull(a.undoBar());
        assertNull("the flow's own undo bar is replaced by one combined bar", a.deleteFlow().undoBar());
        TextView snack = a.undoBar().getView().findViewById(com.google.android.material.R.id.snackbar_text);
        assertEquals("Deleted 3 videos", snack.getText().toString());
        assertEquals(View.VISIBLE, a.findViewById(R.id.dupes_empty).getVisibility());

        // Undo: one untrash request + Private restore; the groups come back.
        a.undoBar().getView().findViewById(com.google.android.material.R.id.snackbar_action).performClick();
        idle();
        assertEquals(2, V151FeatureTest.requests.size());
        assertEquals(DeleteVideosFlow.REQ_UNTRASH, V151FeatureTest.requests.get(1)[0]);
        a.deleteFlow().onSystemResult(true);
        assertTrue(await(() -> Vault.list(app).size() == 1 && a.rows().size() == 7, 5000));
        ctl.pause().stop().destroy();
    }

    @Test
    public void declinedSystemPromptTouchesNothingAndLockedPrivateIsHidden() throws Exception {
        MediaAccess.overrideForTest = false;
        List<VideoItem> lib = fakeLibrary();
        byte[] data = new byte[10_000];
        new Random(6).nextBytes(data);
        File src = File.createTempFile("priv", ".mp4");
        Files.write(src.toPath(), data);
        Vault.Entry pe = Vault.importFrom(app, Uri.fromFile(src), "Copy.mp4", "video/mp4", -1, null);
        VaultLock.unlock();
        DuplicateScan.get().setLast(fakeResult(lib, pe));
        ActivityController<DuplicatesActivity> ctl = Robolectric.buildActivity(DuplicatesActivity.class).setup();
        DuplicatesActivity a = ctl.get();
        idle();
        a.confirmDelete().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        a.deleteFlow().onSystemResult(false);       // "Move to trash?" declined
        idle();
        assertEquals(1, Vault.list(app).size());
        assertEquals(7, a.rows().size());
        assertNull(a.undoBar());
        ctl.pause().stop();
        VaultLock.lock();
        ctl.start().resume();
        idle();
        for (Object o : a.rows()) {
            if (o instanceof DuplicatesActivity.Row) assertFalse(((DuplicatesActivity.Row) o).item.isPrivate());
        }
        assertEquals(6, a.rows().size());           // 2 headers + 4 library videos (look-alike group keeps 2)
        ctl.pause().stop().destroy();
    }

    @Test
    public void menuEntryAndProgressLabels() {
        android.content.Context themed = new androidx.appcompat.view.ContextThemeWrapper(app, R.style.Theme_VideoPlayer);
        Menu m = new MenuBuilder(themed);
        new androidx.appcompat.view.SupportMenuInflater(themed).inflate(R.menu.main, m);
        assertNotNull(m.findItem(R.id.action_duplicates));
        assertEquals("Find duplicates…", m.findItem(R.id.action_duplicates).getTitle().toString());
        assertEquals("Comparing pictures · 3 of 40", DuplicatesActivity.progressLabel("Comparing pictures", 2, 40));
        assertEquals("Listing videos…", DuplicatesActivity.progressLabel("Listing videos", 0, 0));
        assertEquals("Scanned 12 videos · no duplicates", DuplicatesActivity.summary(12, 0, 0, false));
    }

    private static Set<String> keys(DuplicateFinder.Group g) {
        Set<String> s = new HashSet<>();
        for (DuplicateFinder.Item it : g.items) s.add(it.key);
        return s;
    }
}
