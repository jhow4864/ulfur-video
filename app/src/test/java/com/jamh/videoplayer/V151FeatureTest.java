package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.renderToBitmap;
import static com.jamh.videoplayer.PreviewScreenshotTest.save;
import static com.jamh.videoplayer.TestUtil.await;
import static com.jamh.videoplayer.TestUtil.field;
import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.Menu;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.appbar.MaterialToolbar;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 1.5.1: bulk / single delete from selection mode in Videos, folders and Clips; vault bulk delete still works. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class V151FeatureTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    /** Stand-in MediaStore: records deletes and reports success. */
    public static class FakeMediaStore extends ContentProvider {
        static final List<Uri> deleted = Collections.synchronizedList(new ArrayList<>());
        @Override public boolean onCreate() { return true; }
        @Nullable @Override public Cursor query(@NonNull Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Nullable @Override public String getType(@NonNull Uri u) { return "video/mp4"; }
        @Nullable @Override public Uri insert(@NonNull Uri u, ContentValues v) { return null; }
        @Override public int delete(@NonNull Uri u, String s, String[] a) { deleted.add(u); return 1; }
        @Override public int update(@NonNull Uri u, ContentValues v, String s, String[] a) { return 0; }
    }

    /** Records MediaStore requests (Robolectric has no MediaProvider to create them). */
    static final List<int[]> requests = Collections.synchronizedList(new ArrayList<>());
    static final List<List<Uri>> requestUris = Collections.synchronizedList(new ArrayList<>());

    static void stubRequests(Application app) {
        requests.clear();
        requestUris.clear();
        DeleteVideosFlow.requestMaker = (cr, uris, kind) -> {
            requests.add(new int[]{kind, uris.size()});
            requestUris.add(new ArrayList<>(uris));
            return android.app.PendingIntent.getActivity(app, requests.size(),
                    new android.content.Intent("test.REQUEST"), android.app.PendingIntent.FLAG_IMMUTABLE);
        };
    }

    private static DeleteVideosFlow.RequestMaker realMaker;

    @org.junit.Before
    public void setUpRequests() {
        if (realMaker == null) realMaker = DeleteVideosFlow.requestMaker;
        stubRequests(app);
    }

    @After
    public void tearDown() {
        MediaAccess.overrideForTest = null;
        FakeMediaStore.deleted.clear();
        DeleteVideosFlow.requestMaker = realMaker;
    }

    private MainActivity library(boolean grid, int tab, List<VideoItem> vids, ActivityController<MainActivity>[] out) throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_EXTERNAL_STORAGE);
        Prefs.setGrid(app, grid);
        Prefs.setDefaultBannerDismissed(app);
        new PreviewScreenshotTest().seedThumbnails(vids);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        out[0] = ctl;
        MainActivity a = ctl.get();
        idle();
        TestUtil.setField(a, "allVideos", new ArrayList<>(vids));
        TestUtil.setField(a, "tab", tab);
        com.google.android.material.tabs.TabLayout tabs = a.findViewById(R.id.tabs);
        tabs.getTabAt(tab).select();
        Method render = MainActivity.class.getDeclaredMethod("render");
        render.setAccessible(true);
        render.invoke(a);
        idle();
        return a;
    }

    private List<VideoItem> shown(int tab, List<VideoItem> vids) {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : vids) if (tab != 2 || v.matchesClipFilter(Prefs.clipsFilter(app))) out.add(v);
        return out;
    }

    @SuppressWarnings("unchecked")
    void bulkDelete(boolean grid, int tab, String file) throws Exception {
        Robolectric.setupContentProvider(FakeMediaStore.class, "media");
        MediaAccess.overrideForTest = true;   // media management allowed: our dialog, no system prompt
        List<VideoItem> vids = new PreviewScreenshotTest().fakeVideos();
        ActivityController<MainActivity>[] c = new ActivityController[1];
        MainActivity a = library(grid, tab, vids, c);
        LibraryAdapter adapter = (LibraryAdapter) field(a, "adapter");
        List<VideoItem> shown = shown(tab, vids);
        assertTrue(shown.size() >= 3);

        // Long-press still selects (no conflicting menu), taps add more.
        a.onVideoLongClick(shown.get(0), a.findViewById(R.id.recycler));
        adapter.toggle(shown.get(1));
        adapter.toggle(shown.get(2));
        idle();
        View del = a.findViewById(R.id.selection_delete);
        assertEquals(View.VISIBLE, a.findViewById(R.id.selection_bar).getVisibility());
        assertTrue(del.isEnabled());
        assertEquals("Delete 3 videos", del.getContentDescription().toString());
        assertEquals("Move 3 to Private", ((TextView) a.findViewById(R.id.selection_move_private)).getText().toString());
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        Menu m = tb.getMenu();
        assertNotNull("trash icon in the selection toolbar", m.findItem(R.id.action_delete_selected));

        // 1.6.0 (Android 11+): Delete moves the batch to Android's trash with ONE request and no
        // dialog of ours (it's undoable); auto-approved with media management.
        a.onOptionsItemSelected(m.findItem(R.id.action_delete_selected));
        idle();
        assertTrue("no confirmation dialog of ours", ShadowDialog.getLatestDialog() == null
                || !ShadowDialog.getLatestDialog().isShowing());
        assertEquals(1, requests.size());
        assertEquals(DeleteVideosFlow.REQ_TRASH, requests.get(0)[0]);
        assertEquals(3, requests.get(0)[1]);
        for (int i = 0; i < 3; i++) {
            assertTrue(requestUris.get(0).contains(DeleteVideosFlow.mediaStoreUri(shown.get(i))));
        }
        assertTrue("nothing deleted permanently", FakeMediaStore.deleted.isEmpty());
        DeleteVideosFlow flow = (DeleteVideosFlow) field(a, "deleteFlow");
        flow.onSystemResult(true);   // auto-approved
        idle();
        assertFalse("selection mode exited", adapter.isSelectionMode());
        assertEquals(View.GONE, a.findViewById(R.id.selection_bar).getVisibility());
        List<VideoItem> all = (List<VideoItem>) field(a, "allVideos");
        for (int i = 0; i < 3; i++) assertFalse(all.contains(shown.get(i)));
        com.google.android.material.snackbar.Snackbar bar = flow.undoBar();
        assertNotNull("Deleted 3 · Undo", bar);
        TextView sbText = bar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        assertEquals("Deleted 3 videos", sbText.getText().toString());
        android.widget.Button undo = bar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        assertEquals("Undo", undo.getText().toString());
        if (file != null) {
            bar.getView().setVisibility(View.VISIBLE);
            idle();
            renderToBitmap(a.getWindow().getDecorView(), 1080, 2340);
            save(a.getWindow().getDecorView(), file, 1080, 2340);
        }
        // Undo: one restore request for the same batch.
        undo.performClick();
        idle();
        assertEquals(2, requests.size());
        assertEquals(DeleteVideosFlow.REQ_UNTRASH, requests.get(1)[0]);
        assertEquals(requestUris.get(0), requestUris.get(1));
        flow.onSystemResult(true);
        idle();
        assertFalse(flow.isBusy());
        assertEquals("Restored 3 videos", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        c[0].pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void bulkDeleteVideosGrid() throws Exception {
        bulkDelete(true, 0, "library_delete_grid.png");
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void bulkDeleteVideosList() throws Exception {
        bulkDelete(false, 0, null);
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void bulkDeleteClips() throws Exception {
        bulkDelete(true, 2, "library_delete_clips.png");
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    @SuppressWarnings("unchecked")
    public void listUpdatesImmediatelyAndSingleDeleteWorks() throws Exception {
        List<VideoItem> vids = new PreviewScreenshotTest().fakeVideos();
        ActivityController<MainActivity>[] c = new ActivityController[1];
        MainActivity a = library(false, 0, vids, c);
        LibraryAdapter adapter = (LibraryAdapter) field(a, "adapter");
        int before = adapter.getItemCount();
        // Single video: long-press selects it, Delete acts on just that one.
        a.onVideoLongClick(vids.get(0), a.findViewById(R.id.recycler));
        idle();
        assertEquals(1, adapter.selectedCount());
        assertEquals("Delete 1 video", a.findViewById(R.id.selection_delete).getContentDescription().toString());
        a.removeDeleted(Collections.singletonList(vids.get(0)));
        idle();
        assertEquals(before - 1, adapter.getItemCount());
        assertFalse(adapter.isSelectionMode());
        List<VideoItem> all = (List<VideoItem>) field(a, "allVideos");
        assertEquals(vids.size() - 1, all.size());
        assertFalse(all.contains(vids.get(0)));
        c[0].pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    @SuppressWarnings("unchecked")
    public void withoutMediaManagementAndroidsPromptIsTheOnlyConfirmation() throws Exception {
        MediaAccess.overrideForTest = false;
        assertTrue(DeleteVideosFlow.systemWillAsk(app));
        MediaAccess.overrideForTest = true;
        assertFalse(DeleteVideosFlow.systemWillAsk(app));
        MediaAccess.overrideForTest = false;

        List<VideoItem> vids = new PreviewScreenshotTest().fakeVideos();
        ActivityController<MainActivity>[] c = new ActivityController[1];
        MainActivity a = library(true, 0, vids, c);
        LibraryAdapter adapter = (LibraryAdapter) field(a, "adapter");
        a.onVideoLongClick(vids.get(0), a.findViewById(R.id.recycler));
        adapter.toggle(vids.get(1));
        idle();
        DeleteVideosFlow flow = (DeleteVideosFlow) field(a, "deleteFlow");
        // One system "Move to trash?" request for the batch, then the user tapping "Allow".
        a.deleteSelected();
        idle();
        assertEquals(1, requests.size());
        assertEquals(DeleteVideosFlow.REQ_TRASH, requests.get(0)[0]);
        assertEquals(2, requests.get(0)[1]);
        flow.onSystemResult(true);
        idle();
        assertFalse(adapter.isSelectionMode());
        List<VideoItem> all = (List<VideoItem>) field(a, "allVideos");
        assertFalse(all.contains(vids.get(0)) || all.contains(vids.get(1)));

        // Declining the system prompt keeps the selection and the videos.
        a.onVideoLongClick(vids.get(2), a.findViewById(R.id.recycler));
        idle();
        flow.trash(Collections.singletonList(vids.get(2))); // (refresh() re-queried the empty test MediaStore)
        idle();
        assertEquals(2, requests.size());
        flow.onSystemResult(false);
        idle();
        assertTrue(adapter.isSelectionMode());
        assertEquals(1, adapter.selectedCount());
        c[0].pause().stop().destroy();
    }

    @Test
    @Config(sdk = 29, qualifiers = "w411dp-h891dp-port-xxhdpi")
    @SuppressWarnings("unchecked")
    public void android10KeepsPermanentDeleteWithAClearLabel() throws Exception {
        Robolectric.setupContentProvider(FakeMediaStore.class, "media");
        assertFalse(DeleteVideosFlow.usesTrash());
        List<VideoItem> vids = new PreviewScreenshotTest().fakeVideos();
        ActivityController<MainActivity>[] c = new ActivityController[1];
        MainActivity a = library(true, 0, vids, c);
        LibraryAdapter adapter = (LibraryAdapter) field(a, "adapter");
        a.onVideoLongClick(vids.get(0), a.findViewById(R.id.recycler));
        adapter.toggle(vids.get(1));
        idle();
        a.deleteSelected();
        idle();
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("our one confirmation", d);
        assertEquals("Delete 2 videos permanently?",
                ((TextView) d.findViewById(androidx.appcompat.R.id.alertTitle)).getText().toString());
        String msg = ((TextView) d.findViewById(android.R.id.message)).getText().toString();
        assertTrue(msg, msg.contains("Android 10 has no trash"));
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertTrue(await(() -> { idle(); return FakeMediaStore.deleted.size() == 2; }, 5000));
        assertTrue("no trash requests on Android 10", requests.isEmpty());
        // No Trash entry in the menu on Android 10.
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        a.onPrepareOptionsMenu(tb.getMenu());
        if (tb.getMenu().findItem(R.id.action_trash) != null) {
            assertFalse(tb.getMenu().findItem(R.id.action_trash).isVisible());
        }
        c[0].pause().stop().destroy();
    }

    @Test
    public void confirmationTexts() {
        List<VideoItem> vids = new PreviewScreenshotTest().fakeVideos();
        assertEquals("Delete permanently?", DeleteVideosFlow.confirmTitle(1));
        assertEquals("Delete 4 videos permanently?", DeleteVideosFlow.confirmTitle(4));
        assertEquals("Deleted 1 video", DeleteVideosFlow.trashedMessage(1));
        assertEquals("Deleted 4 videos", DeleteVideosFlow.trashedMessage(4));
        String one = DeleteVideosFlow.confirmMessage(Collections.singletonList(vids.get(0)));
        assertTrue(one, one.startsWith("\u201c" + vids.get(0).title + "\u201d"));
        assertTrue(one.contains("can't be undone"));
    }

    // ------------------------------------------------------------------ private folder

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void vaultBulkDeleteGoesToPrivateTrash() throws Exception {
        Method add = Vault.class.getDeclaredMethod("add", android.content.Context.class, Vault.Entry.class);
        add.setAccessible(true);
        List<Vault.Entry> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Vault.Entry e = new Vault.Entry();
            e.id = "del" + i;
            e.file = e.id + ".mp4";
            e.name = "Private " + i + ".mp4";
            e.mime = "video/mp4";
            e.size = 1000;
            File f = Vault.fileOf(app, e);
            f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(new byte[1000]); }
            add.invoke(null, app, e);
            list.add(e);
        }
        assertEquals(3, Vault.list(app).size());
        ActivityController<VaultActivity> ctl = Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity a = ctl.get();
        idle();
        VaultLock.unlock();
        a.showContent();
        // 1.6.0: Delete moves them to the private trash (files kept, encrypted) with Undo.
        com.google.android.material.snackbar.Snackbar sb = a.confirmDelete(list);
        assertNotNull(sb);
        assertTrue(await(() -> Vault.list(app).isEmpty(), 5000));
        assertEquals(3, Vault.listTrashed(app).size());
        for (Vault.Entry e : list) assertTrue("still on disk in the trash", Vault.fileOf(app, e).exists());
        ((TextView) sb.getView().findViewById(com.google.android.material.R.id.snackbar_action)).performClick();
        assertTrue("Undo restores", await(() -> { idle(); return Vault.list(app).size() == 3; }, 5000));
        assertTrue(Vault.listTrashed(app).isEmpty());
        // Trash again, then purge after 30 days deletes them for good.
        Vault.trash(app, Vault.list(app), System.currentTimeMillis() - Vault.TRASH_MS - 1000);
        assertEquals(3, Vault.purgeExpired(app, System.currentTimeMillis()));
        assertTrue(Vault.listAll(app).isEmpty());
        for (Vault.Entry e : list) assertFalse(Vault.fileOf(app, e).exists());
        assertNull(a.confirmDelete(new ArrayList<>()));
        ctl.pause().stop().destroy();
    }
}
