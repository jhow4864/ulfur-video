package com.jamh.videoplayer;

import static com.jamh.videoplayer.TestUtil.await;
import static com.jamh.videoplayer.TestUtil.field;
import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.CommandButton;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** 1.7.0: smart sorting & filters, player gestures, Cast to TV. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class V17FeatureTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        VideoKey.clearCache();
    }

    @After
    public void tearDown() {
        Caster.get().handleSessionEnded(null);
        idle();
        Caster.testRemote = null;
        Caster.testBindAddress = null;
        Caster.lastWarning = null;
    }

    // ================================================================== sorting & filters

    private static final long MIN = 60_000L;

    private static VideoItem vid(long id, String name, long durMs, long size, long dateSec, int w, int h) {
        return new VideoItem(id, Uri.parse("content://media/external/video/media/" + id), name + ".mp4",
                durMs, size, dateSec, "b1", "Camera", w, h);
    }

    private static List<String> names(List<VideoItem> l) {
        List<String> out = new ArrayList<>();
        for (VideoItem v : l) out.add(v.title);
        return out;
    }

    private List<VideoItem> library() {
        return Arrays.asList(
                vid(1, "Episode 10", 22 * MIN, 900_000_000L, 1_000, 1920, 1080),   // long, FHD, landscape
                vid(2, "Episode 2", 3 * MIN, 50_000_000L, 3_000, 1280, 720),       // short, HD
                vid(3, "beach", 10 * MIN, 2_000_000_000L, 2_000, 3840, 2160),      // medium, 4K
                vid(4, "Selfie", 30_000, 10_000_000L, 4_000, 1080, 1920),          // short, FHD, portrait
                vid(5, "Old VHS rip", 45 * MIN, 700_000_000L, 500, 640, 480),      // long, SD
                vid(6, "Unknown", 0, 1_000L, 100, 0, 0));                          // no metadata
    }

    @Test
    public void everySortKeyInBothDirections() {
        List<VideoItem> lib = library();
        LibrarySort.Extractor<VideoItem> x = LibrarySort.videos(WatchHistory.empty());
        assertEquals(Arrays.asList("Selfie", "Episode 2", "beach", "Episode 10", "Old VHS rip", "Unknown"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.DATE, true, false, 0), x)));
        assertEquals(Arrays.asList("Unknown", "Old VHS rip", "Episode 10", "beach", "Episode 2", "Selfie"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.DATE, false, false, 0), x)));
        // Natural, case-insensitive: "Episode 2" before "Episode 10".
        assertEquals(Arrays.asList("beach", "Episode 2", "Episode 10", "Old VHS rip", "Selfie", "Unknown"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.NAME, false, false, 0), x)));
        assertEquals(Arrays.asList("Unknown", "Selfie", "Old VHS rip", "Episode 10", "Episode 2", "beach"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.NAME, true, false, 0), x)));
        assertEquals(Arrays.asList("Old VHS rip", "Episode 10", "beach", "Episode 2", "Selfie", "Unknown"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.LENGTH, true, false, 0), x)));
        assertEquals(Arrays.asList("Unknown", "Selfie", "Episode 2", "beach", "Episode 10", "Old VHS rip"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.LENGTH, false, false, 0), x)));
        assertEquals(Arrays.asList("beach", "Episode 10", "Old VHS rip", "Episode 2", "Selfie", "Unknown"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.SIZE, true, false, 0), x)));
        assertEquals(Arrays.asList("Unknown", "Selfie", "Episode 2", "Old VHS rip", "Episode 10", "beach"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.SIZE, false, false, 0), x)));
        // Resolution by the short side; ties (1080 x 1920 vs 1920 x 1080) by name.
        assertEquals(Arrays.asList("beach", "Episode 10", "Selfie", "Episode 2", "Old VHS rip", "Unknown"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.RESOLUTION, true, false, 0), x)));
        assertEquals(Arrays.asList("Unknown", "Old VHS rip", "Episode 2", "Episode 10", "Selfie", "beach"),
                names(LibrarySort.apply(lib, new LibraryView(LibraryView.RESOLUTION, false, false, 0), x)));
    }

    @Test
    public void recentlyWatchedKeepsNeverWatchedLast() {
        List<VideoItem> lib = library();
        Map<String, Object> history = new HashMap<>();
        history.put("ms:3", 5_000L);
        history.put("ms:1", 9_000L);
        Map<String, Object> resume = new HashMap<>();
        resume.put("ms:5", "60000,2700000,7000");   // resume point saved at t=7000
        resume.put("ms:2", "1000,180000");           // pre-1.7 resume point: no time stamp
        WatchHistory.Snapshot s = new WatchHistory.Snapshot(history, resume);
        LibrarySort.Extractor<VideoItem> x = LibrarySort.videos(s);
        List<String> desc = names(LibrarySort.apply(lib, new LibraryView(LibraryView.WATCHED, true, false, 0), x));
        assertEquals(Arrays.asList("Episode 10", "Old VHS rip", "beach"), desc.subList(0, 3));
        List<String> asc = names(LibrarySort.apply(lib, new LibraryView(LibraryView.WATCHED, false, false, 0), x));
        assertEquals(Arrays.asList("beach", "Old VHS rip", "Episode 10"), asc.subList(0, 3));
        assertEquals("never watched stay last, by name", Arrays.asList("Episode 2", "Selfie", "Unknown"), asc.subList(3, 6));

        assertEquals(WatchHistory.IN_PROGRESS, s.state("ms:5"));
        assertEquals(WatchHistory.IN_PROGRESS, s.state("ms:2"));
        assertEquals(WatchHistory.WATCHED, s.state("ms:1"));
        assertEquals(WatchHistory.UNWATCHED, s.state("ms:4"));
        assertEquals(Arrays.asList("Selfie", "Unknown"), names(LibrarySort.apply(lib,
                new LibraryView(LibraryView.NAME, false, false, LibraryView.F_UNWATCHED), x)));
        assertEquals(Arrays.asList("Episode 2", "Old VHS rip"), names(LibrarySort.apply(lib,
                new LibraryView(LibraryView.NAME, false, false, LibraryView.F_IN_PROGRESS), x)));
    }

    @Test
    public void filtersOrWithinAGroupAndAcrossGroups() {
        List<VideoItem> lib = library();
        LibrarySort.Extractor<VideoItem> x = LibrarySort.videos(WatchHistory.empty());
        LibraryView name = new LibraryView(LibraryView.NAME, false, false, 0);
        assertEquals(Arrays.asList("Episode 2", "Selfie"),
                names(LibrarySort.apply(lib, name.withFilter(LibraryView.F_SHORT, true), x)));
        assertEquals(Arrays.asList("beach"), names(LibrarySort.apply(lib, name.withFilter(LibraryView.F_MEDIUM, true), x)));
        assertEquals(Arrays.asList("Episode 10", "Old VHS rip"),
                names(LibrarySort.apply(lib, name.withFilter(LibraryView.F_LONG, true), x)));
        // OR inside the duration group, AND with resolution.
        LibraryView shortOrLongFhd = name.withFilter(LibraryView.F_SHORT, true).withFilter(LibraryView.F_LONG, true)
                .withFilter(LibraryView.F_FHD, true);
        assertEquals(Arrays.asList("Episode 10", "Selfie"), names(LibrarySort.apply(lib, shortOrLongFhd, x)));
        assertEquals(Arrays.asList("Old VHS rip"), names(LibrarySort.apply(lib, name.withFilter(LibraryView.F_SD, true), x)));
        assertEquals(Arrays.asList("Episode 2"), names(LibrarySort.apply(lib, name.withFilter(LibraryView.F_HD, true), x)));
        assertEquals(Arrays.asList("beach"), names(LibrarySort.apply(lib, name.withFilter(LibraryView.F_4K, true), x)));
        assertEquals(Arrays.asList("Selfie"), names(LibrarySort.apply(lib, name.withFilter(LibraryView.F_PORTRAIT, true), x)));
        assertEquals("unknown size is excluded by orientation filters", 4,
                LibrarySort.apply(lib, name.withFilter(LibraryView.F_LANDSCAPE, true), x).size());
        assertEquals(Arrays.asList("Selfie"), names(LibrarySort.apply(lib,
                name.withFilter(LibraryView.F_PORTRAIT, true).withFilter(LibraryView.F_SHORT, true), x)));
        assertTrue(LibrarySort.apply(lib, name.withFilter(LibraryView.F_PORTRAIT, true)
                .withFilter(LibraryView.F_LONG, true), x).isEmpty());
        assertEquals(3, name.withFilter(LibraryView.F_SHORT, true).withFilter(LibraryView.F_4K, true)
                .withFilter(LibraryView.F_UNWATCHED, true).filterCount());
        assertEquals(0, name.withFilter(LibraryView.F_SHORT, true).withoutFilters().filterCount());

        // Largest first: size descending whatever the chosen sort; the total of what's shown.
        LibraryView storage = name.withStorage(true);
        assertEquals(LibraryView.SIZE, storage.effectiveSort());
        assertTrue(storage.effectiveDesc());
        List<VideoItem> big = LibrarySort.apply(lib, storage.withFilter(LibraryView.F_LONG, true), x);
        assertEquals(Arrays.asList("Episode 10", "Old VHS rip"), names(big));
        assertEquals(1_600_000_000L, LibrarySort.totalSize(big));
        assertEquals("order() ignores filters (player playlists)", 6, LibrarySort.order(lib,
                name.withFilter(LibraryView.F_4K, true), x).size());
        assertEquals("12%", LibraryAdapter.share(120, 1000));
    }

    @Test
    public void naturalCompare() {
        List<String> l = new ArrayList<>(Arrays.asList("Clip 100", "clip 9", "Clip 10", "Ábc", "abc 2", "abc 10", ""));
        l.sort(LibrarySort::naturalCompare);
        assertEquals("", l.get(0));
        assertTrue(l.indexOf("abc 2") < l.indexOf("abc 10"));
        assertTrue(l.indexOf("clip 9") < l.indexOf("Clip 10"));
        assertTrue(l.indexOf("Clip 10") < l.indexOf("Clip 100"));
    }

    @Test
    public void viewIsRememberedPerScreenAndStartsFromTheLegacySort() {
        Prefs.setSort(app, Sort.SIZE);
        assertEquals(new LibraryView(LibraryView.SIZE, true, false, 0), LibraryView.load(app, LibraryView.VIDEOS));
        assertEquals("the vault starts newest first", LibraryView.defaults(), LibraryView.load(app, LibraryView.VAULT));
        LibraryView clips = new LibraryView(LibraryView.WATCHED, true, false, LibraryView.F_PORTRAIT | LibraryView.F_UNWATCHED);
        LibraryView.save(app, LibraryView.CLIPS, clips);
        LibraryView folder = new LibraryView(LibraryView.NAME, false, true, LibraryView.F_4K);
        LibraryView.save(app, LibraryView.FOLDER, folder);
        assertEquals(clips, LibraryView.load(app, LibraryView.CLIPS));
        assertEquals(folder, LibraryView.load(app, LibraryView.FOLDER));
        assertEquals(new LibraryView(LibraryView.SIZE, true, false, 0), LibraryView.load(app, LibraryView.VIDEOS));
        assertEquals(clips, LibraryView.decode(clips.encode(), LibraryView.defaults()));
        assertEquals(LibraryView.defaults(), LibraryView.decode("garbage", LibraryView.defaults()));
        assertEquals(Sort.NAME, new LibraryView(LibraryView.NAME, false, false, 0).toLegacy());
        assertEquals(Sort.SIZE, new LibraryView(LibraryView.NAME, false, true, 0).toLegacy());
        assertEquals(new LibraryView(LibraryView.LENGTH, true, false, 0), LibraryView.fromLegacy(Sort.DURATION));
        assertEquals("Shortest first", LibraryView.directionName(LibraryView.LENGTH, false));
    }

    private ActivityController<MainActivity> library(List<VideoItem> vids) throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_EXTERNAL_STORAGE);
        Prefs.setDefaultBannerDismissed(app);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        idle();
        TestUtil.setField(a, "allVideos", vids);
        java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render");
        render.setAccessible(true);
        render.invoke(a);
        idle();
        return ctl;
    }

    private static Chip chipWithTag(ChipGroup g, Object tag) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (tag.equals(c.getTag())) return (Chip) c;
        }
        return null;
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void libraryChipsStorageViewAndSelectionSize() throws Exception {
        ActivityController<MainActivity> ctl = library(library());
        MainActivity a = ctl.get();
        ChipGroup chips = a.findViewById(R.id.library_chips);
        assertEquals(View.VISIBLE, a.findViewById(R.id.library_bar).getVisibility());
        assertEquals("sort, Largest first, Filters", 3, chips.getChildCount());
        assertEquals("Date added ↓", ((Chip) chips.getChildAt(0)).getText().toString());
        assertEquals(View.GONE, a.findViewById(R.id.storage_card).getVisibility());

        // Largest first: storage card with the total, biggest first.
        chipWithTag(chips, "storage").performClick();
        idle();
        assertTrue(LibraryView.load(a, LibraryView.VIDEOS).storage);
        assertEquals(View.VISIBLE, a.findViewById(R.id.storage_card).getVisibility());
        String total = ((TextView) a.findViewById(R.id.storage_total)).getText().toString();
        assertEquals(Fmt.size(3_660_001_000L) + " in this view", total);
        assertEquals("beach", a.shownVideos().get(0).title);

        // Select two: the card and toolbar show the selected size.
        LibraryAdapter adapter = (LibraryAdapter) field(a, "adapter");
        adapter.setSelectionMode(true);
        adapter.toggle(a.shownVideos().get(0));
        adapter.toggle(a.shownVideos().get(1));
        idle();
        assertEquals(2_900_000_000L, a.selectedBytes());
        TextView sel = a.findViewById(R.id.storage_selected);
        assertEquals(View.VISIBLE, sel.getVisibility());
        assertEquals(Fmt.size(2_900_000_000L) + " selected", sel.getText().toString());
        adapter.setSelectionMode(false);
        idle();

        // A filter: chip per active filter, removable; "n of m" in the subtitle.
        a.setView(LibraryView.load(a, LibraryView.VIDEOS).withStorage(false).withFilter(LibraryView.F_SHORT, true));
        idle();
        assertEquals(Arrays.asList("Episode 2", "Selfie"), sortedNames(a.shownVideos()));
        Chip shortChip = chipWithTag(chips, LibraryView.F_SHORT);
        assertNotNull(shortChip);
        assertEquals("Filters · 1", chipWithTag(chips, "filters").getText().toString());
        TextView subtitle = (TextView) field(a, "subtitle");
        assertTrue(subtitle.getText().toString(), subtitle.getText().toString().startsWith("2 of 6 videos"));
        shortChip.performCloseIconClick();
        idle();
        assertEquals(6, a.shownVideos().size());

        // Nothing matches: a "no matches" message rather than "no videos".
        a.setView(LibraryView.load(a, LibraryView.VIDEOS).withFilter(LibraryView.F_PORTRAIT, true)
                .withFilter(LibraryView.F_LONG, true));
        idle();
        assertTrue(a.shownVideos().isEmpty());
        assertEquals(a.getString(R.string.lib_no_matches), ((TextView) field(a, "emptyText")).getText().toString());

        // Other screens keep their own view.
        TestUtil.setField(a, "tab", 2);
        assertEquals(LibraryView.CLIPS, a.screenKey());
        assertEquals(0, a.currentView().filterCount());
        ctl.pause().stop().destroy();
    }

    private static List<String> sortedNames(List<VideoItem> l) {
        List<String> n = names(l);
        Collections.sort(n);
        return n;
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void sortSheetAppliesLive() throws Exception {
        ActivityController<MainActivity> ctl = library(library());
        MainActivity a = ctl.get();
        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_library, null);
        List<LibraryView> seen = new ArrayList<>();
        LibrarySheet.bind(sheet, a.currentView(), false, new LibrarySheet.Host() {
            @Override public void onViewChanged(LibraryView v) {
                seen.add(v);
                a.setView(v);
            }

            @Override public String summary(LibraryView v) {
                return LibrarySort.apply(library(), v, LibrarySort.videos(WatchHistory.empty())).size() + " videos";
            }
        });
        ChipGroup sorts = sheet.findViewById(R.id.lib_sort);
        assertEquals(LibrarySheet.VIDEO_SORTS.length, sorts.getChildCount());
        chipWithTag(sorts, LibraryView.NAME).performClick();
        idle();
        assertEquals(LibraryView.NAME, a.currentView().sort);
        assertFalse("names start A-Z", a.currentView().desc);
        sheet.findViewById(R.id.lib_dir_desc).performClick();
        idle();
        assertTrue(a.currentView().desc);
        assertEquals("Unknown", a.shownVideos().get(0).title);
        ChipGroup res = sheet.findViewById(R.id.lib_f_res);
        chipWithTag(res, LibraryView.F_4K).performClick();
        idle();
        assertEquals(Collections.singletonList("beach"), names(a.shownVideos()));
        assertEquals("1 videos", ((TextView) sheet.findViewById(R.id.lib_count)).getText().toString());
        sheet.findViewById(R.id.lib_reset).performClick();
        idle();
        assertEquals(0, a.currentView().filterCount());
        assertFalse(seen.isEmpty());
        ctl.pause().stop().destroy();
    }

    private Vault.Entry entry(String id, String name, long dur, long size, int w, int h, long added) {
        Vault.Entry e = new Vault.Entry();
        e.id = id;
        e.file = id + ".mp4";
        e.name = name;
        e.mime = "video/mp4";
        e.durationMs = dur;
        e.size = size;
        e.width = w;
        e.height = h;
        e.addedMs = added;
        return e;
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void vaultFiltersAndStorageView() {
        VaultLock.unlock();
        ActivityController<VaultActivity> ctl = Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity a = ctl.get();
        idle();
        a.showContent();
        TextView storage = a.findViewById(R.id.vault_storage);
        assertTrue(await(() -> storage.getText().toString().startsWith("0 videos"), 5000));
        List<Vault.Entry> list = new ArrayList<>(Arrays.asList(
                entry("v1", "Wedding speech.mp4", 312_000, 486_000_000L, 1920, 1080, 3_000),
                entry("v2", "Passport scan.mp4", 14_000, 22_000_000L, 1080, 1920, 4_000),
                entry("v3", "Surprise plan.mp4", 30 * MIN, 1_600_000_000L, 3840, 2160, 1_000)));
        a.bind(list, 2_108_000_000L, 41L * 1024 * 1024 * 1024);
        idle();
        assertEquals(View.VISIBLE, a.findViewById(R.id.vault_library_bar).getVisibility());
        assertEquals(Arrays.asList("v2", "v1", "v3"), files(a.shownEntries()));
        a.setView(LibraryView.defaults().withStorage(true));
        idle();
        assertEquals(Arrays.asList("v3", "v1", "v2"), files(a.shownEntries()));
        assertTrue(((TextView) a.findViewById(R.id.vault_hint)).getText().toString()
                .contains(Fmt.size(2_108_000_000L)));
        a.setView(LibraryView.defaults().withFilter(LibraryView.F_PORTRAIT, true));
        idle();
        assertEquals(Collections.singletonList("v2"), files(a.shownEntries()));
        // Private history is separate from the gallery's and feeds the vault's filters.
        WatchHistory.mark(a, "v1.mp4", true);
        a.setView(LibraryView.defaults().withFilter(LibraryView.F_UNWATCHED, true));
        idle();
        assertEquals(Arrays.asList("v2", "v3"), files(a.shownEntries()));
        assertTrue(WatchHistory.gallery(a).state("v1.mp4") == WatchHistory.UNWATCHED);
        a.setView(LibraryView.defaults().withFilter(LibraryView.F_SD, true));
        idle();
        assertTrue(a.shownEntries().isEmpty());
        assertEquals(a.getString(R.string.lib_no_matches),
                ((TextView) a.findViewById(R.id.vault_empty)).getText().toString());
        a.setView(LibraryView.defaults());
        ctl.pause().stop().destroy();
    }

    private static List<String> files(List<Vault.Entry> l) {
        List<String> out = new ArrayList<>();
        for (Vault.Entry e : l) out.add(e.id);
        return out;
    }

    // ================================================================== player gestures

    @Test
    public void gestureHelpers() {
        // 1080 x 2340 phone, 30 px back-gesture insets at the sides, 60 px home bar.
        assertTrue(PlayerActivity.inEdgeZone(10, 1000, 1080, 2340, 30, 0, 30, 60, 20));
        assertTrue(PlayerActivity.inEdgeZone(1070, 1000, 1080, 2340, 30, 0, 30, 60, 20));
        assertTrue("top 6%", PlayerActivity.inEdgeZone(500, 100, 1080, 2340, 30, 0, 30, 60, 20));
        assertTrue("bottom 6%", PlayerActivity.inEdgeZone(500, 2300, 1080, 2340, 30, 0, 30, 60, 20));
        assertFalse(PlayerActivity.inEdgeZone(500, 1000, 1080, 2340, 30, 0, 30, 60, 20));
        assertFalse("3-button nav: only the minimum strip", PlayerActivity.inEdgeZone(25, 1000, 1080, 2340, 0, 0, 0, 0, 20));
        assertTrue(PlayerActivity.inEdgeZone(15, 1000, 1080, 2340, 0, 0, 0, 0, 20));

        // 16:9 video on a 19.5:9 landscape screen: fill = 2340/1080 / (16/9) = 1.21875.
        float fill = PlayerActivity.fillZoomFor(1920, 1080, 2340, 1080);
        assertEquals(1.21875f, fill, 0.001f);
        assertEquals(PlayerActivity.SNAP_CROP, PlayerActivity.pinchSnap(0, 1.25f, fill));
        assertEquals(PlayerActivity.SNAP_ONE, PlayerActivity.pinchSnap(0, 1.04f, fill));
        assertEquals(PlayerActivity.SNAP_NONE, PlayerActivity.pinchSnap(0, 1.6f, fill));
        assertEquals("pinch in from crop: back to fit", PlayerActivity.SNAP_FIT, PlayerActivity.pinchSnap(1, 0.8f, fill));
        assertEquals(PlayerActivity.SNAP_ONE, PlayerActivity.pinchSnap(1, 1.05f, fill));
        assertEquals("stretch never snaps to crop", PlayerActivity.SNAP_NONE, PlayerActivity.pinchSnap(2, 1.22f, fill));
        assertEquals(1f, PlayerActivity.fillZoomFor(0, 0, 100, 100), 0f);

        assertEquals(CommandButton.ICON_SKIP_BACK_30, PlayerActivity.skipBackIcon(30));
        assertEquals(CommandButton.ICON_SKIP_FORWARD_5, PlayerActivity.skipForwardIcon(5));
        assertEquals(CommandButton.ICON_SKIP_FORWARD_15, PlayerActivity.skipForwardIcon(15));
        assertEquals(10, Prefs.sanitizeSkip(7));
    }

    private File video(String name) throws Exception {
        File dir = tmp.newFolder();
        byte[] b = new byte[200_000];
        new Random(5).nextBytes(b);
        File f = new File(dir, name);
        Files.write(f.toPath(), b);
        return f;
    }

    private ActivityController<PlayerActivity> launch(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.fromFile(f));
        i.setClass(app, PlayerActivity.class);
        ActivityController<PlayerActivity> ctl = Robolectric.buildActivity(PlayerActivity.class, i).setup();
        TestUtil.awaitPlayer(ctl.get());
        idle();
        return ctl;
    }

    private static void touch(View v, int action, float x, float y, long downAt) {
        MotionEvent e = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    /** Vertical swipe on the right half (volume) starting at (x, y). */
    private static void swipeUp(View layer, float x, float y) {
        long t = SystemClock.uptimeMillis();
        touch(layer, MotionEvent.ACTION_DOWN, x, y, t);
        for (int i = 1; i <= 8; i++) {
            ShadowAdvance.by(16);
            touch(layer, MotionEvent.ACTION_MOVE, x, y - i * 40, t);
        }
        touch(layer, MotionEvent.ACTION_UP, x, y - 320, t);
    }

    /** Small clock helper (Robolectric's paused looper drives SystemClock). */
    static final class ShadowAdvance {
        static void by(long ms) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms));
        }
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void skipAmountSettingAndMenuRows() throws Exception {
        Prefs.setSkipSeconds(app, 15);
        ActivityController<PlayerActivity> ctl = launch(video("Harbour.mp4"));
        PlayerActivity a = ctl.get();
        ExoPlayer p = (ExoPlayer) field(a, "player");
        assertEquals(15_000, p.getSeekForwardIncrement());
        assertEquals(15_000, p.getSeekBackIncrement());
        assertEquals("Forward 15 seconds", a.findViewById(R.id.btn_ffwd).getContentDescription().toString());
        assertEquals("Back 15 seconds", a.findViewById(R.id.btn_rew).getContentDescription().toString());

        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_player_menu, null);
        a.bindMenuSheet(sheet, new Dialog(a));
        assertEquals("15 seconds", ((TextView) sheet.findViewById(R.id.skip_value)).getText().toString());
        MaterialSwitch sw = sheet.findViewById(R.id.switch_gestures);
        assertTrue(sw.isChecked());
        sheet.findViewById(R.id.row_gestures).performClick();
        assertFalse(Prefs.gesturesEnabled(app));
        assertFalse((Boolean) field(a, "gesturesEnabled"));
        sheet.findViewById(R.id.row_gestures).performClick();
        assertTrue(Prefs.gesturesEnabled(app));

        AlertDialog ad = a.showSkipDialog();
        idle();
        assertEquals(4, ad.getListView().getCount());
        ad.getListView().performItemClick(ad.getListView().getChildAt(3), 3, 3);
        idle();
        assertEquals(30, Prefs.skipSeconds(app));
        assertEquals("Forward 30 seconds", a.findViewById(R.id.btn_ffwd).getContentDescription().toString());
        ctl.pause().stop().destroy();
        Prefs.setSkipSeconds(app, 10);
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void swipesHonourTheToggleAndEdges() throws Exception {
        ActivityController<PlayerActivity> ctl = launch(video("Harbour.mp4"));
        PlayerActivity a = ctl.get();
        View layer = a.findViewById(R.id.gesture_layer);
        View panel = a.findViewById(R.id.gesture_panel);
        int w = layer.getWidth(), h = layer.getHeight();
        assertTrue("laid out", w > 0 && h > 0);
        TestUtil.setField(a, "controlsVisible", false);
        a.findViewById(R.id.controls).setVisibility(View.GONE);

        swipeUp(layer, w * 0.75f, h * 0.7f);
        assertEquals("volume panel", View.VISIBLE, panel.getVisibility());
        ShadowAdvance.by(2000);
        assertEquals(View.GONE, panel.getVisibility());

        // From the very edge (system back gesture): ignored.
        swipeUp(layer, 3, h * 0.7f);
        assertEquals(View.GONE, panel.getVisibility());

        // Gestures off: swipes do nothing, a tap still shows the controls.
        Prefs.setGesturesEnabled(app, false);
        TestUtil.setField(a, "gesturesEnabled", false);
        swipeUp(layer, w * 0.75f, h * 0.7f);
        assertEquals(View.GONE, panel.getVisibility());
        long t = SystemClock.uptimeMillis();
        touch(layer, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, t);
        ShadowAdvance.by(50);
        touch(layer, MotionEvent.ACTION_UP, w / 2f, h / 2f, t);
        idle();
        assertTrue((Boolean) field(a, "controlsVisible"));
        Prefs.setGesturesEnabled(app, true);
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void longPressPlaysAtTwiceSpeedWhileHeld() throws Exception {
        ActivityController<PlayerActivity> ctl = launch(video("Harbour.mp4"));
        PlayerActivity a = ctl.get();
        ExoPlayer p = (ExoPlayer) field(a, "player");
        p.setPlayWhenReady(true);
        View layer = a.findViewById(R.id.gesture_layer);
        TestUtil.setField(a, "controlsVisible", false);
        a.findViewById(R.id.controls).setVisibility(View.GONE);
        float x = layer.getWidth() / 2f, y = layer.getHeight() / 2f;
        long t = SystemClock.uptimeMillis();
        touch(layer, MotionEvent.ACTION_DOWN, x, y, t);
        ShadowAdvance.by(800);
        assertTrue(a.isHoldingFast());
        assertEquals(2f, p.getPlaybackParameters().speed, 0f);
        assertEquals(View.VISIBLE, a.findViewById(R.id.speed_hold).getVisibility());
        touch(layer, MotionEvent.ACTION_UP, x, y, t);
        idle();
        assertFalse(a.isHoldingFast());
        assertEquals(1f, p.getPlaybackParameters().speed, 0f);
        assertEquals(View.GONE, a.findViewById(R.id.speed_hold).getVisibility());

        // Paused: no 2x.
        p.setPlayWhenReady(false);
        t = SystemClock.uptimeMillis();
        touch(layer, MotionEvent.ACTION_DOWN, x, y, t);
        ShadowAdvance.by(800);
        assertFalse(a.isHoldingFast());
        touch(layer, MotionEvent.ACTION_UP, x, y, t);
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void zoomResetPillAndCastButtonHiddenWithoutPlayServices() throws Exception {
        ActivityController<PlayerActivity> ctl = launch(video("Harbour.mp4"));
        PlayerActivity a = ctl.get();
        View reset = a.findViewById(R.id.zoom_reset);
        assertEquals(View.GONE, reset.getVisibility());
        TestUtil.setField(a, "aspectMode", 1);
        java.lang.reflect.Method apply = PlayerActivity.class.getDeclaredMethod("applyAspect", boolean.class);
        apply.setAccessible(true);
        apply.invoke(a, false);
        java.lang.reflect.Method show = PlayerActivity.class.getDeclaredMethod("setControlsVisible", boolean.class);
        show.setAccessible(true);
        show.invoke(a, true);
        assertEquals(View.VISIBLE, reset.getVisibility());
        reset.performClick();
        assertEquals(0, field(a, "aspectMode"));
        assertEquals(View.GONE, reset.getVisibility());

        // Cast SDK unavailable (no Play services here): no Cast button, no banner.
        assertEquals(View.GONE, a.findViewById(R.id.btn_cast).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.cast_banner).getVisibility());
        assertFalse(CastSupport.isAvailable(a));
        ctl.pause().stop().destroy();
    }

    // ================================================================== Cast: server

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }

    @Test
    public void mediaServerServesRangesWithTokenAndStops() throws Exception {
        byte[] data = new byte[300_000];
        new Random(1).nextBytes(data);
        File f = tmp.newFile("clip.mp4");
        Files.write(f.toPath(), data);
        LocalMediaServer s = LocalMediaServer.start(InetAddress.getLoopbackAddress(), true);
        try {
            String url = s.register(new LocalMediaServer.FileSource(f, "video/mp4"), "My clip (1).mp4");
            assertTrue(url, url.contains("/" + s.token() + "/"));
            assertTrue(url, url.endsWith("/My_clip__1_.mp4"));
            assertTrue("long random token", s.token().length() >= 40);

            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            assertEquals(200, c.getResponseCode());
            assertEquals("video/mp4", c.getContentType());
            assertEquals("bytes", c.getHeaderField("Accept-Ranges"));
            assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"));
            assertArrayEquals(data, readAll(c.getInputStream()));

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Range", "bytes=1000-1999");
            assertEquals(206, c.getResponseCode());
            assertEquals("bytes 1000-1999/300000", c.getHeaderField("Content-Range"));
            assertArrayEquals(Arrays.copyOfRange(data, 1000, 2000), readAll(c.getInputStream()));

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Range", "bytes=-500");
            assertEquals(206, c.getResponseCode());
            assertArrayEquals(Arrays.copyOfRange(data, 299_500, 300_000), readAll(c.getInputStream()));

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Range", "bytes=299990-");
            assertEquals(206, c.getResponseCode());
            assertEquals(10, readAll(c.getInputStream()).length);

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Range", "bytes=400000-");
            assertEquals(416, c.getResponseCode());
            assertEquals("bytes */300000", c.getHeaderField("Content-Range"));

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            assertEquals(200, c.getResponseCode());
            assertEquals("300000", c.getHeaderField("Content-Length"));

            String wrongToken = url.replace(s.token(), s.token().substring(1) + "A");
            c = (HttpURLConnection) new URL(wrongToken).openConnection();
            assertEquals(404, c.getResponseCode());
            String otherId = url.replaceAll("/" + s.token() + "/[^/]+/", "/" + s.token() + "/zz/");
            c = (HttpURLConnection) new URL(otherId).openConnection();
            assertEquals(404, c.getResponseCode());
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("DELETE");
            assertEquals(405, c.getResponseCode());

            // Keep-alive: two requests on one connection.
            URL u = new URL(url);
            try (Socket sock = new Socket(u.getHost(), u.getPort())) {
                OutputStream out = sock.getOutputStream();
                String req = "GET " + u.getPath() + " HTTP/1.1\r\nHost: x\r\nRange: bytes=0-9\r\n\r\n";
                out.write((req + req).getBytes(StandardCharsets.US_ASCII));
                out.flush();
                sock.setSoTimeout(5000);
                InputStream in = sock.getInputStream();
                String all = readUntilCount(in, "HTTP/1.1 206", 2);
                assertTrue(all, all.contains("Connection: keep-alive"));
            }

            s.unregisterAll();
            assertEquals(0, s.registeredCount());
            c = (HttpURLConnection) new URL(url).openConnection();
            assertEquals("unregistered", 404, c.getResponseCode());
        } finally {
            s.stop();
        }
        assertFalse(s.isRunning());
        try {
            new Socket(InetAddress.getLoopbackAddress(), s.port()).close();
            fail("server still accepting");
        } catch (java.io.IOException expected) {
        }
    }

    private static String readUntilCount(InputStream in, String marker, int count) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            sb.append((char) c);
            int n = 0, idx = 0;
            while ((idx = sb.indexOf(marker, idx)) >= 0) {
                n++;
                idx += marker.length();
            }
            if (n >= count && sb.toString().endsWith("\r\n\r\n")) {
                // read the 10-byte body of the second response
                for (int i = 0; i < 10; i++) sb.append((char) in.read());
                return sb.toString();
            }
        }
        return sb.toString();
    }

    @Test
    public void rangeParsing() {
        assertNull(LocalMediaServer.parseRange(null, 100));
        assertNull(LocalMediaServer.parseRange("items=0-1", 100));
        assertArrayEquals(new long[]{0, 99}, LocalMediaServer.parseRange("bytes=0-", 100));
        assertArrayEquals(new long[]{10, 99}, LocalMediaServer.parseRange("bytes=10-500", 100));
        assertArrayEquals(new long[]{90, 99}, LocalMediaServer.parseRange("bytes=-10", 100));
        assertArrayEquals(new long[]{0, 99}, LocalMediaServer.parseRange("bytes=-1000", 100));
        assertArrayEquals(new long[]{5, 6}, LocalMediaServer.parseRange("bytes=5-6, 20-30", 100));
        assertArrayEquals(new long[]{-1, -1}, LocalMediaServer.parseRange("bytes=100-", 100));
        assertArrayEquals(new long[]{-1, -1}, LocalMediaServer.parseRange("bytes=50-10", 100));
        assertTrue(LocalMediaServer.isLocalClient(InetAddress.getLoopbackAddress()));
    }

    @Test
    public void srtBecomesWebVtt() {
        String vtt = SubtitleConvert.toVtt("\uFEFF1\r\n00:00:01,000 --> 00:00:04,5\r\nHello\r\n\r\n2\r\n1:02:03,250 --> 1:02:05,000\r\nBye\r\n");
        assertTrue(vtt, vtt.startsWith("WEBVTT\n\n"));
        assertTrue(vtt, vtt.contains("00:00:01.000 --> 00:00:04.500"));
        assertTrue(vtt, vtt.contains("01:02:03.250 --> 01:02:05.000"));
        assertFalse(vtt.contains("\r"));
        assertEquals("WEBVTT\n\n00:01.000 --> 00:02.000\nHi", SubtitleConvert.toVtt("WEBVTT\n\n00:01.000 --> 00:02.000\nHi"));
        assertTrue(SubtitleConvert.supported("Movie.SRT"));
        assertTrue(SubtitleConvert.supported("a.vtt"));
        assertFalse(SubtitleConvert.supported("a.ass"));
    }

    @Test
    public void wifiAddressPicking() throws Exception {
        InetAddress lan = InetAddress.getByName("192.168.1.23");
        InetAddress pub = InetAddress.getByName("81.2.69.160");
        InetAddress v6 = InetAddress.getByName("fe80::1");
        assertEquals(lan, WifiAddress.pick(Arrays.asList(v6, pub, lan)));
        assertEquals(pub, WifiAddress.pick(Arrays.asList(v6, pub)));
        assertNull(WifiAddress.pick(Arrays.asList(v6, InetAddress.getLoopbackAddress())));
        assertTrue(WifiAddress.isWifiLikeName("wlan0"));
        assertTrue(WifiAddress.isWifiLikeName("swlan0"));
        assertFalse(WifiAddress.isWifiLikeName("rmnet_data0"));
        assertFalse(WifiAddress.isWifiLikeName("tun0"));
    }

    // ================================================================== Cast: flow

    /** Fake TV: records loads and accepts them. */
    static final class FakeRemote implements Caster.Remote {
        final List<MediaLoadRequestData> loads = new ArrayList<>();
        boolean connected = true;

        @Override public boolean isConnected() {
            return connected;
        }

        @Override public String deviceName() {
            return "Living Room TV";
        }

        @Override public void load(MediaLoadRequestData request, Caster.LoadCallback cb) {
            loads.add(request);
            cb.done(true, null);
        }

        @Override public void endSession() {
            connected = false;
        }
    }

    private AppCompatActivity themedActivity(ActivityController<AppCompatActivity> ctl) {
        ctl.get().setTheme(R.style.Theme_VideoPlayer);
        ctl.setup();
        return ctl.get();
    }

    @Test
    public void privateVideoWarnsThenStreamsDecryptedAndHandsBack() throws Exception {
        byte[] plain = new byte[250_000];
        new Random(9).nextBytes(plain);
        File src = tmp.newFile("Secret.mp4");
        Files.write(src.toPath(), plain);
        Vault.Entry e = Vault.importFrom(app, Uri.fromFile(src), "Secret.mp4", "video/mp4", -1, null);
        e.durationMs = 120_000;
        assertTrue(e.enc);
        PrivateResume.save(app, e.file, 30_000, 120_000);

        FakeRemote tv = new FakeRemote();
        Caster.testRemote = tv;
        Caster.testBindAddress = InetAddress.getLoopbackAddress();
        AtomicReference<long[]> handed = new AtomicReference<>();
        Caster.Listener l = new Caster.Listener() {
            @Override public void onCastHandedBack(String key, boolean isPrivate, long positionMs) {
                if (key.equals(e.file) && isPrivate) handed.set(new long[]{positionMs});
            }
        };
        Caster.get().addListener(l);
        ActivityController<AppCompatActivity> ctl = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity a = themedActivity(ctl);

        Caster.get().castPrivateVideo(a, e);
        idle();
        AlertDialog warn = Caster.lastWarning;
        assertNotNull("asks first", warn);
        assertTrue(warn.isShowing());
        TextView msg = warn.findViewById(android.R.id.message);
        assertTrue(msg.getText().toString(), msg.getText().toString().contains("visible on the TV (Living Room TV)"));
        assertTrue("nothing sent before confirming", tv.loads.isEmpty());
        warn.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()), await(() -> !tv.loads.isEmpty(), 5000));

        MediaInfo mi = tv.loads.get(0).getMediaInfo();
        assertEquals("Private video", mi.getMetadata().getString(MediaMetadata.KEY_TITLE));
        assertTrue("no poster for private videos", mi.getMetadata().getImages().isEmpty());
        assertEquals(30_000, tv.loads.get(0).getCurrentTime());
        assertTrue(Caster.get().isCasting(e.file));
        String url = mi.getContentId();
        assertTrue(url, url.startsWith("http://127.0.0.1:"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        assertArrayEquals("decrypted on the fly", plain, readAll(c.getInputStream()));
        c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Range", "bytes=100000-100099");
        assertEquals(206, c.getResponseCode());
        assertArrayEquals(Arrays.copyOfRange(plain, 100_000, 100_100), readAll(c.getInputStream()));

        // TV disconnects at 1:15: resume point saved, listener told, server gone.
        Caster.get().onRemoteProgress(75_000);
        LocalMediaServer server = Caster.get().server();
        assertNotNull(server);
        Caster.get().handleSessionEnded(a);
        idle();
        assertFalse(server.isRunning());
        assertNull(Caster.get().server());
        assertEquals(75_000, PrivateResume.get(app, e.file)[0]);
        assertNotNull(handed.get());
        assertEquals(75_000, handed.get()[0]);
        assertFalse(Caster.get().isCasting(e.file));
        assertEquals(WatchHistory.IN_PROGRESS, WatchHistory.vault(app).state(e.file));
        try {
            HttpURLConnection dead = (HttpURLConnection) new URL(url).openConnection();
            dead.setConnectTimeout(1000);
            dead.getResponseCode();
            fail("server should be stopped");
        } catch (java.io.IOException expected) {
        }
        Caster.get().removeListener(l);
        ctl.pause().stop().destroy();
        Vault.delete(app, e);
    }

    @Test
    public void cancellingTheWarningSendsNothing() throws Exception {
        File src = tmp.newFile("Secret2.mp4");
        Files.write(src.toPath(), new byte[50_000]);
        Vault.Entry e = Vault.importFrom(app, Uri.fromFile(src), "Secret2.mp4", "video/mp4", -1, null);
        FakeRemote tv = new FakeRemote();
        Caster.testRemote = tv;
        Caster.testBindAddress = InetAddress.getLoopbackAddress();
        ActivityController<AppCompatActivity> ctl = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity a = themedActivity(ctl);
        Caster.get().castPrivateVideo(a, e);
        idle();
        Caster.lastWarning.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        Thread.sleep(200);
        idle();
        assertTrue(tv.loads.isEmpty());
        assertNull("no server started", Caster.get().server());
        ctl.pause().stop().destroy();
        Vault.delete(app, e);
    }

    @Test
    public void galleryVideoWithSubtitlesAndNetworkStream() throws Exception {
        File dir = tmp.newFolder("Movies");
        byte[] data = new byte[80_000];
        new Random(3).nextBytes(data);
        File v = new File(dir, "Nosferatu.mp4");
        Files.write(v.toPath(), data);
        Files.write(new File(dir, "Nosferatu.srt").toPath(),
                "1\n00:00:01,000 --> 00:00:04,000\nHello\n".getBytes(StandardCharsets.UTF_8));
        FakeRemote tv = new FakeRemote();
        Caster.testRemote = tv;
        Caster.testBindAddress = InetAddress.getLoopbackAddress();
        ActivityController<AppCompatActivity> ctl = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity a = themedActivity(ctl);

        Caster.Request r = new Caster.Request();
        r.uri = Uri.fromFile(v);
        r.title = "Nosferatu";
        r.key = "file-key";
        r.positionMs = 12_000;
        r.durationMs = 90_000;
        r.subtitleUri = Uri.fromFile(new File(dir, "Nosferatu.srt"));
        r.subtitleName = "Nosferatu.srt";
        Caster.get().cast(a, r, null);
        assertNull("no warning for gallery videos", Caster.lastWarning);
        assertTrue(String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()), await(() -> !tv.loads.isEmpty(), 5000));
        MediaInfo mi = tv.loads.get(0).getMediaInfo();
        assertEquals("Nosferatu", mi.getMetadata().getString(MediaMetadata.KEY_TITLE));
        assertEquals("video/mp4", mi.getContentType());
        assertEquals(1, mi.getMediaTracks().size());
        assertEquals("text/vtt", mi.getMediaTracks().get(0).getContentType());
        assertArrayEquals(new long[]{1}, tv.loads.get(0).getActiveTrackIds());
        HttpURLConnection c = (HttpURLConnection) new URL(mi.getMediaTracks().get(0).getContentId()).openConnection();
        String vtt = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(vtt, vtt.startsWith("WEBVTT") && vtt.contains("00:00:01.000 --> 00:00:04.000"));
        c = (HttpURLConnection) new URL(mi.getContentId()).openConnection();
        assertArrayEquals(data, readAll(c.getInputStream()));

        Caster.get().onRemoteProgress(40_000);
        Caster.get().handleSessionEnded(a);
        idle();
        assertEquals(40_000, Prefs.getResume(app, "file-key")[0]);

        // Network streams go to the TV by their own URL (no local server).
        tv.loads.clear();
        Caster.Request n = new Caster.Request();
        n.uri = Uri.parse("https://example.com/live/stream.m3u8");
        n.title = "Stream";
        n.key = n.uri.toString();
        Caster.get().cast(a, n, null);
        assertTrue(String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()), await(() -> !tv.loads.isEmpty(), 5000));
        assertEquals("https://example.com/live/stream.m3u8", tv.loads.get(0).getMediaInfo().getContentId());
        assertEquals("application/x-mpegURL", tv.loads.get(0).getMediaInfo().getContentType());
        assertNull(Caster.get().server());
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void playerHandsOffToTheTvAndBack() throws Exception {
        FakeRemote tv = new FakeRemote();
        Caster.testRemote = tv;
        Caster.testBindAddress = InetAddress.getLoopbackAddress();
        ActivityController<PlayerActivity> ctl = launch(video("Harbour.mp4"));
        PlayerActivity a = ctl.get();
        assertTrue(await(() -> field(a, "currentKey") != null, 5000));
        TextView banner = a.findViewById(R.id.cast_banner);
        assertEquals("connected: offer to play there", View.VISIBLE, banner.getVisibility());
        assertTrue(banner.getText().toString(), banner.getText().toString().startsWith("Connected to Living Room TV"));
        banner.performClick();
        assertTrue(String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()), await(() -> !tv.loads.isEmpty(), 5000));
        idle();
        ExoPlayer p = (ExoPlayer) field(a, "player");
        assertFalse("phone paused while the TV plays", p.getPlayWhenReady());
        assertTrue(banner.getText().toString(), banner.getText().toString().startsWith("Playing on Living Room TV"));
        String key = (String) field(a, "currentKey");
        assertTrue(Caster.get().isCasting(key));

        tv.connected = false;
        Caster.get().onRemoteProgress(45_000);
        Caster.get().handleSessionEnded(a);
        idle();
        assertEquals(View.GONE, banner.getVisibility());
        assertEquals(45_000, Prefs.getResume(app, key)[0]);
        ctl.pause().stop().destroy();
    }
}
