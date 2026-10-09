package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.fakeFrame;
import static com.jamh.videoplayer.PreviewScreenshotTest.renderToBitmap;
import static com.jamh.videoplayer.PreviewScreenshotTest.save;
import static com.jamh.videoplayer.TestUtil.await;
import static com.jamh.videoplayer.TestUtil.field;
import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.Menu;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.appbar.MaterialToolbar;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** 1.4.0 (Ulfur Video): multi-select, bulk Move to Private, one-time warning with "Don't ask me again". */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class V14FeatureTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    private MainActivity library(boolean grid, int tab, List<VideoItem> vids, ActivityController<MainActivity>[] out) throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_EXTERNAL_STORAGE);
        Prefs.setGrid(app, grid);
        Prefs.setDefaultBannerDismissed(app);
        new PreviewScreenshotTest().seedThumbnails(vids);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        out[0] = ctl;
        MainActivity a = ctl.get();
        idle();
        Field all = MainActivity.class.getDeclaredField("allVideos");
        all.setAccessible(true);
        all.set(a, vids);
        Field t = MainActivity.class.getDeclaredField("tab");
        t.setAccessible(true);
        t.set(a, tab);
        com.google.android.material.tabs.TabLayout tabs = a.findViewById(R.id.tabs);
        tabs.getTabAt(tab).select();
        Method render = MainActivity.class.getDeclaredMethod("render");
        render.setAccessible(true);
        render.invoke(a);
        idle();
        return a;
    }

    @SuppressWarnings("unchecked")
    private void selection(boolean grid, int tab, String file) throws Exception {
        List<VideoItem> vids = new PreviewScreenshotTest().fakeVideos();
        ActivityController<MainActivity>[] c = new ActivityController[1];
        MainActivity a = library(grid, tab, vids, c);
        LibraryAdapter adapter = (LibraryAdapter) field(a, "adapter");
        List<VideoItem> shown = new ArrayList<>();
        for (VideoItem v : vids) if (tab != 2 || v.matchesClipFilter(Prefs.clipsFilter(app))) shown.add(v);
        // Long-press the first video: selection mode with 1 selected
        a.onVideoLongClick(shown.get(0), a.findViewById(R.id.recycler));
        idle();
        assertTrue(adapter.isSelectionMode());
        assertEquals(1, adapter.selectedCount());
        // Taps toggle
        adapter.toggle(shown.get(1));
        adapter.toggle(shown.get(2));
        idle();
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        assertEquals("3 selected", tb.getTitle().toString());
        assertEquals(View.VISIBLE, a.findViewById(R.id.selection_bar).getVisibility());
        assertEquals("Move 3 to Private", ((TextView) a.findViewById(R.id.selection_move_private)).getText().toString());
        save(a.getWindow().getDecorView(), file, 1080, 2340);
        // Toggle off again
        adapter.toggle(shown.get(2));
        assertEquals(2, adapter.selectedCount());
        // Select all, then back exits selection mode
        a.findViewById(R.id.selection_select_all).performClick();
        assertEquals(adapter.visibleVideoCount(), adapter.selectedCount());
        a.getOnBackPressedDispatcher().onBackPressed();
        idle();
        assertFalse(adapter.isSelectionMode());
        assertEquals(0, adapter.selectedCount());
        assertEquals(View.GONE, a.findViewById(R.id.selection_bar).getVisibility());
        assertEquals(a.getString(R.string.app_name), tb.getTitle().toString());
        c[0].pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void multiSelectGrid() throws Exception {
        selection(true, 0, "library_select_grid.png");
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void multiSelectList() throws Exception {
        selection(false, 0, "library_select_list.png");
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void multiSelectClips() throws Exception {
        selection(true, 2, "library_select_clips.png");
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    @SuppressWarnings("unchecked")
    public void bulkWarningShownOnceWithDontAskAgain() throws Exception {
        Prefs.setWarnBeforePrivate(app, true);
        List<VideoItem> vids = new PreviewScreenshotTest().fakeVideos();
        ActivityController<MainActivity>[] c = new ActivityController[1];
        MainActivity a = library(true, 0, vids, c);
        LibraryAdapter adapter = (LibraryAdapter) field(a, "adapter");
        a.onVideoLongClick(vids.get(0), a.findViewById(R.id.recycler));
        adapter.toggle(vids.get(1));
        adapter.toggle(vids.get(2));
        idle();
        Bitmap base = renderToBitmap(a.getWindow().getDecorView(), 1080, 2340);
        MoveToPrivateFlow flow = (MoveToPrivateFlow) field(a, "moveToPrivate");
        List<VideoItem> sel = a.selectedVideos();
        assertEquals(3, sel.size());
        AlertDialog d = flow.showConfirm(sel, null);
        idle();
        TextView msg = d.findViewById(android.R.id.message);
        assertTrue(msg.getText().toString(), msg.getText().toString().startsWith("3 videos will be copied"));
        assertTrue(msg.getText().toString().contains("Uninstalling"));
        CheckBox box = d.findViewById(R.id.dont_ask_again);
        assertNotNull(box);
        assertFalse(box.isChecked());
        box.setChecked(true);
        View dv = d.getWindow().getDecorView();
        int w = 1080 - 2 * 72;
        dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340 - 60, View.MeasureSpec.AT_MOST));
        dv.layout(0, 0, w, dv.getMeasuredHeight());
        Canvas cv = new Canvas(base);
        cv.drawColor(0x99000000);
        cv.translate(72, (2340 - dv.getMeasuredHeight()) / 2f);
        dv.draw(cv);
        V13FeatureTest.write(base, "move_to_private_bulk_confirm.png");
        assertTrue(Prefs.warnBeforePrivate(app));
        // Confirm with the box ticked: persisted, never shown again
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse(Prefs.warnBeforePrivate(app));
        // Overflow menu toggle turns it back on
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        a.exitSelection();
        idle();
        Menu m = tb.getMenu();
        assertNotNull(m.findItem(R.id.action_warn_private));
        assertFalse(m.findItem(R.id.action_warn_private).isChecked());
        a.onOptionsItemSelected(m.findItem(R.id.action_warn_private));
        assertTrue(Prefs.warnBeforePrivate(app));
        c[0].pause().stop().destroy();
    }

    // ------------------------------------------------------------------ vault

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
    public void vaultMultiSelect() throws Exception {
        ActivityController<VaultActivity> ctl = Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity a = ctl.get();
        idle();
        VaultLock.unlock();
        List<Vault.Entry> list = new ArrayList<>();
        list.add(fakeEntry("a1", "Wedding speech.mp4", 312_000, 486_000_000L, 1920, 1080, 10));
        list.add(fakeEntry("a2", "Scan of passport.mp4", 14_000, 22_000_000L, 1080, 1920, 3));
        list.add(fakeEntry("a3", "Surprise party plan.mp4", 95_000, 160_000_000L, 1920, 1080, 6));
        list.add(fakeEntry("a4", "Baby's first steps.mp4", 48_000, 81_000_000L, 1920, 1080, 1));
        a.showContent();
        TextView storage = a.findViewById(R.id.vault_storage);
        assertTrue(await(() -> storage.getText().toString().startsWith("0 videos"), 5000));
        a.bind(list, 749_000_000L, 41L * 1024 * 1024 * 1024);
        a.setSelecting(true);
        a.toggle(list.get(0));
        a.toggle(list.get(2));
        View decor = a.getWindow().getDecorView();
        renderToBitmap(decor, 1080, 2340);
        await(() -> false, 600);
        MaterialToolbar tb = a.findViewById(R.id.vault_toolbar);
        assertEquals("2 selected", tb.getTitle().toString());
        assertEquals(View.VISIBLE, a.findViewById(R.id.vault_selection_bar).getVisibility());
        assertEquals("Move 2 out of Private", ((TextView) a.findViewById(R.id.vault_move_out_selected)).getText().toString());
        save(decor, "vault_select.png", 1080, 2340);
        a.getOnBackPressedDispatcher().onBackPressed();
        assertEquals(View.GONE, a.findViewById(R.id.vault_selection_bar).getVisibility());
        assertEquals(a.getString(R.string.vault_title), tb.getTitle().toString());
        ctl.pause().stop().destroy();
        for (Vault.Entry e : list) Vault.thumbOf(app, e).delete();
    }

    // ------------------------------------------------------------------ 1.4.1 media management

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    @SuppressWarnings("unchecked")
    public void mediaManagementPromptOnceWithDontAskAgain() throws Exception {
        MoveToPrivateFlow.resetSessionPromptForTest();
        Prefs.setMediaManagePromptOff(app, false);
        MediaAccess.overrideForTest = false;
        List<VideoItem> vids = new PreviewScreenshotTest().fakeVideos();
        ActivityController<MainActivity>[] c = new ActivityController[1];
        MainActivity a = library(true, 0, vids, c);
        assertFalse(MediaAccess.canManageMedia(a));
        MoveToPrivateFlow flow = (MoveToPrivateFlow) field(a, "moveToPrivate");
        assertTrue(flow.shouldOfferMediaManagement());
        // Menu entry exists on Android 12+
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        assertTrue(tb.getMenu().findItem(R.id.action_media_manage).isVisible());

        Bitmap base = renderToBitmap(a.getWindow().getDecorView(), 1080, 2340);
        final boolean[] continued = {false};
        AlertDialog d = flow.showMediaManagementPrompt(() -> continued[0] = true);
        idle();
        TextView msg = d.findViewById(android.R.id.message);
        assertTrue(msg.getText().toString().contains("Allow media management so Android stops asking every time"));
        assertEquals(a.getString(R.string.open_settings), d.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertEquals(a.getString(R.string.not_now), d.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());
        View dv = d.getWindow().getDecorView();
        int w = 1080 - 2 * 72;
        dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340 - 60, View.MeasureSpec.AT_MOST));
        dv.layout(0, 0, w, dv.getMeasuredHeight());
        Canvas cv = new Canvas(base);
        cv.drawColor(0x99000000);
        cv.translate(72, (2340 - dv.getMeasuredHeight()) / 2f);
        dv.draw(cv);
        V13FeatureTest.write(base, "media_management_prompt.png");
        // Once per session even without "Don't ask again"
        assertFalse(flow.shouldOfferMediaManagement());
        ((CheckBox) d.findViewById(R.id.dont_ask_again)).setChecked(true);
        d.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();
        assertTrue("Not now continues the move", continued[0]);
        assertTrue(Prefs.mediaManagePromptOff(app));
        MoveToPrivateFlow.resetSessionPromptForTest();
        assertFalse("Don't ask again is persisted", flow.shouldOfferMediaManagement());

        // "Open settings" launches Settings.ACTION_REQUEST_MANAGE_MEDIA for our package
        assertTrue(flow.openManageMediaSettings());
        android.content.Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, next.getAction());
        assertEquals("package:" + a.getPackageName(), next.getData().toString());
        // Granted: never offered, menu shows it ticked
        MediaAccess.overrideForTest = true;
        MoveToPrivateFlow.resetSessionPromptForTest();
        Prefs.setMediaManagePromptOff(app, false);
        assertFalse(flow.shouldOfferMediaManagement());
        c[0].pause().stop().destroy();
        MediaAccess.overrideForTest = null;
    }

    // ------------------------------------------------------------------ 1.4.2 seeking

    @Test
    public void scrubReleaseDoesNotDoubleSeek() {
        // Tap / drag that ended where the last preview (keyframe) seek went: no second, EXACT seek.
        assertEquals(PlayerActivity.RELEASE_NONE, PlayerActivity.releaseSeekMode(false, 600_000, 600_000));
        // Finger moved after the last throttled preview seek: one keyframe seek, still not EXACT.
        assertEquals(PlayerActivity.RELEASE_SNAP, PlayerActivity.releaseSeekMode(false, 590_000, 600_000));
        assertEquals(PlayerActivity.RELEASE_SNAP, PlayerActivity.releaseSeekMode(false, -1, 600_000));
        // Fine (0.1 s) scrubbing stays frame-exact.
        assertEquals(PlayerActivity.RELEASE_EXACT, PlayerActivity.releaseSeekMode(true, 600_000, 600_000));
    }

    @Test
    public void localPlaybackRestartsQuicklyAfterSeek() {
        assertTrue(PlayerFactory.LOCAL_BUFFER_FOR_PLAYBACK_MS <= 250);
        assertTrue(PlayerFactory.LOCAL_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS <= 500);
        // Streams keep the safer pre-roll.
        assertEquals(1_500, PlayerFactory.BUFFER_FOR_PLAYBACK_MS);
    }
}
