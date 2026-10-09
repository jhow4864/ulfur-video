package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.renderToBitmap;
import static com.jamh.videoplayer.PreviewScreenshotTest.save;
import static com.jamh.videoplayer.TestUtil.await;
import static com.jamh.videoplayer.TestUtil.field;
import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;

import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.media3.exoplayer.ExoPlayer;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 1.6.0-pre: resume + Continue watching for Private videos, PiP for Private with FLAG_SECURE. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class V16ResumePipTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        for (Vault.Entry e : Vault.readIndex(app)) Vault.delete(app, e);
        shadowOf(app.getPackageManager()).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true);
    }

    @After
    public void tearDown() {
        VaultLock.lock();
        Prefs.setUpscaleMode(app, Upscaler.MODE_OFF);
        for (Vault.Entry e : Vault.readIndex(app)) Vault.delete(app, e);
    }

    private Vault.Entry importEntry(String name, long durMs, int seed, Bitmap thumb) throws Exception {
        byte[] d = new byte[30_000];
        new Random(seed).nextBytes(d);
        File src = File.createTempFile("src", ".mp4");
        Files.write(src.toPath(), d);
        Vault.Entry e = Vault.importFrom(app, Uri.fromFile(src), name, "video/mp4", -1, null);
        e.durationMs = durMs;
        e.width = 1920;
        e.height = 1080;
        // Robolectric can't decode the fake video: store a fake (encrypted) thumbnail + duration.
        if (thumb != null) {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            thumb.compress(Bitmap.CompressFormat.JPEG, 85, jpeg);
            Vault.writeEncThumb(app, e, jpeg.toByteArray());
        }
        List<Vault.Entry> l = Vault.readIndex(app);
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(e.id)) l.set(i, e);
        Vault.save(app, l);
        return e;
    }

    private ActivityController<PlayerActivity> play(Vault.Entry e) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Vault.uriFor(app, e), "video/mp4")
                .setClass(app, PlayerActivity.class);
        ActivityController<PlayerActivity> ctl = Robolectric.buildActivity(PlayerActivity.class, i).setup();
        TestUtil.awaitPlayer(ctl.get());
        return ctl;
    }

    @Test
    public void privateResumeIsSeparateFromTheGallery() throws Exception {
        VaultLock.unlock();
        Vault.Entry e = importEntry("Wedding speech.mp4", 312_000, 1, null);
        PrivateResume.save(app, e.file, 61_000, 312_000);
        ActivityController<PlayerActivity> ctl = play(e);
        PlayerActivity a = ctl.get();
        assertTrue(await(() -> Vault.uriFor(app, e).equals(field(a, "currentUri")), 5000));
        ExoPlayer p = (ExoPlayer) field(a, "player");
        assertTrue("resumed at 1:01", await(() -> { idle(); return p.getCurrentPosition() == 61_000; }, 5000));

        p.seekTo(95_000);
        idle();
        Method save = PlayerActivity.class.getDeclaredMethod("saveResume");
        save.setAccessible(true);
        save.invoke(a);
        long[] r = PrivateResume.get(app, e.file);
        assertNotNull(r);
        assertEquals(95_000, r[0]);
        assertNull("nothing in the gallery's resume file", Prefs.getResume(app, (String) field(a, "currentKey")));
        idle();
        assertTrue("still no launcher shortcut", ShortcutManagerCompat.getDynamicShortcuts(app).isEmpty());
        ctl.pause().stop().destroy();

        // Moving out / deleting forgets it.
        Vault.delete(app, e);
        assertNull(PrivateResume.get(app, e.file));
    }

    @Test
    public void privateResumeFileIsExcludedFromBackup() throws Exception {
        for (int res : new int[]{R.xml.data_extraction_rules, R.xml.backup_rules}) {
            XmlResourceParser x = app.getResources().getXml(res);
            boolean resume = false, keyset = false, bookmarks = false, speeds = false;
            while (x.next() != XmlResourceParser.END_DOCUMENT) {
                if (x.getEventType() == XmlResourceParser.START_TAG && "exclude".equals(x.getName())) {
                    String path = x.getAttributeValue(null, "path");
                    if ("private_resume.xml".equals(path)) resume = true;
                    if ("ulfur_vault_keyset.xml".equals(path)) keyset = true;
                    if ("bookmarks.xml".equals(path)) bookmarks = true;
                    if ("speeds.xml".equals(path)) speeds = true;
                }
            }
            assertTrue(resume);
            assertTrue(keyset);
            assertTrue("bookmark names stay out of backups", bookmarks);
            assertTrue(speeds);
        }
    }

    @Test
    public void vaultShowsContinueWatching() throws Exception {
        Vault.Entry a1 = importEntry("Wedding speech.mp4", 312_000, 1, PreviewScreenshotTest.fakeFrame(360, 203, 10));
        Vault.Entry a2 = importEntry("Scan of passport.mp4", 14_000, 2, PreviewScreenshotTest.fakeFrame(360, 203, 3));
        Vault.Entry a3 = importEntry("Surprise party plan.mp4", 95_000, 3, PreviewScreenshotTest.fakeFrame(360, 203, 6));
        PrivateResume.save(app, a3.file, 40_000, 95_000);
        Thread.sleep(5);
        PrivateResume.save(app, a1.file, 200_000, 312_000);
        VaultLock.unlock();
        ActivityController<VaultActivity> ctl = Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity v = ctl.get();
        v.showContent();
        assertTrue(await(() -> { idle(); return v.continueWatching().size() == 2; }, 5000));
        assertEquals("most recent first", a1.id, v.continueWatching().get(0).id);
        assertEquals(a3.id, v.continueWatching().get(1).id);
        assertEquals(View.VISIBLE, v.findViewById(R.id.vault_continue_section).getVisibility());
        View decor = v.getWindow().getDecorView();
        renderToBitmap(decor, 1080, 2340);
        await(() -> false, 600);
        save(decor, "v16_vault_continue.png", 1080, 2340);
        // Hidden while selecting.
        v.setSelecting(true);
        assertEquals(View.GONE, v.findViewById(R.id.vault_continue_section).getVisibility());
        v.setSelecting(false);
        assertEquals(View.VISIBLE, v.findViewById(R.id.vault_continue_section).getVisibility());
        ctl.pause().stop().destroy();
        assertFalse(a2.enc && PrivateResume.get(app, a2.file) != null);
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void privatePipKeepsFlagSecureAndPausesTheUpscaler() throws Exception {
        Prefs.setUpscaleMode(app, Upscaler.MODE_BALANCED);
        VaultLock.unlock();
        Vault.Entry e = importEntry("Private clip.mp4", 60_000, 4, null);
        ActivityController<PlayerActivity> ctl = play(e);
        PlayerActivity a = ctl.get();
        assertTrue(await(() -> a.isPrivatePlayback(), 5000));
        assertEquals("PiP button shown for private videos", View.VISIBLE, a.findViewById(R.id.btn_pip).getVisibility());
        Method running = PlayerActivity.class.getDeclaredMethod("upscaleRunning");
        running.setAccessible(true);
        assertTrue("upscaler on in full screen", await(() -> { idle(); try { return (boolean) running.invoke(a); } catch (Exception ex) { return false; } }, 5000));

        Method enter = PlayerActivity.class.getDeclaredMethod("enterPip");
        enter.setAccessible(true);
        enter.invoke(a);
        assertTrue("entered PiP", a.isInPictureInPictureMode());
        a.onPictureInPictureModeChanged(true, new Configuration(a.getResources().getConfiguration()));
        idle();
        assertTrue("FLAG_SECURE kept in PiP", (a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertFalse("upscaler paused in PiP", (boolean) running.invoke(a));
        // Activity stays started in PiP: Private stays unlocked while the window is open.
        assertTrue(VaultLock.isUnlocked());

        // Auto-enter on Android 12+ is allowed for private videos while unlocked.
        Method params = PlayerActivity.class.getDeclaredMethod("buildPipParams");
        params.setAccessible(true);
        android.app.PictureInPictureParams pp = (android.app.PictureInPictureParams) params.invoke(a);
        assertNotNull(pp);
        ctl.pause().stop().destroy();
    }
}
