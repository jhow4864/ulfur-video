package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.renderToBitmap;
import static com.jamh.videoplayer.PreviewScreenshotTest.save;
import static com.jamh.videoplayer.TestUtil.await;
import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.DialogInterface;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.appbar.MaterialToolbar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 1.6.0-pre: Trash with undo (gallery = Android's trash, Private = encrypted app trash). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class V16TrashTest {

    private final Application app = ApplicationProvider.getApplicationContext();
    private DeleteVideosFlow.RequestMaker real;

    @Before
    public void setUp() {
        real = DeleteVideosFlow.requestMaker;
        V151FeatureTest.stubRequests(app);
        for (Vault.Entry e : Vault.readIndex(app)) Vault.delete(app, e);
    }

    @After
    public void tearDown() {
        DeleteVideosFlow.requestMaker = real;
        GalleryTrash.overrideForTest = null;
        MediaAccess.overrideForTest = null;
        VaultLock.lock();
        for (Vault.Entry e : Vault.readIndex(app)) Vault.delete(app, e);
    }

    private List<GalleryTrash.Item> fakeTrash() throws Exception {
        List<VideoItem> vids = new PreviewScreenshotTest().fakeVideos();
        new PreviewScreenshotTest().seedThumbnails(vids);
        long now = System.currentTimeMillis() / 1000;
        List<GalleryTrash.Item> out = new ArrayList<>();
        int[] days = {29, 12, 3, 1};
        for (int i = 0; i < 4; i++) out.add(new GalleryTrash.Item(vids.get(i), now + days[i] * 86_400L - 60));
        return out;
    }

    @Test
    public void timeLeftLabels() {
        long now = 1_000_000_000_000L;
        assertEquals("30 days left", GalleryTrash.timeLeft(now + Vault.TRASH_MS, now));
        assertEquals("1 day left", GalleryTrash.timeLeft(now + 3_600_000, now));
        assertEquals("Deleted today", GalleryTrash.timeLeft(now - 1, now));
        assertEquals("In trash", GalleryTrash.timeLeft(0, now));
    }

    @Test
    public void galleryTrashRestoreAndDeleteForever() throws Exception {
        MediaAccess.overrideForTest = true;
        GalleryTrash.overrideForTest = fakeTrash();
        ActivityController<TrashActivity> ctl = Robolectric.buildActivity(TrashActivity.class,
                TrashActivity.intent(app, false)).setup();
        TrashActivity a = ctl.get();
        assertTrue(await(() -> { idle(); return a.rows().size() == 4; }, 5000));
        assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        MaterialToolbar tb = a.findViewById(R.id.trash_toolbar);
        assertTrue(((TextView) a.findViewById(R.id.trash_info)).getText().toString().startsWith("4 videos · "));
        View decor = a.getWindow().getDecorView();
        renderToBitmap(decor, 1080, 2340);
        await(() -> false, 500);
        save(decor, "v16_trash_gallery.png", 1080, 2340);

        // Restore one: a single MediaStore untrash request.
        AlertDialog sheet = a.showActions(a.rows().get(1));
        idle();
        ListView lv = sheet.getListView();
        assertEquals("Restore", lv.getAdapter().getItem(0).toString());
        assertEquals("Delete forever", lv.getAdapter().getItem(1).toString());
        lv.performItemClick(lv.getAdapter().getView(0, null, lv), 0, 0);
        idle();
        assertEquals(1, V151FeatureTest.requests.size());
        assertEquals(DeleteVideosFlow.REQ_UNTRASH, V151FeatureTest.requests.get(0)[0]);
        GalleryTrash.overrideForTest.remove(1);
        a.onRequestResult(true);
        assertTrue(await(() -> { idle(); return a.rows().size() == 3; }, 5000));

        // Empty trash: with media management our confirmation, then one delete request for all.
        AlertDialog d = a.confirmDeleteForever(new ArrayList<>(a.rows()), true);
        assertNotNull(d);
        assertEquals("Empty trash?", ((TextView) d.findViewById(androidx.appcompat.R.id.alertTitle)).getText().toString());
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(2, V151FeatureTest.requests.size());
        assertEquals(DeleteVideosFlow.REQ_DELETE, V151FeatureTest.requests.get(1)[0]);
        assertEquals(3, V151FeatureTest.requests.get(1)[1]);
        GalleryTrash.overrideForTest.clear();
        a.onRequestResult(true);
        assertTrue(await(() -> { idle(); return a.rows().isEmpty(); }, 5000));
        assertEquals(View.VISIBLE, a.findViewById(R.id.trash_empty).getVisibility());

        // Without media management Android's prompt is the only confirmation.
        MediaAccess.overrideForTest = false;
        GalleryTrash.overrideForTest = fakeTrash();
        a.reload();
        assertTrue(await(() -> { idle(); return a.rows().size() == 4; }, 5000));
        assertNull(a.confirmDeleteForever(new ArrayList<>(a.rows().subList(0, 1)), false));
        assertEquals(3, V151FeatureTest.requests.size());
        ctl.pause().stop().destroy();
    }

    private Vault.Entry importBytes(int n, String name, int seed) throws Exception {
        byte[] d = new byte[n];
        new Random(seed).nextBytes(d);
        File src = File.createTempFile("src", ".mp4");
        Files.write(src.toPath(), d);
        return Vault.importFrom(app, Uri.fromFile(src), name, "video/mp4", -1, null);
    }

    @Test
    public void privateTrashIsSecureEncryptedAndPurged() throws Exception {
        Vault.Entry a1 = importBytes(40_000, "Wedding speech.mp4", 1);
        Vault.Entry a2 = importBytes(50_000, "Scan of passport.mp4", 2);
        Vault.Entry a3 = importBytes(60_000, "Surprise party plan.mp4", 3);
        List<Vault.Entry> two = new ArrayList<>();
        two.add(a1);
        two.add(a2);
        Vault.trash(app, two, System.currentTimeMillis() - 5 * 86_400_000L);
        assertEquals(1, Vault.list(app).size());
        assertEquals(2, Vault.listTrashed(app).size());
        // Still encrypted where it was.
        assertTrue(Vault.fileOf(app, a1).getName().endsWith(".uvx"));

        // Locked: the private trash refuses to open.
        VaultLock.lock();
        ActivityController<TrashActivity> locked = Robolectric.buildActivity(TrashActivity.class,
                TrashActivity.intent(app, true)).setup();
        assertTrue(locked.get().isFinishing());
        locked.pause().stop().destroy();

        VaultLock.unlock();
        ActivityController<TrashActivity> ctl = Robolectric.buildActivity(TrashActivity.class,
                TrashActivity.intent(app, true)).setup();
        TrashActivity a = ctl.get();
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertTrue(await(() -> { idle(); return a.rows().size() == 2; }, 5000));
        MaterialToolbar tb = a.findViewById(R.id.trash_toolbar);
        assertEquals(app.getString(R.string.trash_private_title), tb.getTitle().toString());
        View decor = a.getWindow().getDecorView();
        renderToBitmap(decor, 1080, 2340);
        await(() -> false, 400);
        save(decor, "v16_trash_private.png", 1080, 2340);

        a.restore(new ArrayList<>(a.rows().subList(0, 1)));
        assertTrue(await(() -> { idle(); return a.rows().size() == 1 && Vault.list(app).size() == 2; }, 5000));

        AlertDialog d = a.confirmDeleteForever(new ArrayList<>(a.rows()), true);
        assertNotNull("always confirmed in Private", d);
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertTrue(await(() -> { idle(); return a.rows().isEmpty(); }, 5000));
        assertEquals(2, Vault.listAll(app).size());
        ctl.pause().stop().destroy();

        // 30-day purge.
        List<Vault.Entry> one = new ArrayList<>();
        one.add(a3);
        Vault.trash(app, one, System.currentTimeMillis() - Vault.TRASH_MS + 60_000);
        assertEquals("not yet", 0, Vault.purgeExpired(app, System.currentTimeMillis()));
        assertEquals(1, Vault.purgeExpired(app, System.currentTimeMillis() + 120_000));
        assertFalse(Vault.fileOf(app, a3).exists());
    }

    @Test
    public void libraryMenuHasTrashOnAndroid11Plus() throws Exception {
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        idle();
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        a.onPrepareOptionsMenu(tb.getMenu());
        assertTrue(tb.getMenu().findItem(R.id.action_trash).isVisible());
        a.onOptionsItemSelected(tb.getMenu().findItem(R.id.action_trash));
        assertEquals(TrashActivity.class.getName(),
                org.robolectric.Shadows.shadowOf(a).getNextStartedActivity().getComponent().getClassName());
        ctl.pause().stop().destroy();
    }
}
