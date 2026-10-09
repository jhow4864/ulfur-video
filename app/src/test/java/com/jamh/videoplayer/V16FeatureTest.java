package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.OUT;
import static com.jamh.videoplayer.PreviewScreenshotTest.fakeFrame;
import static com.jamh.videoplayer.PreviewScreenshotTest.renderToBitmap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.collect.ImmutableList;

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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** 1.6: bookmarks, external subtitles, remembered speed (stable per-video key). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class V16FeatureTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        VideoKey.clearCache();
    }

    private File video(File dir, String name, int bytes, long seed) throws Exception {
        dir.mkdirs();
        byte[] b = new byte[bytes];
        new Random(seed).nextBytes(b);
        File f = new File(dir, name);
        Files.write(f.toPath(), b);
        return f;
    }

    // ------------------------------------------------------------------ stable key

    @Test
    public void keyFollowsTheContentNotTheLocation() throws Exception {
        File a = video(tmp.newFolder("Camera"), "Harbour timelapse.mp4", 400_000, 7);
        File moved = new File(tmp.newFolder("UlfurVideo"), "renamed copy.mp4");
        Files.copy(a.toPath(), moved.toPath());
        String k1 = VideoKey.of(app, Uri.fromFile(a), -1, -1, null);
        String k2 = VideoKey.of(app, Uri.fromFile(moved), -1, -1, null);
        assertNotNull(k1);
        assertTrue(k1, k1.startsWith(VideoKey.PREFIX_CONTENT));
        assertEquals("same bytes in another folder / under another name", k1, k2);

        // Change one byte in the tail (re-encode / different file of the same size): different key.
        byte[] bytes = Files.readAllBytes(a.toPath());
        bytes[bytes.length - 10] ^= 0x55;
        File other = new File(tmp.getRoot(), "other.mp4");
        Files.write(other.toPath(), bytes);
        assertNotEquals(k1, VideoKey.of(app, Uri.fromFile(other), -1, -1, null));

        // Files smaller than one chunk still work.
        File small = video(tmp.getRoot(), "tiny.mp4", 5_000, 3);
        File small2 = new File(tmp.getRoot(), "tiny2.mp4");
        Files.copy(small.toPath(), small2.toPath());
        assertEquals(VideoKey.of(app, Uri.fromFile(small), -1, -1, null),
                VideoKey.of(app, Uri.fromFile(small2), -1, -1, null));
    }

    @Test
    public void privateCopyServedThroughFileProviderHasTheSameKey() throws Exception {
        // Move to Private copies the file into filesDir/vault and plays it through our FileProvider.
        File original = video(tmp.newFolder("Movies"), "Wedding speech.mp4", 300_000, 11);
        File vaultDir = new File(app.getFilesDir(), Vault.DIR);
        vaultDir.mkdirs();
        File inVault = new File(vaultDir, "a1b2c3.mp4");
        Files.copy(original.toPath(), inVault.toPath());
        Uri vaultUri = FileProvider.getUriForFile(app, app.getPackageName() + Vault.AUTHORITY_SUFFIX, inVault);
        assertTrue(Vault.isVaultUri(vaultUri));
        String plain = VideoKey.of(app, Uri.fromFile(original), -1, -1, null);
        // On a phone the vault URI is read through FileProvider's seekable descriptor, which hashes the
        // same bytes. (Robolectric on JDK 17 can't close a ParcelFileDescriptor without --add-opens,
        // so here the vault file is hashed through the same channel code directly.)
        String priv;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(inVault, "r")) {
            priv = VideoKey.fromChannel(raf.getChannel(), raf.length());
        }
        assertEquals(plain, priv);
        // Offset descriptors (AssetFileDescriptor start offset) hash only the video's own bytes.
        File packed = new File(tmp.getRoot(), "packed.bin");
        byte[] body = Files.readAllBytes(original.toPath());
        byte[] withHeader = new byte[body.length + 4096];
        System.arraycopy(body, 0, withHeader, 4096, body.length);
        Files.write(packed.toPath(), withHeader);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(packed, "r")) {
            assertEquals(plain, VideoKey.fromChannel(raf.getChannel(), 4096, body.length));
        }
    }

    @Test
    public void fallbackKeys() {
        assertEquals(VideoKey.metaKey(1234, 61_500, "Film.MP4"), VideoKey.metaKey(1234, 61_900, "film.mkv"));
        assertNull(VideoKey.metaKey(-1, 1000, "x.mp4"));
        assertEquals("url:https://example.com/a.m3u8",
                VideoKey.of(app, Uri.parse("https://example.com/a.m3u8"), -1, -1, null));
        String missing = VideoKey.of(app, Uri.parse("file:///nope/gone.mp4"), 5000, 2000, "gone.mp4");
        assertEquals(VideoKey.metaKey(5000, 2000, "gone.mp4"), missing);
        assertEquals("uri:file:///nope/x.mp4", VideoKey.of(app, Uri.parse("file:///nope/x.mp4"), -1, -1, null));
    }

    // ------------------------------------------------------------------ bookmark store

    @Test
    public void bookmarksAddMergeRenameDeleteUndoAndStaySorted() {
        BookmarkStore s = new BookmarkStore(app);
        String k = "v1:abc";
        assertTrue(s.list(k).isEmpty());
        BookmarkStore.Bookmark b2 = s.add(k, 90_000, "  The   twist  ");
        BookmarkStore.Bookmark b1 = s.add(k, 12_000, null);
        assertNotNull(b1);
        assertEquals("The twist", b2.name);
        assertEquals("Bookmark at 0:12", b1.label());
        // Within a second of an existing one: no duplicate, but a name fills in an unnamed one.
        BookmarkStore.Bookmark again = s.add(k, 12_400, "Opening");
        assertEquals(b1.id, again.id);
        assertEquals("Opening", again.name);
        List<BookmarkStore.Bookmark> l = s.list(k);
        assertEquals(2, l.size());
        assertEquals(12_000, l.get(0).timeMs);
        assertEquals(90_000, l.get(1).timeMs);

        assertEquals("Big reveal", s.rename(k, b2.id, "Big reveal").name);
        assertTrue(s.delete(k, b1.id));
        assertEquals(1, s.list(k).size());
        s.restore(k, l.get(0));
        assertEquals(2, s.list(k).size());
        assertEquals("other videos are separate", 0, s.list("v1:other").size());
        assertTrue(s.list(null).isEmpty());
        assertNull(s.add(null, 1000, null));

        String longName = new String(new char[200]).replace('\0', 'x');
        assertEquals(BookmarkStore.MAX_NAME, BookmarkStore.cleanName(longName).length());

        // A corrupt value doesn't crash the player.
        app.getSharedPreferences(BookmarkStore.FILE, 0).edit().putString("v1:bad", "{nope").commit();
        assertTrue(s.list("v1:bad").isEmpty());
        // Persisted: a new store instance sees the same data.
        assertEquals(2, new BookmarkStore(app).list(k).size());
    }

    @Test
    public void tickPositionsFollowTheTrack() {
        assertEquals(100f, BookmarkTicksView.tickX(0, 60_000, 100, 700), 0.01);
        assertEquals(400f, BookmarkTicksView.tickX(30_000, 60_000, 100, 700), 0.01);
        assertEquals(700f, BookmarkTicksView.tickX(60_000, 60_000, 100, 700), 0.01);
        assertTrue(Float.isNaN(BookmarkTicksView.tickX(61_000, 60_000, 100, 700)));
        assertTrue(Float.isNaN(BookmarkTicksView.tickX(1_000, 0, 100, 700)));
    }

    // ------------------------------------------------------------------ speed memory

    @Test
    public void speedIsRememberedPerVideoOnTopOfTheGlobalDefault() {
        assertEquals(1f, PlaybackMemory.defaultSpeed(app), 0f);
        assertNull(PlaybackMemory.videoSpeed(app, "v1:a"));
        PlaybackMemory.setVideoSpeed(app, "v1:a", 1.5f);
        assertEquals(1.5f, PlaybackMemory.startSpeed(app, "v1:a"), 0f);
        assertEquals(1f, PlaybackMemory.startSpeed(app, "v1:b"), 0f);
        PlaybackMemory.setDefaultSpeed(app, 1.25f);
        assertEquals("own speed kept", 1.5f, PlaybackMemory.startSpeed(app, "v1:a"), 0f);
        assertEquals("others follow the default", 1.25f, PlaybackMemory.startSpeed(app, "v1:b"), 0f);
        // Picking the default again forgets the per-video speed.
        PlaybackMemory.setVideoSpeed(app, "v1:a", 1.25f);
        assertNull(PlaybackMemory.videoSpeed(app, "v1:a"));
        assertEquals(PlaybackMemory.MAX_SPEED, PlaybackMemory.sanitizeSpeed(99f), 0f);
        assertEquals(1f, PlaybackMemory.sanitizeSpeed(Float.NaN), 0f);
        PlaybackMemory.setVideoSpeed(app, null, 2f); // no key: ignored
    }

    @Test
    public void subtitleSettingsDefaultsAndSizes() {
        assertTrue(PlaybackMemory.subtitlesOn(app));
        assertTrue(PlaybackMemory.autoLoadSubtitles(app));
        assertEquals(PlaybackMemory.SUB_SIZE_DEFAULT, PlaybackMemory.subtitleSize(app));
        assertEquals("Medium is the 1.5.0 size", 0.0533f * 1.1f,
                PlaybackMemory.subtitleFraction(PlaybackMemory.SUB_SIZE_DEFAULT), 1e-6);
        assertTrue(PlaybackMemory.subtitleFraction(3) > PlaybackMemory.subtitleFraction(2));
        PlaybackMemory.setSubtitleSize(app, 9);
        assertEquals(PlaybackMemory.SUB_SIZE_DEFAULT, PlaybackMemory.subtitleSize(app));
    }

    // ------------------------------------------------------------------ subtitle finder

    @Test
    public void picksTheSameNameSubtitle() {
        List<String> names = Arrays.asList("Film.mkv", "Film.en.srt", "Film.vtt", "Other.srt", "Film.SRT");
        assertEquals("Film.SRT", SubtitleFinder.pick("Film.mkv", names));
        assertEquals("Film.vtt", SubtitleFinder.pick("Film.mkv", Arrays.asList("Film.vtt", "Film.en.srt")));
        assertEquals("Film.en.srt", SubtitleFinder.pick("Film.mkv", Arrays.asList("Film.en.srt", "Film.english.srt")));
        assertNull(SubtitleFinder.pick("Film.mkv", Arrays.asList("Films.srt", "Film.txt", "Film2.srt")));
        assertNull(SubtitleFinder.pick(null, names));
        assertEquals("Film", SubtitleFinder.baseName("/sdcard/Movies/Film.mkv"));
    }

    @Test
    public void findsSiblingFileForFileUris() throws Exception {
        File dir = tmp.newFolder("Movies");
        File v = video(dir, "Harbour timelapse.mp4", 1000, 1);
        assertNull(SubtitleFinder.find(app, Uri.fromFile(v), null));
        Files.write(new File(dir, "Harbour timelapse.vtt").toPath(), "WEBVTT\n".getBytes());
        SubtitleFinder.Found f = SubtitleFinder.find(app, Uri.fromFile(v), null);
        assertNotNull(f);
        assertEquals("Harbour timelapse.vtt", f.name);
        assertEquals(Uri.fromFile(new File(dir, "Harbour timelapse.vtt")), f.uri);
        assertNull("streams are skipped", SubtitleFinder.find(app, Uri.parse("https://x.y/Harbour timelapse.mp4"), null));
    }

    // ------------------------------------------------------------------ player integration

    private ActivityController<PlayerActivity> launch(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.fromFile(f));
        i.setClass(app, PlayerActivity.class);
        ActivityController<PlayerActivity> ctl = Robolectric.buildActivity(PlayerActivity.class, i).setup();
        TestUtil.awaitPlayer(ctl.get());
        PlayerActivity a = ctl.get();
        assertTrue("video key resolved", TestUtil.await(() -> TestUtil.field(a, "videoKey") != null, 5000));
        return ctl;
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void bookmarkButtonTicksSheetJumpAndPrivateMove() throws Exception {
        File f = video(tmp.newFolder("Camera"), "Harbour timelapse.mp4", 300_000, 21);
        ActivityController<PlayerActivity> ctl = launch(f);
        PlayerActivity a = ctl.get();
        String key = (String) TestUtil.field(a, "videoKey");
        assertTrue(key.startsWith(VideoKey.PREFIX_CONTENT));

        // Button: bookmark at the current position (0:00 here).
        View btn = a.findViewById(R.id.btn_bookmark);
        assertNotNull(btn);
        btn.performClick();
        TestUtil.idle();
        BookmarkStore store = new BookmarkStore(app);
        assertEquals(1, store.list(key).size());
        BookmarkTicksView ticks = a.findViewById(R.id.bookmark_ticks);
        assertEquals(1, ticks.times().length);

        // Jump: instant keyframe seek (1.4.2 path), exact seeks restored afterwards.
        assertEquals(SeekParameters.CLOSEST_SYNC, PlayerActivity.BOOKMARK_SEEK);
        a.jumpToBookmark(75_000);
        TestUtil.idle();
        ExoPlayer p = (ExoPlayer) TestUtil.field(a, "player");
        assertEquals(75_000, p.getCurrentPosition());
        assertEquals(SeekParameters.EXACT, p.getSeekParameters());

        // Sheet: lists the bookmarks, tap jumps, add button adds at the current position.
        store.add(key, 192_000, "Boats leave");
        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_bookmarks, null);
        a.bindBookmarksSheet(sheet, new Dialog(a));
        RecyclerView list = sheet.findViewById(R.id.bookmarks_list);
        measure(list, 1000);
        assertEquals(2, list.getAdapter().getItemCount());
        assertEquals("Add at 1:15", ((TextView) sheet.findViewById(R.id.bookmarks_add)).getText().toString());
        sheet.findViewById(R.id.bookmarks_add).performClick();
        assertEquals(3, store.list(key).size());
        measure(list, 1000);
        list.findViewHolderForAdapterPosition(2).itemView.performClick(); // "Boats leave" (3:12)
        TestUtil.idle();
        assertEquals(192_000, p.getCurrentPosition());
        assertEquals(3, ticks.times().length);
        ctl.pause().stop().destroy();

        // Moved (copied) somewhere else, e.g. into Private and back out: bookmarks are still there.
        File movedOut = new File(tmp.newFolder("UlfurVideo"), "Harbour timelapse.mp4");
        Files.copy(f.toPath(), movedOut.toPath());
        ActivityController<PlayerActivity> ctl2 = launch(movedOut);
        assertEquals(key, TestUtil.field(ctl2.get(), "videoKey"));
        BookmarkTicksView t2 = ctl2.get().findViewById(R.id.bookmark_ticks);
        assertEquals(3, t2.times().length);
        ctl2.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void speedIsRestoredForTheSameVideoOnly() throws Exception {
        File dir = tmp.newFolder("Movies");
        File f1 = video(dir, "Lecture 1.mp4", 200_000, 1);
        File f2 = video(dir, "Lecture 2.mp4", 200_000, 2);
        ActivityController<PlayerActivity> ctl = launch(f1);
        PlayerActivity a = ctl.get();
        Method m = PlayerActivity.class.getDeclaredMethod("setSpeedForVideo", float.class);
        m.setAccessible(true);
        m.invoke(a, 1.75f);
        ExoPlayer p = (ExoPlayer) TestUtil.field(a, "player");
        assertEquals(1.75f, p.getPlaybackParameters().speed, 0f);
        ctl.pause().stop().destroy();

        ActivityController<PlayerActivity> c2 = launch(f2);
        assertEquals("other video: global default", 1f, (float) TestUtil.field(c2.get(), "speed"), 0f);
        c2.pause().stop().destroy();

        PlaybackMemory.setDefaultSpeed(app, 1.25f);
        ActivityController<PlayerActivity> c3 = launch(f2);
        assertEquals(1.25f, (float) TestUtil.field(c3.get(), "speed"), 0f);
        c3.pause().stop().destroy();

        ActivityController<PlayerActivity> c1 = launch(f1);
        PlayerActivity a1 = c1.get();
        assertTrue(TestUtil.await(() -> (float) TestUtil.field(a1, "speed") == 1.75f, 3000));
        ExoPlayer p1 = (ExoPlayer) TestUtil.field(a1, "player");
        assertEquals(1.75f, p1.getPlaybackParameters().speed, 0f);
        assertEquals("1.75x", ((TextView) a1.findViewById(R.id.btn_speed)).getText().toString());
        c1.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void sameNameSubtitleIsLoadedAndSheetControlsWork() throws Exception {
        File dir = tmp.newFolder("Movies");
        File v = video(dir, "Nosferatu (1922).mp4", 200_000, 5);
        Files.write(new File(dir, "Nosferatu (1922).srt").toPath(),
                "1\n00:00:01,000 --> 00:00:04,000\nHello\n".getBytes());
        ActivityController<PlayerActivity> ctl = launch(v);
        PlayerActivity a = ctl.get();
        assertTrue(TestUtil.await(() -> "Nosferatu (1922).srt".equals(TestUtil.field(a, "externalSubName")), 3000));
        assertEquals(Uri.fromFile(new File(dir, "Nosferatu (1922).srt")), TestUtil.field(a, "externalSubUri"));

        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_subtitles, null);
        a.bindSubtitlesSheet(sheet, null);
        assertEquals("Playing Nosferatu (1922).srt",
                ((TextView) sheet.findViewById(R.id.subs_load_value)).getText().toString());
        MaterialSwitch on = sheet.findViewById(R.id.switch_subs_on);
        assertTrue(on.isChecked());
        on.setChecked(false);
        assertFalse(PlaybackMemory.subtitlesOn(app));
        ExoPlayer p = (ExoPlayer) TestUtil.field(a, "player");
        assertTrue(p.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));

        ChipGroup sizes = sheet.findViewById(R.id.subs_size_group);
        assertEquals(4, sizes.getChildCount());
        ((Chip) sizes.getChildAt(2)).setChecked(true);
        assertEquals(2, PlaybackMemory.subtitleSize(app));
        PlayerView pv = a.findViewById(R.id.player_view);
        assertNotNull(pv.getSubtitleView());
        ctl.pause().stop().destroy();

        // Off survives a new player.
        ActivityController<PlayerActivity> c2 = launch(v);
        ExoPlayer p2 = (ExoPlayer) TestUtil.field(c2.get(), "player");
        assertTrue(p2.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
        c2.pause().stop().destroy();

        // Auto-load switched off: nothing is loaded.
        PlaybackMemory.setAutoLoadSubtitles(app, false);
        ActivityController<PlayerActivity> c3 = launch(v);
        TestUtil.await(() -> false, 300);
        assertNull(TestUtil.field(c3.get(), "externalSubName"));
        c3.pause().stop().destroy();
    }

    private static void measure(View v, int w) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));
        v.layout(0, 0, w, v.getMeasuredHeight());
    }

    // ------------------------------------------------------------------ previews

    private FrameLayout dress(PlayerActivity a, String title, int seed) {
        FrameLayout root = a.findViewById(R.id.player_root);
        ImageView frame = new ImageView(a);
        frame.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.setImageBitmap(fakeFrame(1600, 900, seed));
        root.addView(frame, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((TextView) a.findViewById(R.id.title)).setText(title);
        a.findViewById(R.id.controls).setVisibility(View.VISIBLE);
        a.findViewById(R.id.controls).setAlpha(1f);
        a.findViewById(R.id.buffering).setVisibility(View.GONE);
        return root;
    }

    private static void progress(PlayerActivity a, long posMs, long durMs) {
        SeekBar sb = a.findViewById(R.id.seekbar);
        sb.setMax((int) (durMs / 100));
        sb.setProgress((int) (posMs / 100));
        sb.setSecondaryProgress((int) (posMs / 100) + 600);
        ((TextView) a.findViewById(R.id.position)).setText(Fmt.time(posMs));
        ((TextView) a.findViewById(R.id.duration)).setText(Fmt.time(durMs));
    }

    private static Bitmap withSheet(PlayerActivity a, Bitmap base, View sheet, int w, int h) {
        FrameLayout card = new FrameLayout(a);
        GradientDrawable bg = new GradientDrawable();
        float r = 28 * a.getResources().getDisplayMetrics().density;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(MaterialColors.getColor(a, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.DKGRAY));
        card.setBackground(bg);
        card.addView(sheet);
        int cw = Math.min(w, (int) (640 * a.getResources().getDisplayMetrics().density));
        card.measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h - (int) (24 * a.getResources().getDisplayMetrics().density), View.MeasureSpec.AT_MOST));
        card.layout(0, 0, cw, card.getMeasuredHeight());
        Canvas c = new Canvas(base);
        c.drawColor(0x88000000);
        c.save();
        c.translate((w - cw) / 2f, h - card.getMeasuredHeight());
        card.draw(c);
        c.restore();
        return base;
    }

    private static void write(Bitmap b, String name) throws Exception {
        OUT.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(OUT, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void previewBookmarks() throws Exception {
        File f = video(tmp.newFolder("Movies"), "Planet Earth III S01E01.mp4", 200_000, 8);
        ActivityController<PlayerActivity> ctl = launch(f);
        PlayerActivity a = ctl.get();
        String key = (String) TestUtil.field(a, "videoKey");
        BookmarkStore s = new BookmarkStore(app);
        long dur = 52 * 60_000L + 14_000;
        s.add(key, 2 * 60_000L + 41_000, "Snow leopard hunt");
        s.add(key, 11 * 60_000L + 5_000, null);
        s.add(key, 23 * 60_000L + 30_000, "Penguin chicks");
        s.add(key, 38 * 60_000L + 2_000, "Night-time reef");
        s.add(key, 47 * 60_000L + 50_000, "Behind the scenes");
        FrameLayout root = dress(a, "Planet Earth III S01E01", 7);
        progress(a, 19 * 60_000L + 12_000, dur);
        List<BookmarkStore.Bookmark> l = s.list(key);
        long[] t = new long[l.size()];
        for (int i = 0; i < t.length; i++) t[i] = l.get(i).timeMs;
        ((BookmarkTicksView) a.findViewById(R.id.bookmark_ticks)).setBookmarks(t, dur);
        int w = 2340, h = 1080;
        write(renderToBitmap(root, w, h), "player_bookmarks.png");

        // Empty state.
        View empty = a.getLayoutInflater().inflate(R.layout.sheet_bookmarks, null);
        TestUtil.setField(a, "videoKey", "v1:empty");
        a.bindBookmarksSheet(empty, new Dialog(a));
        assertEquals(View.VISIBLE, empty.findViewById(R.id.bookmarks_empty).getVisibility());
        write(withSheet(a, renderToBitmap(root, w, h), empty, w, h), "player_bookmarks_empty.png");
        TestUtil.setField(a, "videoKey", key);

        // Player menu row.
        View menu = a.getLayoutInflater().inflate(R.layout.sheet_player_menu, null);
        a.bindMenuSheet(menu, new Dialog(a));
        assertEquals("5 bookmarks in this video", ((TextView) menu.findViewById(R.id.bookmarks_value)).getText().toString());
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void previewBookmarksSheet() throws Exception {
        File f = video(tmp.newFolder("Movies"), "Planet Earth III S01E01.mp4", 200_000, 8);
        ActivityController<PlayerActivity> ctl = launch(f);
        PlayerActivity a = ctl.get();
        String key = (String) TestUtil.field(a, "videoKey");
        BookmarkStore s = new BookmarkStore(app);
        long dur = 52 * 60_000L + 14_000;
        s.add(key, 2 * 60_000L + 41_000, "Snow leopard hunt");
        s.add(key, 11 * 60_000L + 5_000, null);
        s.add(key, 23 * 60_000L + 30_000, "Penguin chicks");
        s.add(key, 38 * 60_000L + 2_000, "Night-time reef");
        s.add(key, 47 * 60_000L + 50_000, "Behind the scenes");
        FrameLayout root = dress(a, "Planet Earth III S01E01", 7);
        progress(a, 19 * 60_000L + 12_000, dur);
        ExoPlayer p = (ExoPlayer) TestUtil.field(a, "player");
        p.seekTo(19 * 60_000L + 12_000); // the "Add at" button shows the playing position
        TestUtil.idle();
        int w = 1080, h = 2340;
        Bitmap base = renderToBitmap(root, w, h);
        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_bookmarks, null);
        a.bindBookmarksSheet(sheet, new Dialog(a));
        assertEquals("Add at 19:12", ((TextView) sheet.findViewById(R.id.bookmarks_add)).getText().toString());
        write(withSheet(a, base, sheet, w, h), "player_bookmarks_sheet.png");

        // Rename prompt (from ⋮ › Rename or the snackbar's "Name").
        Dialog d = BookmarksSheet.showNameDialog(a, "Rename bookmark", "Penguin chicks", "23:30", n -> { });
        TestUtil.idle();
        assertNotNull(d);
        View dv = d.getWindow().getDecorView();
        Bitmap db = renderToBitmap(root, w, h);
        Canvas c = new Canvas(db);
        c.drawColor(0x99000000);
        int dw = w - 120;
        dv.measure(View.MeasureSpec.makeMeasureSpec(dw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
        dv.layout(0, 0, dw, dv.getMeasuredHeight());
        c.translate(60, (h - dv.getMeasuredHeight()) / 2f);
        dv.draw(c);
        write(db, "player_bookmark_rename.png");
        d.dismiss();
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void previewSubtitlesSheet() throws Exception {
        File dir = tmp.newFolder("Movies");
        File v = video(dir, "Nosferatu (1922).mp4", 200_000, 5);
        Files.write(new File(dir, "Nosferatu (1922).srt").toPath(), "1\n00:00:01,000 --> 00:00:04,000\nHello\n".getBytes());
        PlaybackMemory.setSubtitleSize(app, 2);
        ActivityController<PlayerActivity> ctl = launch(v);
        PlayerActivity a = ctl.get();
        TestUtil.await(() -> TestUtil.field(a, "externalSubName") != null, 3000);
        FrameLayout root = dress(a, "Nosferatu (1922)", 3);
        progress(a, 31 * 60_000L + 4_000, 94 * 60_000L);
        int w = 1080, h = 2340;
        Bitmap base = renderToBitmap(root, w, h);

        // Stand-in tracks (Robolectric doesn't extract real ones): the loaded .srt + two embedded.
        ExoPlayer real = (ExoPlayer) TestUtil.field(a, "player");
        Player fake = new ForwardingPlayer(real) {
            @Override public Tracks getCurrentTracks() {
                return new Tracks(ImmutableList.of(
                        text("Nosferatu (1922).srt", "und", true),
                        text(null, "en", false),
                        text(null, "de", false)));
            }
        };
        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_subtitles, null);
        SubtitleSheet.bind(sheet, null, new SubtitleSheet.Host() {
            @Override public Player player() { return fake; }
            @Override public void setSubtitlesOn(boolean on) { }
            @Override public void setSubtitleSize(int index) { }
            @Override public void pickSubtitleFile() { }
            @Override public String externalSubtitleName() { return "Nosferatu (1922).srt"; }
        });
        LinearLayout tracks = sheet.findViewById(R.id.subs_tracks);
        assertEquals(3, tracks.getChildCount());
        assertEquals("Nosferatu (1922).srt", ((TextView) sheet.findViewById(R.id.subs_on_value)).getText().toString());
        write(withSheet(a, base, sheet, w, h), "player_subtitles_sheet.png");
        ctl.pause().stop().destroy();
    }

    private static Tracks.Group text(String label, String lang, boolean selected) {
        Format f = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLabel(label).setLanguage(lang).build();
        return new Tracks.Group(new TrackGroup(f), false, new int[]{C.FORMAT_HANDLED}, new boolean[]{selected});
    }
}
