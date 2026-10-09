package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.OUT;
import static com.jamh.videoplayer.PreviewScreenshotTest.fakeFrame;
import static com.jamh.videoplayer.PreviewScreenshotTest.renderToBitmap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.color.MaterialColors;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 1.7.0 previews: library chips / sort sheet / storage view, vault filters, gestures, Cast. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class V17PreviewTest {

    private static final int W = 1080, H = 2340;

    @After
    public void tearDown() {
        Caster.testRemote = null;
        Caster.lastWarning = null;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void write(Bitmap b, String name) throws Exception {
        OUT.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(OUT, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }

    private static void drawBottomSheet(Bitmap base, View sheet) {
        android.content.Context ctx = sheet.getContext();
        FrameLayout card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        float r = 28 * ctx.getResources().getDisplayMetrics().density;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.DKGRAY));
        card.setBackground(bg);
        card.addView(sheet);
        card.measure(View.MeasureSpec.makeMeasureSpec(base.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(base.getHeight(), View.MeasureSpec.AT_MOST));
        card.layout(0, 0, base.getWidth(), card.getMeasuredHeight());
        Canvas c = new Canvas(base);
        c.drawColor(0x88000000);
        c.save();
        c.translate(0, base.getHeight() - card.getMeasuredHeight());
        card.draw(c);
        c.restore();
    }

    private static void drawDialogCentered(Bitmap base, Dialog d, int maxW) {
        View dv = d.getWindow().getDecorView();
        int w = Math.min(maxW, base.getWidth() - 2 * 72);
        dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(base.getHeight() - 60, View.MeasureSpec.AT_MOST));
        dv.layout(0, 0, w, dv.getMeasuredHeight());
        Canvas c = new Canvas(base);
        c.drawColor(0x99000000);
        c.save();
        c.translate((base.getWidth() - w) / 2f, (base.getHeight() - dv.getMeasuredHeight()) / 2f);
        dv.draw(c);
        c.restore();
    }

    // ------------------------------------------------------------------ library

    private ActivityController<MainActivity> library(boolean grid, LibraryView view) throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_EXTERNAL_STORAGE);
        Prefs.setGrid(app, grid);
        Prefs.setDefaultBannerDismissed(app);
        PreviewScreenshotTest helper = new PreviewScreenshotTest();
        List<VideoItem> vids = helper.fakeVideos();
        helper.seedThumbnails(vids);
        Prefs.saveResume(app, vids.get(0).resumeKey(), vids.get(0).durationMs / 3, vids.get(0).durationMs);
        WatchHistory.mark(app, vids.get(2).resumeKey(), false);
        LibraryView.save(app, LibraryView.VIDEOS, view);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        idle();
        TestUtil.setField(a, "allVideos", vids);
        Method render = MainActivity.class.getDeclaredMethod("render");
        render.setAccessible(true);
        render.invoke(a);
        idle();
        return ctl;
    }

    @Test
    public void libraryWithFilterChips() throws Exception {
        LibraryView v = new LibraryView(LibraryView.LENGTH, true, false, 0)
                .withFilter(LibraryView.F_FHD, true).withFilter(LibraryView.F_LANDSCAPE, true);
        ActivityController<MainActivity> ctl = library(true, v);
        MainActivity a = ctl.get();
        assertTrue(a.shownVideos().size() < 12);
        write(renderToBitmap(a.getWindow().getDecorView(), W, H), "v17_library_chips.png");

        Bitmap base = renderToBitmap(a.getWindow().getDecorView(), W, H);
        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_library, null);
        LibrarySheet.bind(sheet, a.currentView(), false, new LibrarySheet.Host() {
            @Override public void onViewChanged(LibraryView nv) {
                a.setView(nv);
            }

            @Override public String summary(LibraryView nv) {
                return a.shownVideos().size() + " of 12 videos";
            }
        });
        drawBottomSheet(base, sheet);
        write(base, "v17_sort_sheet.png");
        ctl.pause().stop().destroy();
    }

    @Test
    public void storageViewWithSelection() throws Exception {
        ActivityController<MainActivity> ctl = library(false, LibraryView.defaults().withStorage(true));
        MainActivity a = ctl.get();
        assertEquals(View.VISIBLE, a.findViewById(R.id.storage_card).getVisibility());
        LibraryAdapter adapter = (LibraryAdapter) TestUtil.field(a, "adapter");
        adapter.setSelectionMode(true);
        adapter.toggle(a.shownVideos().get(0));
        adapter.toggle(a.shownVideos().get(1));
        idle();
        write(renderToBitmap(a.getWindow().getDecorView(), W, H), "v17_storage_view.png");
        ctl.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ vault

    private Vault.Entry fakeEntry(Application app, String id, String name, long dur, long size, int w, int h,
                                  long added, int seed) throws Exception {
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
        File t = Vault.thumbOf(app, e);
        t.getParentFile().mkdirs();
        Bitmap b = w >= h ? fakeFrame(360, 203, seed) : fakeFrame(203, 360, seed);
        try (FileOutputStream out = new FileOutputStream(t)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        }
        return e;
    }

    @Test
    public void vaultFiltersAndPrivateCastWarning() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        VaultLock.unlock();
        LibraryView.save(app, LibraryView.VAULT, LibraryView.defaults().withStorage(true)
                .withFilter(LibraryView.F_LANDSCAPE, true));
        ActivityController<VaultActivity> ctl = Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity a = ctl.get();
        idle();
        a.showContent();
        TextView storage = a.findViewById(R.id.vault_storage);
        assertTrue(TestUtil.await(() -> storage.getText().toString().startsWith("0 videos"), 5000));
        List<Vault.Entry> list = new ArrayList<>();
        list.add(fakeEntry(app, "a1", "Wedding speech.mp4", 312_000, 486_000_000L, 1920, 1080, 3_000, 10));
        list.add(fakeEntry(app, "a2", "Scan of passport.mp4", 14_000, 22_000_000L, 1080, 1920, 4_000, 3));
        list.add(fakeEntry(app, "a3", "Surprise party plan.mp4", 95_000, 160_000_000L, 1920, 1080, 2_000, 6));
        list.add(fakeEntry(app, "a4", "Baby's first steps.mp4", 48_000, 81_000_000L, 1920, 1080, 1_000, 1));
        long used = 0;
        for (Vault.Entry e : list) used += e.size;
        a.bind(list, used, 41L * 1024 * 1024 * 1024);
        idle();
        assertEquals(3, a.shownEntries().size());
        write(renderToBitmap(a.getWindow().getDecorView(), W, H), "v17_vault_filters.png");

        Bitmap base = renderToBitmap(a.getWindow().getDecorView(), W, H);
        AlertDialog d = Caster.get().confirmPrivate(a, "Living Room TV", () -> { });
        idle();
        drawDialogCentered(base, d, 1000);
        write(base, "v17_cast_private_warning.png");
        d.dismiss();
        ctl.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ player

    private ActivityController<PlayerActivity> launchPlayer(String qualifiersIgnored) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/Movies/Brighton%20beach%20sunset.mp4"));
        i.setClass(ApplicationProvider.getApplicationContext(), PlayerActivity.class);
        ActivityController<PlayerActivity> ctl = Robolectric.buildActivity(PlayerActivity.class, i).setup();
        TestUtil.awaitPlayer(ctl.get());
        idle();
        return ctl;
    }

    private FrameLayout dressPlayer(PlayerActivity a, boolean controls) {
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
        a.findViewById(R.id.controls).setVisibility(controls ? View.VISIBLE : View.GONE);
        a.findViewById(R.id.controls).setAlpha(1f);
        a.findViewById(R.id.buffering).setVisibility(View.GONE);
        a.findViewById(R.id.gesture_panel).setVisibility(View.GONE);
        a.findViewById(R.id.seek_info).setVisibility(View.GONE);
        return root;
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void gestureOverlays() throws Exception {
        ActivityController<PlayerActivity> ctl = launchPlayer(null);
        PlayerActivity a = ctl.get();
        FrameLayout root = dressPlayer(a, false);
        int w = 2340, h = 1080;

        // Double-tap right twice: ripple + "+20s".
        SkipRippleView ripple = a.findViewById(R.id.skip_ripple);
        TextView right = a.findViewById(R.id.skip_right);
        right.setText("+20s");
        right.setVisibility(View.VISIBLE);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        ripple.ripple(1, w * 0.82f, h * 0.48f);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120));
        write(renderToBitmap(root, w, h), "v17_double_tap.png");
        ripple.setVisibility(View.GONE);
        right.setVisibility(View.GONE);

        // Long-press: 2x pill.
        a.findViewById(R.id.speed_hold).setVisibility(View.VISIBLE);
        write(renderToBitmap(root, w, h), "v17_hold_2x.png");
        a.findViewById(R.id.speed_hold).setVisibility(View.GONE);

        // Pinch to fill: "Crop to fill" + Reset zoom pill.
        View panel = a.findViewById(R.id.gesture_panel);
        ((ImageView) a.findViewById(R.id.gesture_icon)).setImageResource(R.drawable.ic_aspect);
        ((TextView) a.findViewById(R.id.gesture_text)).setText(R.string.gesture_crop_fill);
        a.findViewById(R.id.gesture_progress).setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        ImageView frame = (ImageView) root.getChildAt(1);
        frame.setScaleType(ImageView.ScaleType.CENTER_CROP);
        a.findViewById(R.id.zoom_reset).setVisibility(View.VISIBLE);
        write(renderToBitmap(root, w, h), "v17_pinch_crop.png");
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void playerCastingAndMenu() throws Exception {
        ActivityController<PlayerActivity> ctl = launchPlayer(null);
        PlayerActivity a = ctl.get();
        FrameLayout root = dressPlayer(a, true);
        a.findViewById(R.id.btn_cast).setVisibility(View.VISIBLE); // shown when Play services can cast
        TextView banner = a.findViewById(R.id.cast_banner);
        banner.setText(a.getString(R.string.cast_playing_on, "Living Room TV"));
        banner.setVisibility(View.VISIBLE);
        write(renderToBitmap(root, 2340, 1080), "v17_player_casting.png");
        banner.setVisibility(View.GONE);
        ctl.pause().stop().destroy();
    }

    @Test
    public void playerMenuWithGestureRows() throws Exception {
        ActivityController<PlayerActivity> ctl = launchPlayer(null);
        PlayerActivity a = ctl.get();
        FrameLayout root = dressPlayer(a, true);
        Bitmap base = renderToBitmap(root, W, H);
        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_player_menu, null);
        a.bindMenuSheet(sheet, new Dialog(a));
        drawBottomSheet(base, sheet);
        write(base, "v17_player_menu.png");
        ctl.pause().stop().destroy();
    }
}
