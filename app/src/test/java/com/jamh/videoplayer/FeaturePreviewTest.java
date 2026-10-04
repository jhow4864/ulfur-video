package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.OUT;
import static com.jamh.videoplayer.PreviewScreenshotTest.fakeFrame;
import static com.jamh.videoplayer.PreviewScreenshotTest.renderToBitmap;
import static com.jamh.videoplayer.PreviewScreenshotTest.save;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

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

import androidx.test.core.app.ApplicationProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

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
import java.util.List;

/** Smoke tests + previews for the v1.2 features (player menu, sleep timer, fine scrub, editor, clips feed). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeaturePreviewTest {

    private static void write(Bitmap b, String name) throws Exception {
        OUT.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(OUT, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private ActivityController<PlayerActivity> launchPlayer() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/Movies/Brighton%20beach%20sunset.mp4"));
        i.setClass(ApplicationProvider.getApplicationContext(), PlayerActivity.class);
        return Robolectric.buildActivity(PlayerActivity.class, i).setup();
    }

    /** Player with a stand-in video frame and visible controls (no real decoding under Robolectric). */
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
        return root;
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

    // ------------------------------------------------------------------ player menu

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void playerMenuSheet() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        Prefs.setDialogueBoost(app, true);
        Prefs.setBackgroundAudio(app, true);
        ActivityController<PlayerActivity> ctl = launchPlayer();
        PlayerActivity a = ctl.get();
        Field df = PlayerActivity.class.getDeclaredField("dialogueBoost");
        df.setAccessible(true);
        DialogueBoostProcessor proc = (DialogueBoostProcessor) df.get(a);
        assertNotNull("dialogue processor wired into the audio sink", proc);
        assertTrue("persisted dialogue toggle restored", proc.isEnabled());
        Field ms = PlayerActivity.class.getDeclaredField("mediaSession");
        ms.setAccessible(true);
        assertNotNull("media session created", ms.get(a));

        a.startSleepTimer(30);
        idle();
        assertEquals(View.VISIBLE, a.findViewById(R.id.badge_night).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.badge_sleep).getVisibility());
        FrameLayout root = dressPlayer(a, true);
        int w = 1080, h = 2340;
        Bitmap base = renderToBitmap(root, w, h);

        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_player_menu, null);
        a.bindMenuSheet(sheet, new Dialog(a));
        FrameLayout card = new FrameLayout(a);
        GradientDrawable bg = new GradientDrawable();
        float r = 28 * a.getResources().getDisplayMetrics().density;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(MaterialColors.getColor(a, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.DKGRAY));
        card.setBackground(bg);
        card.addView(sheet);
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
        card.layout(0, 0, w, card.getMeasuredHeight());
        Canvas c = new Canvas(base);
        c.drawColor(0x88000000);
        c.save();
        c.translate(0, h - card.getMeasuredHeight());
        card.draw(c);
        c.restore();
        write(base, "player_menu.png");

        // Toggling the switch in the sheet updates the processor and the persisted pref.
        com.google.android.material.materialswitch.MaterialSwitch sw = sheet.findViewById(R.id.switch_dialogue);
        sw.setChecked(false);
        assertFalse(Prefs.dialogueBoost(app));
        assertFalse(proc.isEnabled());
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void sleepTimerDialog() throws Exception {
        ActivityController<PlayerActivity> ctl = launchPlayer();
        PlayerActivity a = ctl.get();
        FrameLayout root = dressPlayer(a, false);
        Bitmap base = renderToBitmap(root, 2340, 1080);
        a.showSleepTimerDialog();
        idle();
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        drawDialogCentered(base, d, 1100);
        write(base, "player_sleep_timer.png");
        d.dismiss();
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void fineScrubOverlay() throws Exception {
        ActivityController<PlayerActivity> ctl = launchPlayer();
        PlayerActivity a = ctl.get();
        FrameLayout root = dressPlayer(a, false);
        Field zone = PlayerActivity.class.getDeclaredField("scrubZone");
        zone.setAccessible(true);
        zone.setInt(a, 2);
        Field labels = PlayerActivity.class.getDeclaredField("SCRUB_LABELS");
        labels.setAccessible(true);
        String fine = ((String[]) labels.get(null))[2];
        Method show = PlayerActivity.class.getDeclaredMethod("showSeekInfo", long.class, Long.class, String.class);
        show.setAccessible(true);
        show.invoke(a, 101_300L, 1_300L, fine);
        TextView info = a.findViewById(R.id.seek_info);
        assertTrue(info.getText().toString().contains("Fine scrubbing"));
        save(root, "player_scrub_fine.png", 2340, 1080);
        ctl.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ clip editor

    private ActivityController<ClipEditorActivity> launchEditor() {
        Intent i = new Intent(ApplicationProvider.getApplicationContext(), ClipEditorActivity.class);
        i.setData(Uri.parse("file:///sdcard/Movies/Brighton%20beach%20sunset.mp4"));
        i.putExtra(ClipEditorActivity.EXTRA_TITLE, "Brighton beach sunset.mp4");
        i.putExtra(ClipEditorActivity.EXTRA_POSITION_MS, 101_000L);
        i.putExtra(ClipEditorActivity.EXTRA_DURATION_MS, 263_000L);
        return Robolectric.buildActivity(ClipEditorActivity.class, i).setup();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void clipEditor() throws Exception {
        ActivityController<ClipEditorActivity> ctl = launchEditor();
        ClipEditorActivity a = ctl.get();
        ((ImageView) a.findViewById(R.id.editor_poster)).setImageBitmap(fakeFrame(1600, 900, 2));
        ClipExporter.Spec spec = a.spec();
        assertEquals(101_000L, spec.startMs);
        assertEquals(106_000L, spec.endMs);

        // Frame nudges (fallback 30 fps → 33 ms) and 0.1 s nudges.
        a.nudge(true, a.frameMs());
        assertEquals(101_033L, spec.startMs);
        a.nudge(false, -100);
        assertEquals(105_900L, spec.endMs);
        // Long video → trim slider is zoomed to a 60 s window around the selection.
        com.google.android.material.slider.RangeSlider range = a.findViewById(R.id.editor_range);
        assertTrue(range.getValueTo() - range.getValueFrom() <= 60_000f);
        assertTrue(range.getValueFrom() <= spec.startMs && range.getValueTo() >= spec.endMs);

        ((Chip) a.findViewById(R.id.crop_1_1)).setChecked(true);
        assertNotNull("1:1 crop active", spec.crop);
        ((TextView) a.findViewById(R.id.text_input)).setText("Best sunset ever");
        ChipGroup colors = a.findViewById(R.id.text_color_group);
        ((Chip) colors.getChildAt(1)).setChecked(true);
        assertEquals(0xFFFFD60A, spec.textColor);
        a.findViewById(R.id.text_input).clearFocus();
        idle();
        save(a.getWindow().getDecorView(), "clip_editor.png", 1080, 2340);

        // GIF mode with a 20 s range → large-GIF warning.
        a.selectGif(true);
        a.nudge(false, 14_200);
        idle();
        assertTrue(spec.gif);
        assertEquals(View.VISIBLE, a.findViewById(R.id.editor_warning).getVisibility());
        ((Chip) a.findViewById(R.id.crop_none)).setChecked(true);
        a.findViewById(R.id.text_input).clearFocus();
        View decor = a.getWindow().getDecorView();
        renderToBitmap(decor, 1080, 2340);                 // first layout pass
        androidx.core.widget.NestedScrollView scroll = a.findViewById(R.id.editor_scroll);
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());  // show output options + warning
        save(decor, "clip_editor_gif.png", 1080, 2340);

        // Export progress dialog (rendered with the real layout; no Transformer under Robolectric).
        Bitmap base = renderToBitmap(a.getWindow().getDecorView(), 1080, 2340);
        View content = a.getLayoutInflater().inflate(R.layout.dialog_export_progress, null);
        LinearProgressIndicator bar = content.findViewById(R.id.export_progress);
        bar.setIndeterminate(false);
        bar.setMax(100);
        bar.setProgress(42);
        ((TextView) content.findViewById(R.id.export_status)).setText("42%");
        Dialog d = new MaterialAlertDialogBuilder(a).setTitle("Making GIF").setView(content)
                .setNegativeButton(android.R.string.cancel, null).show();
        idle();
        drawDialogCentered(base, d, 1080 - 2 * 72);
        write(base, "clip_export_progress.png");
        d.dismiss();
        ctl.pause().stop().destroy();
    }

    @Test
    public void exportFileNamesAreSafe() {
        ClipExporter.Spec s = new ClipExporter.Spec();
        s.title = "Concert – Manchester (live)/final.mkv";
        String n = ClipExporter.fileName(s, "gif");
        assertTrue(n, n.matches("[A-Za-z0-9 _-]+_\\d{8}_\\d{6}\\.gif"));
    }

    @Test
    public void gifEstimateGrowsWithLengthAndWidth() {
        ClipExporter.Spec s = new ClipExporter.Spec();
        s.gif = true;
        s.startMs = 0;
        s.endMs = 5_000;
        long small = ClipExporter.estimateBytes(s, 1920, 1080);
        s.endMs = 20_000;
        long longer = ClipExporter.estimateBytes(s, 1920, 1080);
        s.gifWidth = 640;
        long wider = ClipExporter.estimateBytes(s, 1920, 1080);
        assertTrue(longer > small * 3 && wider > longer);
    }

    // ------------------------------------------------------------------ clips feed

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void clipsFeed() throws Exception {
        String[] names = {"Snowdonia summit.mp4", "Dog on the beach.mp4", "Skate park.mp4", "Fireworks.mp4"};
        List<VideoItem> clips = new ArrayList<>();
        java.lang.reflect.Field cacheField = ThumbnailLoader.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        android.util.LruCache<Long, Bitmap> cache = (android.util.LruCache<Long, Bitmap>) cacheField.get(null);
        for (int i = 0; i < names.length; i++) {
            VideoItem v = new VideoItem(5000 + i, Uri.parse("content://media/external/video/media/" + (5000 + i)),
                    names[i], (12 + i * 9) * 1000L, 9_000_000L, 1_790_000_000L - i * 3600, "cam", "Camera", 1080, 1920);
            clips.add(v);
            cache.put(v.id, fakeFrame(540, 960, i + 3));
        }
        assertTrue(clips.get(0).matchesClipFilter(1));
        ClipsFeedActivity.pendingClips = clips;
        Intent i = new Intent(ApplicationProvider.getApplicationContext(), ClipsFeedActivity.class)
                .putExtra(ClipsFeedActivity.EXTRA_START, 1);
        ActivityController<ClipsFeedActivity> ctl = Robolectric.buildActivity(ClipsFeedActivity.class, i).setup();
        ClipsFeedActivity a = ctl.get();
        idle();
        ViewPager2 pager = a.findViewById(R.id.pager);
        assertEquals(ViewPager2.ORIENTATION_VERTICAL, pager.getOrientation());
        assertEquals(1, pager.getCurrentItem());
        Field pf = ClipsFeedActivity.class.getDeclaredField("players");
        pf.setAccessible(true);
        Object[] players = (Object[]) pf.get(a);
        assertTrue("player pool is capped at 2", players.length <= 2);
        save(a.getWindow().getDecorView(), "clips_feed.png", 1080, 2340);
        ctl.pause().stop().destroy();
    }
}
