package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.OUT;
import static com.jamh.videoplayer.PreviewScreenshotTest.fakeFrame;
import static com.jamh.videoplayer.PreviewScreenshotTest.renderToBitmap;
import static com.jamh.videoplayer.PreviewScreenshotTest.save;
import static com.jamh.videoplayer.TestUtil.await;
import static com.jamh.videoplayer.TestUtil.field;
import static com.jamh.videoplayer.TestUtil.idle;
import static com.jamh.videoplayer.TestUtil.setField;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.C;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** v1.3.0: review fixes, share sheet, shortcuts, orientation, scrub thumbnails, Private videos. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class V13FeatureTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    @org.junit.Before
    public void resetFileProviderCache() throws Exception {
        // FileProvider caches path roots statically; Robolectric gives every test a new data dir.
        java.lang.reflect.Field f = androidx.core.content.FileProvider.class.getDeclaredField("sCache");
        f.setAccessible(true);
        ((java.util.Map<?, ?>) f.get(null)).clear();
    }

    @After
    public void tearDown() {
        VaultLock.lock();
    }

    static void write(Bitmap b, String name) throws Exception {
        OUT.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(OUT, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }

    private ActivityController<PlayerActivity> launchPlayer(Intent i) {
        i.setClass(app, PlayerActivity.class);
        ActivityController<PlayerActivity> ctl = Robolectric.buildActivity(PlayerActivity.class, i).setup();
        TestUtil.awaitPlayer(ctl.get());
        return ctl;
    }

    private static byte[] jpeg(Bitmap b) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, bos);
        return bos.toByteArray();
    }

    // ================================================================== orientation

    @Test
    public void orientationPolicyFollowsThePhoneByDefault() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER,
                PlayerActivity.requestedOrientationFor(false, false, false, false));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER,
                PlayerActivity.requestedOrientationFor(false, false, false, true));
        // Rotation lock without a manual choice: stay exactly as the screen is now.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED,
                PlayerActivity.requestedOrientationFor(true, false, false, false));
        // Rotate button.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                PlayerActivity.requestedOrientationFor(false, true, false, true));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                PlayerActivity.requestedOrientationFor(true, true, false, false));
        // "Auto-rotate to match video" (old behaviour) once the video's shape is known.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                PlayerActivity.requestedOrientationFor(false, false, true, false));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                PlayerActivity.requestedOrientationFor(false, false, true, true));
        // A manual choice wins over the setting.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
                PlayerActivity.requestedOrientationFor(false, true, true, false));
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void playerOpensInCurrentOrientationAndSettingIsOffByDefault() throws Exception {
        assertFalse("Auto-rotate to match video defaults to off", Prefs.autoRotateToVideo(app));
        ActivityController<PlayerActivity> ctl = launchPlayer(new Intent(Intent.ACTION_VIEW,
                Uri.parse("file:///sdcard/Movies/Brighton%20beach%20sunset.mp4")));
        PlayerActivity a = ctl.get();
        assertEquals("no forced landscape", ActivityInfo.SCREEN_ORIENTATION_USER, a.getRequestedOrientation());

        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_player_menu, null);
        a.bindMenuSheet(sheet, new Dialog(a));
        com.google.android.material.materialswitch.MaterialSwitch sw = sheet.findViewById(R.id.switch_auto_rotate);
        assertFalse(sw.isChecked());
        sw.setChecked(true);
        assertTrue(Prefs.autoRotateToVideo(app));
        sw.setChecked(false);
        assertFalse(Prefs.autoRotateToVideo(app));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, a.getRequestedOrientation());

        // Rotation lock keeps the current orientation; unlocking goes back to following the phone.
        a.findViewById(R.id.btn_rotation_lock).performClick();
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, a.getRequestedOrientation());
        a.findViewById(R.id.btn_rotation_lock).performClick();
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, a.getRequestedOrientation());
        // Rotate button: portrait screen → landscape.
        a.findViewById(R.id.btn_rotate).performClick();
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, a.getRequestedOrientation());
        ctl.pause().stop().destroy();
    }

    // ================================================================== P0 buffer tiers

    @Test
    public void bufferTierShrinksForHighBitrateFiles() {
        long budget = 160L * 1024 * 1024;
        assertEquals(PlayerFactory.TIER_UNKNOWN, PlayerFactory.tierForBitrate(-1, budget));
        assertEquals(0, PlayerFactory.tierForBitrate(5_000_000L, budget));       // 5 Mbps phone video
        int t80 = PlayerFactory.tierForBitrate(80_000_000L, budget);             // 80 Mbps 4K60
        int t400 = PlayerFactory.tierForBitrate(400_000_000L, budget);           // ProRes-class
        assertTrue(t80 > 0);
        assertTrue(t400 >= t80);
        int prev = 0;
        for (long br = 1_000_000L; br < 1_000_000_000L; br *= 2) {
            int t = PlayerFactory.tierForBitrate(br, budget);
            assertTrue("tier never decreases as bitrate grows", t >= prev);
            prev = t;
        }
        long b = PlayerFactory.bufferBudgetBytes();
        assertTrue(b >= 48L * 1024 * 1024 && b <= 160L * 1024 * 1024);
    }

    // ================================================================== P1-1 dialogue boost

    @Test
    public void dialogueBoostChannelChangeBeforeFlushDoesNotThrow() throws Exception {
        DialogueBoostProcessor p = new DialogueBoostProcessor(true);
        p.configure(new AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT));
        p.flush();
        ByteBuffer stereo = ByteBuffer.allocateDirect(4 * 480).order(ByteOrder.nativeOrder());
        // Reconfigure to 5.1 while stereo data is still queued (Media3 does this before flushing).
        p.configure(new AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT));
        p.queueInput(stereo);
        p.getOutput();
        p.flush();
        ByteBuffer six = ByteBuffer.allocateDirect(12 * 480).order(ByteOrder.nativeOrder());
        p.queueInput(six);
        assertFalse("5.1 input consumed after the flush", six.hasRemaining());
        assertEquals(12 * 480, p.getOutput().remaining());
    }

    // ================================================================== share sheet

    @Test
    public void urisFromShareIntents() {
        Uri a = Uri.parse("content://com.google.android.apps.photos.contentprovider/1/a.mp4");
        Uri b = Uri.parse("content://com.android.providers.media.documents/document/video%3A2");
        Intent send = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, a);
        assertEquals(List.of(a), PlayerActivity.urisFromIntent(send));

        Intent multi = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(List.of(b, a)));
        assertEquals("share order kept", List.of(b, a), PlayerActivity.urisFromIntent(multi));

        Intent clipOnly = new Intent(Intent.ACTION_SEND).setType("video/mp4");
        clipOnly.setClipData(ClipData.newRawUri("v", b));
        assertEquals(List.of(b), PlayerActivity.urisFromIntent(clipOnly));

        assertEquals(List.of(a), PlayerActivity.urisFromIntent(new Intent(Intent.ACTION_VIEW, a)));
        assertTrue(PlayerActivity.urisFromIntent(new Intent(Intent.ACTION_SEND)).isEmpty());

        ArrayList<Uri> many = new ArrayList<>();
        for (int i = 0; i < 260; i++) many.add(Uri.parse("file:///sdcard/v" + i + ".mp4"));
        Intent big = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, many);
        assertEquals(200, PlayerActivity.urisFromIntent(big).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sharedVideosPlayAsPlaylistInShareOrder() {
        ArrayList<Uri> uris = new ArrayList<>(List.of(
                Uri.parse("file:///sdcard/DCIM/Camera/first.mp4"),
                Uri.parse("file:///sdcard/DCIM/Camera/second.mp4"),
                Uri.parse("file:///sdcard/DCIM/Camera/third.mp4")));
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ActivityController<PlayerActivity> ctl = launchPlayer(i);
        PlayerActivity a = ctl.get();
        List<VideoItem> pl = (List<VideoItem>) field(a, "playlist");
        assertTrue("playlist built", await(() -> pl.size() == 3, 5000));
        for (int k = 0; k < 3; k++) assertEquals(uris.get(k), pl.get(k).uri);
        assertEquals(0, (int) (Integer) field(a, "playlistIndex"));
        assertEquals(uris.get(0), field(a, "currentUri"));
        ctl.pause().stop().destroy();
    }

    // ================================================================== shortcuts

    @Test
    public void shortcutEligibility() {
        assertTrue(Shortcuts.canReopenLater(Uri.parse("content://media/external/video/media/5"), 5));
        assertTrue(Shortcuts.canReopenLater(Uri.parse("content://media/external/video/media/5"), -1));
        assertTrue(Shortcuts.canReopenLater(Uri.parse("https://example.com/live.m3u8"), -1));
        assertFalse("temporary grant from another app",
                Shortcuts.canReopenLater(Uri.parse("content://com.google.android.apps.photos.contentprovider/x"), -1));
        assertFalse("private videos never become shortcuts", Shortcuts.canReopenLater(
                Uri.parse("content://com.jamh.videoplayer.fileprovider/vault/abc.mp4"), 7));
    }

    @Test
    @Config(sdk = 34)
    public void continueWatchingShortcutIsPushedAndRemovedWhenMovedToPrivate() {
        Uri u = Uri.parse("content://media/external/video/media/42");
        Shortcuts.pushContinueWatching(app, u, 42, "Lake District hike", null);
        List<ShortcutInfoCompat> list = ShortcutManagerCompat.getDynamicShortcuts(app);
        assertEquals(1, list.size());
        ShortcutInfoCompat sc = list.get(0);
        assertEquals(Shortcuts.ID_CONTINUE, sc.getId());
        assertEquals(42, sc.getIntent().getLongExtra(PlayerActivity.EXTRA_VIDEO_ID, -1));
        assertEquals(PlayerActivity.class.getName(), sc.getIntent().getComponent().getClassName());

        Shortcuts.removeContinueWatchingFor(app, 7, null);   // other video: kept
        assertEquals(1, ShortcutManagerCompat.getDynamicShortcuts(app).size());
        Shortcuts.removeContinueWatchingFor(app, 42, u);
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(app).isEmpty());
    }

    @Test
    public void staticClipsShortcutDeclared() throws Exception {
        XmlResourceParser p = app.getResources().getXml(R.xml.shortcuts);
        String ns = "http://schemas.android.com/apk/res/android";
        boolean clips = false, action = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev != XmlPullParser.START_TAG) continue;
            if ("shortcut".equals(p.getName()) && "clips".equals(p.getAttributeValue(ns, "shortcutId"))) clips = true;
            if ("intent".equals(p.getName()) && Shortcuts.ACTION_OPEN_CLIPS.equals(p.getAttributeValue(ns, "action"))) action = true;
        }
        assertTrue(clips);
        assertTrue(action);
    }

    // ================================================================== scrub thumbnails

    @Test
    public void scrubThumbnailSpacing() {
        assertEquals(2_000, ScrubThumbnails.intervalFor(60_000));          // 1 min clip: every 2 s
        assertEquals(12_000, ScrubThumbnails.intervalFor(2 * 3600_000L));   // 2 h film: 600 frames max
        long iv = ScrubThumbnails.intervalFor(169 * 60_000L);
        assertTrue(ScrubThumbnails.frameCount(169 * 60_000L, iv) <= ScrubThumbnails.MAX_FRAMES + 1);
        assertEquals(0, ScrubThumbnails.indexFor(900, 2000, 31));
        assertEquals(1, ScrubThumbnails.indexFor(1100, 2000, 31));
        assertEquals(30, ScrubThumbnails.indexFor(999_999, 2000, 31));
    }

    @Test
    public void sweepIsCoarseToFineAndCoversEveryFrame() {
        int[] order = ScrubThumbnails.sweepOrder(16);
        assertArrayEquals(new int[]{0, 8, 4, 12}, Arrays.copyOf(order, 4));
        for (int n : new int[]{1, 2, 7, 100, 601}) {
            int[] o = ScrubThumbnails.sweepOrder(n);
            Set<Integer> seen = new HashSet<>();
            for (int v : o) seen.add(v);
            assertEquals(n, seen.size());
        }
    }

    @Test
    public void nearestCachedFrame() {
        byte[][] f = new byte[10][];
        assertEquals(-1, ScrubThumbnails.nearestAvailable(f, 4));
        f[0] = new byte[1];
        f[8] = new byte[1];
        assertEquals(0, ScrubThumbnails.nearestAvailable(f, 3));
        assertEquals(8, ScrubThumbnails.nearestAvailable(f, 6));
        f[6] = new byte[1];
        assertEquals(6, ScrubThumbnails.nearestAvailable(f, 6));
    }

    @Test
    public void packFileRoundTripSurvivesTruncation() throws Exception {
        File f = File.createTempFile("scrub", ".pack");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(f))) {
            ScrubThumbnails.writeHeader(out, 4000, 5);
            ScrubThumbnails.writeRecord(out, 0, new byte[]{1, 2, 3});
            ScrubThumbnails.writeRecord(out, 3, new byte[]{9, 9});
            ScrubThumbnails.writeRecord(out, 4, new byte[]{7, 7, 7, 7});
        }
        // Simulate a crash mid-append: chop the last record.
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(raf.length() - 2);
        }
        byte[][] frames = new byte[5][];
        assertTrue(ScrubThumbnails.readPack(f, 4000, frames));
        assertArrayEquals(new byte[]{1, 2, 3}, frames[0]);
        assertArrayEquals(new byte[]{9, 9}, frames[3]);
        assertNull(frames[4]);
        assertFalse("different spacing → ignored", ScrubThumbnails.readPack(f, 2000, new byte[5][]));
        assertFalse(ScrubThumbnails.readPack(new File(f.getPath() + ".missing"), 4000, new byte[5][]));
        f.delete();
    }

    @Test
    public void lookupShowsNearestFrameAndPrivateVideosSkipTheDiskCache() {
        ScrubThumbnails t = new ScrubThumbnails(app, Uri.parse("file:///sdcard/a.mp4"), "ms:1", 60_000);
        assertNotNull(t.packFile());
        assertTrue(t.packFile().getPath().contains(File.separator + "scrub" + File.separator));
        byte[][] frames = (byte[][]) field(t, "jpegs");
        frames[0] = jpeg(fakeFrame(240, 135, 1));
        frames[10] = jpeg(fakeFrame(240, 135, 2));
        ScrubThumbnails.Frame fr = t.lookup(18_500);           // index 9 → nearest cached is 10
        assertNotNull(fr);
        assertEquals(10, fr.index);
        assertFalse(fr.exact);
        assertEquals(240, fr.bitmap.getWidth());
        assertEquals("exact frame requested on demand", 9, (int) (Integer) field(t, "demandIndex"));
        assertTrue(t.lookup(20_000).exact);
        t.release();

        ScrubThumbnails priv = new ScrubThumbnails(app,
                Uri.parse("content://com.jamh.videoplayer.fileprovider/vault/x.mp4"), null, 60_000);
        assertNull("private video thumbnails stay in memory", priv.packFile());
        priv.release();
    }

    @Test
    public void thumbnailDiskCacheIsPruned() throws Exception {
        File dir = Files.createTempDirectory("scrubprune").toFile();
        File old = new File(dir, "old.pack"), keep = new File(dir, "keep.pack");
        try (RandomAccessFile r = new RandomAccessFile(old, "rw")) {
            r.setLength(ScrubThumbnails.DISK_BUDGET_BYTES);
        }
        try (RandomAccessFile r = new RandomAccessFile(keep, "rw")) {
            r.setLength(1024);
        }
        old.setLastModified(1_000_000L);
        ScrubThumbnails.pruneDisk(dir, keep);
        assertFalse(old.exists());
        assertTrue(keep.exists());
    }

    /** Player with a stand-in frame + controls, as in the other previews. */
    private FrameLayout dressPlayer(PlayerActivity a) {
        FrameLayout root = a.findViewById(R.id.player_root);
        ImageView frame = new ImageView(a);
        frame.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.setImageBitmap(fakeFrame(1600, 900, 2));
        root.addView(frame, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((TextView) a.findViewById(R.id.title)).setText("Brighton beach sunset");
        SeekBar sb = a.findViewById(R.id.seekbar);
        sb.setMax(2634);
        sb.setProgress(1012);
        ((TextView) a.findViewById(R.id.position)).setText("1:41");
        ((TextView) a.findViewById(R.id.duration)).setText("4:23");
        a.findViewById(R.id.controls).setVisibility(View.VISIBLE);
        a.findViewById(R.id.controls).setAlpha(1f);
        a.findViewById(R.id.buffering).setVisibility(View.GONE);
        return root;
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void scrubThumbnailPreview() throws Exception {
        ActivityController<PlayerActivity> ctl = launchPlayer(new Intent(Intent.ACTION_VIEW,
                Uri.parse("file:///sdcard/Movies/Brighton%20beach%20sunset.mp4")));
        PlayerActivity a = ctl.get();
        // Stand-in thumbnails (no MediaMetadataRetriever decoding under Robolectric).
        ScrubThumbnails t = new ScrubThumbnails(a, Uri.parse("file:///sdcard/Movies/Brighton%20beach%20sunset.mp4"),
                "test-preview", 263_000);
        byte[][] frames = (byte[][]) field(t, "jpegs");
        for (int k = 0; k < frames.length; k += 2) frames[k] = jpeg(fakeFrame(240, 135, 5 + k / 9));
        setField(a, "thumbs", t);
        setField(a, "thumbsUri", field(a, "currentUri"));
        FrameLayout root = dressPlayer(a);
        renderToBitmap(root, 2340, 1080); // layout pass so the seek bar has a position

        Method atSeekBar = PlayerActivity.class.getDeclaredMethod("showScrubPreviewAtSeekBar", long.class);
        atSeekBar.setAccessible(true);
        atSeekBar.invoke(a, 101_200L);
        View preview = a.findViewById(R.id.scrub_preview);
        assertEquals(View.VISIBLE, preview.getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.scrub_preview_frame).getVisibility());
        assertEquals("1:41", ((TextView) a.findViewById(R.id.scrub_preview_time)).getText().toString());
        SeekBar sb = a.findViewById(R.id.seekbar);
        int[] sbLoc = new int[2], rootLoc = new int[2];
        sb.getLocationInWindow(sbLoc);
        root.getLocationInWindow(rootLoc);
        assertTrue("sits above the seek bar",
                preview.getTranslationY() + preview.getMeasuredHeight() <= sbLoc[1] - rootLoc[1]);
        TextView info = a.findViewById(R.id.seek_info);
        info.setText("1:41 / 4:23");
        info.setVisibility(View.VISIBLE);
        save(root, "player_scrub_thumbnail.png", 2340, 1080);

        // Swipe scrub: above the finger, controls hidden.
        a.findViewById(R.id.controls).setVisibility(View.GONE);
        info.setText("+0:25\n2:06 / 4:23\nNormal scrubbing");
        Method swipe = PlayerActivity.class.getDeclaredMethod("showScrubPreview", long.class, float.class, float.class, boolean.class);
        swipe.setAccessible(true);
        swipe.invoke(a, 126_000L, 1500f, 700f, false);
        assertTrue("above the finger", preview.getTranslationY() + preview.getMeasuredHeight() < 700f);
        TextView si = a.findViewById(R.id.seek_info);
        float siLeft = (root.getWidth() - si.getMeasuredWidth()) / 2f;
        assertTrue("clear of the centre indicator", preview.getTranslationX() >= siLeft + si.getMeasuredWidth()
                || preview.getTranslationY() >= si.getMeasuredHeight() + 72 * a.getResources().getDisplayMetrics().density);
        info.setText("+0:25\n2:06 / 4:23\nNormal scrubbing");
        save(root, "player_scrub_thumbnail_swipe.png", 2340, 1080);

        // Streams: no thumbnail overlay.
        Method hide = PlayerActivity.class.getDeclaredMethod("hideScrubPreview");
        hide.setAccessible(true);
        hide.invoke(a);
        setField(a, "currentUri", Uri.parse("https://example.com/live/stream.m3u8"));
        swipe.invoke(a, 126_000L, 1500f, 700f, false);
        assertEquals(View.GONE, preview.getVisibility());
        ctl.pause().stop().destroy();
        assertNull("thumbnail work cancelled when leaving the player", field(a, "thumbs"));
    }

    // ================================================================== Private videos

    private File tempVideo(int bytes, int seed) throws Exception {
        File f = File.createTempFile("vid", ".mp4");
        byte[] b = new byte[bytes];
        new Random(seed).nextBytes(b);
        Files.write(f.toPath(), b);
        return f;
    }

    @Test
    public void vaultImportVerifiesAndLists() throws Exception {
        File src = tempVideo(300_000, 1);
        Vault.Entry e = Vault.importFrom(app, Uri.fromFile(src), "Wedding speech.mp4", "video/mp4", -1, null);
        assertEquals(300_000, e.size);
        assertEquals(Vault.sha256Of(src), e.sha256);
        File stored = Vault.fileOf(app, e);
        assertTrue(stored.getPath().startsWith(new File(app.getFilesDir(), "vault").getPath()));
        // 1.6.0: stored encrypted; the plaintext comes back through the vault's own reader.
        assertTrue(e.enc);
        try (java.io.InputStream in = Vault.openStream(app, e)) {
            assertArrayEquals(Files.readAllBytes(src.toPath()), in.readAllBytes());
        }
        List<Vault.Entry> list = Vault.list(app);
        assertEquals(1, list.size());
        assertEquals("Wedding speech.mp4", list.get(0).name);
        assertTrue(Vault.usedBytes(app) >= 300_000);
        // No temp files left behind.
        for (File f : Vault.dir(app).listFiles()) assertFalse(f.getName(), f.getName().startsWith(".tmp-"));

        Uri vu = Uri.parse("content://com.jamh.videoplayer.fileprovider/vault/" + e.file);
        assertTrue(Vault.isVaultUri(vu));
        assertEquals(e.id, Vault.findByUri(app, vu).id);
        Uri fp = Vault.uriFor(app, e);
        assertTrue(fp.toString(), Vault.isVaultUri(fp));
        assertFalse(Vault.isVaultUri(Uri.parse("content://media/external/video/media/1")));
        assertFalse(Vault.isVaultUri(Uri.parse("content://com.jamh.videoplayer.fileprovider/exports/a.mp4")));

        Vault.delete(app, e);
        assertTrue(Vault.list(app).isEmpty());
        assertFalse(stored.exists());
    }

    @Test
    public void cancelledImportLeavesNothingBehind() throws Exception {
        File src = tempVideo(3_000_000, 2);
        try {
            Vault.importFrom(app, Uri.fromFile(src), "Big.mp4", "video/mp4", -1, new Vault.Progress() {
                @Override public void onProgress(long done, long total) { }
                @Override public boolean isCancelled() { return true; }
            });
            fail("expected cancellation");
        } catch (java.io.IOException expected) {
            // ok
        }
        assertTrue(Vault.list(app).isEmpty());
        File[] left = Vault.dir(app).listFiles();
        assertTrue(left == null || Arrays.stream(left).noneMatch(File::isFile));
    }

    @Test
    public void vaultIsExcludedFromBackupAndTransfer() throws Exception {
        for (int res : new int[]{R.xml.data_extraction_rules, R.xml.backup_rules}) {
            XmlResourceParser p = app.getResources().getXml(res);
            int excludes = 0;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG && "exclude".equals(p.getName())
                        && "file".equals(p.getAttributeValue(null, "domain"))
                        && "vault/".equals(p.getAttributeValue(null, "path"))) excludes++;
            }
            assertEquals(res == R.xml.data_extraction_rules ? 2 : 1, excludes);
        }
    }

    @Test
    public void vaultLocksWhenAppGoesToBackground() {
        VaultLock.unlock();
        ActivityController<VaultActivity> ctl = Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity a = ctl.get();
        idle();
        assertTrue((a.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertEquals(View.VISIBLE, a.findViewById(R.id.vault_content).getVisibility());
        ctl.pause().stop();                      // home button: no activity of ours is visible
        assertFalse(VaultLock.isUnlocked());
        ctl.restart().start().resume();
        idle();
        assertEquals(View.GONE, a.findViewById(R.id.vault_content).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.vault_locked).getVisibility());
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void privatePlaybackIsSecureAndLeavesNoTraces() throws Exception {
        File src = tempVideo(200_000, 3);
        Vault.Entry e = Vault.importFrom(app, Uri.fromFile(src), "Private clip.mp4", "video/mp4", -1, null);
        VaultLock.unlock();
        Uri vu = Vault.uriFor(app, e);
        ActivityController<PlayerActivity> ctl = launchPlayer(new Intent(Intent.ACTION_VIEW).setDataAndType(vu, "video/mp4"));
        PlayerActivity a = ctl.get();
        assertTrue(await(() -> vu.equals(field(a, "currentUri")), 5000));
        assertTrue(a.isPrivatePlayback());
        assertTrue((a.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0);
        // 1.6.0: PiP is allowed for private videos (FLAG_SECURE stays); resume points go to the
        // separate private file, never to the gallery's (see V16ResumePipTest).
        Method save = PlayerActivity.class.getDeclaredMethod("saveResume");
        save.setAccessible(true);
        save.invoke(a);
        assertNull("no gallery resume point", Prefs.getResume(app, (String) field(a, "currentKey")));
        idle();
        assertTrue("no Continue watching shortcut", ShortcutManagerCompat.getDynamicShortcuts(app).isEmpty());

        // Locked again (app was backgrounded): the picture is hidden until unlocked.
        VaultLock.lock();
        ctl.pause().stop();
        ctl.restart().start().resume();
        idle();
        assertEquals(View.INVISIBLE, a.findViewById(R.id.player_view).getVisibility());
        ctl.pause().stop().destroy();
        Vault.delete(app, e);
    }

    // ------------------------------------------------------------------ previews

    private Vault.Entry fakeEntry(String id, String name, long dur, long size, int w, int h, int seed) throws Exception {
        Vault.Entry e = new Vault.Entry();
        e.id = id;
        e.file = id + ".mp4";
        e.name = name;
        e.mime = "video/mp4";
        e.durationMs = dur;
        e.size = size;
        e.width = w;
        e.height = h;
        File t = Vault.thumbOf(app, e);
        t.getParentFile().mkdirs();
        Bitmap b = w >= h ? fakeFrame(360, 203, seed) : fakeFrame(203, 360, seed);
        try (FileOutputStream out = new FileOutputStream(t)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        }
        return e;
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void vaultScreens() throws Exception {
        ActivityController<VaultActivity> ctl = Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity a = ctl.get();
        idle();
        a.showLocked(false);
        assertEquals(View.VISIBLE, a.findViewById(R.id.vault_locked).getVisibility());
        save(a.getWindow().getDecorView(), "vault_locked.png", 1080, 2340);

        a.showLocked(true);
        assertEquals(a.getString(R.string.vault_no_lock_title),
                ((TextView) a.findViewById(R.id.vault_locked_title)).getText().toString());
        save(a.getWindow().getDecorView(), "vault_no_screen_lock.png", 1080, 2340);

        VaultLock.unlock();
        List<Vault.Entry> list = new ArrayList<>();
        list.add(fakeEntry("a1", "Wedding speech.mp4", 312_000, 486_000_000L, 1920, 1080, 10));
        list.add(fakeEntry("a2", "Scan of passport.mp4", 14_000, 22_000_000L, 1080, 1920, 3));
        list.add(fakeEntry("a3", "Surprise party plan.mp4", 95_000, 160_000_000L, 1920, 1080, 6));
        list.add(fakeEntry("a4", "Baby's first steps.mp4", 48_000, 81_000_000L, 1920, 1080, 1));
        long used = 0;
        for (Vault.Entry e : list) used += e.size;
        a.showContent();
        TextView storage = a.findViewById(R.id.vault_storage);
        assertTrue("initial reload done", await(() -> storage.getText().toString().startsWith("0 videos"), 5000));
        a.bind(list, used, 41L * 1024 * 1024 * 1024);
        View decor = a.getWindow().getDecorView();
        renderToBitmap(decor, 1080, 2340);
        await(() -> false, 600);                // let the thumbnails load
        assertTrue(storage.getText().toString(), storage.getText().toString().startsWith("4 videos · "));
        assertEquals(View.GONE, a.findViewById(R.id.vault_empty).getVisibility());
        TextView warn = a.findViewById(R.id.vault_warning);
        assertTrue(warn.getText().toString().contains("Uninstalling"));
        save(decor, "vault_list.png", 1080, 2340);

        a.bind(new ArrayList<>(), 0, 41L * 1024 * 1024 * 1024);
        assertEquals(View.VISIBLE, a.findViewById(R.id.vault_empty).getVisibility());
        save(decor, "vault_empty.png", 1080, 2340);
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void moveToPrivateConfirmation() throws Exception {
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        idle();
        Bitmap base = renderToBitmap(a.getWindow().getDecorView(), 1080, 2340);
        MoveToPrivateFlow flow = (MoveToPrivateFlow) field(a, "moveToPrivate");
        VideoItem v = new VideoItem(1011, Uri.parse("content://media/external/video/media/1011"),
                "Wedding speech.mp4", 312_000, 486_000_000L, 1_790_000_000L, "dl", "Download", 1920, 1080);
        Dialog d = flow.showConfirm(v);
        idle();
        View dv = d.getWindow().getDecorView();
        int w = 1080 - 2 * 72;
        dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340 - 60, View.MeasureSpec.AT_MOST));
        dv.layout(0, 0, w, dv.getMeasuredHeight());
        Canvas c = new Canvas(base);
        c.drawColor(0x99000000);
        c.save();
        c.translate(72, (2340 - dv.getMeasuredHeight()) / 2f);
        dv.draw(c);
        c.restore();
        write(base, "move_to_private_confirm.png");
        TextView msg = d.findViewById(android.R.id.message);
        assertTrue(msg.getText().toString().contains("Uninstalling"));
        d.dismiss();
        ctl.pause().stop().destroy();
    }
}
