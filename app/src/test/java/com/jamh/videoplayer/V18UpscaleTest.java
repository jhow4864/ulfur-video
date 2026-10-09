package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.save;
import static com.jamh.videoplayer.TestUtil.await;
import static com.jamh.videoplayer.TestUtil.field;
import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 1.8.0: Sharp = Anime4K (routing, fallback to Balanced, HDR bypass, assets) + Upscale on export. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class V18UpscaleTest {

    @After
    public void tearDown() {
        RealEsrgan.setCachedForTest(null);
    }

    // ------------------------------------------------------------------ mode routing

    @Test
    public void sharpRoutesToAnime4kAtSourceResolutionThenResamples() {
        // 480p on a 1080p screen: CNN 2x at the source size (1708x960), then EASU up to 1920x1080.
        Upscaler.Plan p = Upscaler.route(false, 854, 480, 1920, 1080, Upscaler.MODE_SHARP, Upscaler.QUALITY_FSR);
        assertEquals(Upscaler.PLAN_A4K_UPSCALE, p.kind);
        assertEquals(1708, p.cnnWidth);
        assertEquals(960, p.cnnHeight);
        assertEquals(1920, p.outWidth);
        assertEquals(1080, p.outHeight);
        assertEquals(Upscaler.RESAMPLE_EASU, p.resample);
        assertEquals("no RCAS/CAS on top of Anime4K", 0f, p.sharpness, 0f);
        assertTrue(p.describe(854, 480), p.describe(854, 480).contains("Anime4K 2× CNN + FSR EASU"));

        // 720p on a 1080p screen: the 2x (2560x1440) is larger than the output -> bicubic down.
        Upscaler.Plan d = Upscaler.route(false, 1280, 720, 1920, 1080, Upscaler.MODE_SHARP, Upscaler.QUALITY_FSR);
        assertEquals(Upscaler.PLAN_A4K_UPSCALE, d.kind);
        assertEquals(2560, d.cnnWidth);
        assertEquals(Upscaler.RESAMPLE_LITE, d.resample);
        assertEquals(1920, d.outWidth);

        // Exactly 2x: the CNN writes straight into the output.
        Upscaler.Plan x = Upscaler.route(false, 960, 540, 1920, 1080, Upscaler.MODE_SHARP, Upscaler.QUALITY_FSR);
        assertEquals(Upscaler.RESAMPLE_NONE, x.resample);
        assertEquals(1920, x.cnnWidth);

        // Lite quality (after an FSR failure): the resampler is Catmull-Rom.
        assertEquals(Upscaler.RESAMPLE_LITE, Upscaler.route(false, 854, 480, 1920, 1080,
                Upscaler.MODE_SHARP, Upscaler.QUALITY_LITE).resample);
    }

    @Test
    public void sharpOnAFullScreenVideoIsRestoreOnlyAndAbove1080pIsBalanced() {
        Upscaler.Plan r = Upscaler.route(false, 1920, 1080, 1920, 1080, Upscaler.MODE_SHARP, Upscaler.QUALITY_FSR);
        assertEquals(Upscaler.PLAN_A4K_RESTORE, r.kind);
        assertEquals(1920, r.outWidth);
        assertEquals(1080, r.outHeight);
        assertTrue(r.describe(1920, 1080).contains("Restore only"));
        // 1080p shown in a smaller window (downscaled by the compositor): still Restore only.
        assertEquals(Upscaler.PLAN_A4K_RESTORE, Upscaler.route(false, 1920, 1080, 1280, 720,
                Upscaler.MODE_SHARP, Upscaler.QUALITY_FSR).kind);
        // Above 1080p Sharp behaves exactly like Balanced.
        for (int[] s : new int[][]{{3840, 2160, 2400, 1080}, {2560, 1440, 2560, 1440}, {2560, 1440, 3840, 2160}}) {
            Upscaler.Plan sharp = Upscaler.route(false, s[0], s[1], s[2], s[3], Upscaler.MODE_SHARP, Upscaler.QUALITY_FSR);
            Upscaler.Plan bal = Upscaler.plan(s[0], s[1], s[2], s[3], Upscaler.MODE_BALANCED);
            assertFalse(sharp.isAnime4k());
            assertEquals(bal.kind, sharp.kind);
            assertEquals(bal.outWidth, sharp.outWidth);
            assertEquals(bal.sharpness, sharp.sharpness, 0f);
        }
    }

    @Test
    public void offAndBalancedAreTheUnchangedFsrPaths() {
        int[][] cases = {{640, 360, 1920, 1080}, {1280, 720, 1920, 1080}, {1920, 1080, 1920, 1080},
                {360, 640, 1080, 1920}, {854, 480, 2560, 1440}, {320, 240, 1920, 1080}};
        for (int[] c : cases) {
            for (int mode : new int[]{Upscaler.MODE_OFF, Upscaler.MODE_BALANCED}) {
                for (int q : new int[]{Upscaler.QUALITY_FSR, Upscaler.QUALITY_LITE}) {
                    Upscaler.Plan a = Upscaler.route(false, c[0], c[1], c[2], c[3], mode, q);
                    Upscaler.Plan b = Upscaler.plan(c[0], c[1], c[2], c[3], mode);
                    assertEquals(b.kind, a.kind);
                    assertEquals(b.outWidth, a.outWidth);
                    assertEquals(b.outHeight, a.outHeight);
                    assertEquals(b.sharpness, a.sharpness, 0f);
                }
            }
        }
        // Balanced is still FSR 1.0 EASU + RCAS with the 1.5.0 strength.
        Upscaler.Plan bal = Upscaler.route(false, 640, 360, 1920, 1080, Upscaler.MODE_BALANCED, Upscaler.QUALITY_FSR);
        assertEquals(Upscaler.PLAN_UPSCALE, bal.kind);
        assertEquals(Upscaler.sharpnessForStops(Upscaler.BALANCED_STOPS), bal.sharpness, 1e-6);
    }

    @Test
    public void pickerLabelsSharpForAnimeAndCartoons() {
        String[] items = Upscaler.pickerItems();
        assertEquals(3, items.length);
        assertEquals("Off", items[0]);
        assertTrue(items[1].contains("FSR"));
        assertTrue(items[2], items[2].startsWith("Sharp") && items[2].contains("best for anime & cartoons"));
    }

    // ------------------------------------------------------------------ fallback

    @Test
    public void sharpGuardDropsOnlyOnASustainedOverBudgetWindow() {
        Upscaler.SharpGuard g = new Upscaler.SharpGuard();
        assertEquals(26.67, Upscaler.SharpGuard.budgetMs(30), 0.01);   // 80 % of 33.3 ms
        assertEquals(13.33, Upscaler.SharpGuard.budgetMs(60), 0.01);
        assertTrue(g.onWindow(30.0, 60, 30));    // 2 s of 30 ms frames at 30 fps: too slow
        assertFalse(g.onWindow(20.0, 60, 30));   // comfortably inside the budget
        assertTrue(g.onWindow(20.0, 120, 60));   // the same GPU time is too slow at 60 fps
        assertTrue(g.onWindow(20.0, 120, 30 * 2.0)); // 2x playback speed doubles the frame rate
        assertFalse("a single slow frame never trips it", g.onWindow(90.0, 1, 30));
        assertFalse(g.onWindow(90.0, Upscaler.SharpGuard.MIN_SAMPLES - 1, 30));
        assertFalse("unknown frame rate", g.onWindow(90.0, 60, 0));
    }

    @Test
    public void frameTimesAverageTheWindowAndIgnoreBogusSamples() {
        Upscaler.FrameTimes t = new Upscaler.FrameTimes();
        t.add(10_000_000L);
        t.add(20_000_000L);
        t.add(0);                    // disjoint / empty query
        t.add(5_000_000_000L);       // nonsense
        double[] w = t.drain();
        assertEquals(15.0, w[0], 1e-9);
        assertEquals(2, (int) w[1]);
        assertEquals(15.0, t.lastMeanMs(), 1e-9);
        assertEquals(0, (int) t.drain()[1]); // window cleared
        t.markSharpUnsupported("needs OpenGL ES 3");
        assertTrue(t.sharpUnsupported());
        t.clearSharpUnsupported();
        assertFalse(t.sharpUnsupported());
    }

    @Test
    public void effectiveModeDropsSharpToBalancedOnly() {
        assertEquals(Upscaler.MODE_BALANCED, Upscaler.effectiveMode(Upscaler.MODE_SHARP, true));
        assertEquals(Upscaler.MODE_SHARP, Upscaler.effectiveMode(Upscaler.MODE_SHARP, false));
        assertEquals(Upscaler.MODE_BALANCED, Upscaler.effectiveMode(Upscaler.MODE_BALANCED, true));
        assertEquals(Upscaler.MODE_OFF, Upscaler.effectiveMode(Upscaler.MODE_OFF, true));
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void playerDropsSharpToBalancedForTheSessionWithAToast() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        Prefs.setUpscaleMode(app, Upscaler.MODE_SHARP);
        ActivityController<PlayerActivity> ctl = launchPlayer(app);
        PlayerActivity a = ctl.get();
        assertTrue((boolean) field(a, "upscalePipeline"));
        assertEquals(Upscaler.MODE_SHARP, a.effectiveUpscaleMode());
        assertTrue(a.upscaleSheetSubtitle(), a.upscaleSheetSubtitle().contains("Anime4K"));
        await(() -> ((String) field(a, "appliedEffectsKey")).startsWith(Upscaler.MODE_SHARP + "/"), 3000);
        assertTrue((String) field(a, "appliedEffectsKey"), ((String) field(a, "appliedEffectsKey")).startsWith(Upscaler.MODE_SHARP + "/"));

        // The GL side reports that Sharp can't run here -> the next watchdog tick drops it.
        a.upscaleFrameTimes.markSharpUnsupported("needs OpenGL ES 3 (OpenGL ES 2.0)");
        Method tick = PlayerActivity.class.getDeclaredMethod("onUpscaleWatchdogTick");
        tick.setAccessible(true);
        tick.invoke(a);
        idle();
        assertEquals(Upscaler.MODE_BALANCED, a.effectiveUpscaleMode());
        assertEquals("the saved choice stays Sharp", Upscaler.MODE_SHARP, Prefs.upscaleMode(app));
        assertTrue((String) field(a, "appliedEffectsKey"), ((String) field(a, "appliedEffectsKey")).startsWith(Upscaler.MODE_BALANCED + "/"));
        assertEquals("Sharp → Balanced", ((TextView) a.findViewById(R.id.upscale_toast_title)).getText().toString());
        assertEquals(View.VISIBLE, a.findViewById(R.id.upscale_toast).getVisibility());
        assertTrue(a.upscaleSheetSubtitle(), a.upscaleSheetSubtitle().startsWith("Sharp → Balanced"));
        assertTrue("pipeline kept (Balanced runs)", (boolean) field(a, "upscalePipeline"));

        // Choosing Sharp again gives it another try.
        a.setUpscaleMode(Upscaler.MODE_SHARP);
        idle();
        assertEquals(Upscaler.MODE_SHARP, a.effectiveUpscaleMode());
        // Over budget for a window -> Balanced again (direct call: Robolectric has no GPU timings).
        a.downgradeSharp("GPU 40 ms/frame, budget 27 ms");
        assertEquals(Upscaler.MODE_BALANCED, a.effectiveUpscaleMode());
        assertTrue(((TextView) a.findViewById(R.id.upscale_toast_detail)).getText().toString().contains("GPU 40 ms/frame"));
        ctl.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ HDR bypass

    @Test
    public void hdrIsNeverProcessedInAnyMode() {
        for (int mode : new int[]{Upscaler.MODE_OFF, Upscaler.MODE_BALANCED, Upscaler.MODE_SHARP}) {
            for (int[] s : new int[][]{{854, 480}, {1920, 1080}, {3840, 2160}}) {
                Upscaler.Plan p = Upscaler.route(true, s[0], s[1], 1920, 1080, mode, Upscaler.QUALITY_FSR);
                assertEquals(Upscaler.PLAN_COPY, p.kind);
                assertEquals(s[0], p.outWidth);
                assertEquals(s[1], p.outHeight);
                assertFalse(p.isAnime4k());
            }
            assertFalse("no GL pipeline for HDR", Upscaler.pipelineWanted(mode, false, true));
        }
        assertTrue(Upscaler.pipelineWanted(Upscaler.MODE_SHARP, false, false));
        assertFalse(Upscaler.pipelineWanted(Upscaler.MODE_SHARP, true, false));
        assertFalse(Upscaler.pipelineWanted(Upscaler.MODE_OFF, false, false));
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void hdrVideoInSharpModeRebuildsWithoutThePipeline() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        Prefs.setUpscaleMode(app, Upscaler.MODE_SHARP);
        ActivityController<PlayerActivity> ctl = launchPlayer(app);
        PlayerActivity a = ctl.get();
        assertTrue((boolean) field(a, "upscalePipeline"));
        TestUtil.setField(a, "currentItemHdr", true);
        Method wanted = PlayerActivity.class.getDeclaredMethod("upscaleWanted");
        wanted.setAccessible(true);
        assertFalse((boolean) wanted.invoke(a));
        Method rebuild = PlayerActivity.class.getDeclaredMethod("rebuildAtCurrentPosition");
        rebuild.setAccessible(true);
        rebuild.invoke(a); // what onTracksChanged does for an HDR track
        idle();
        assertFalse("HDR: player rebuilt without the GL pipeline", (boolean) field(a, "upscalePipeline"));
        assertTrue(a.upscaleSheetSubtitle(), a.upscaleSheetSubtitle().contains("paused for HDR"));
        ctl.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ shader assets

    @Test
    public void anime4kShadersShipWithCreditAndMatchThePipeline() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        String restore = asset(app, Anime4kPipeline.RESTORE);
        String upscale = asset(app, Anime4kPipeline.UPSCALE);
        String vert = asset(app, Anime4kPipeline.VERTEX);
        String lic = asset(app, "licenses/ANIME4K_LICENSE.txt");
        assertTrue(lic.contains("bloc97") && lic.contains("Permission is hereby granted, free of charge"));
        for (String s : new String[]{restore, upscale}) {
            assertTrue(s.contains("bloc97") && s.contains("MIT License"));
            assertTrue(s.contains("uniform ivec2 uSrcSize"));
            assertFalse("the app prepends the version line", s.startsWith("#version"));
        }
        assertTrue(restore.contains("// PASS_COUNT " + Anime4kPipeline.RESTORE_PASSES));
        assertTrue(upscale.contains("// PASS_COUNT " + Anime4kPipeline.UPSCALE_PASSES));
        assertTrue(restore.contains("Restore-CNN-(M)") && upscale.contains("Upscale-CNN-x2-(M)"));
        assertTrue(vert.startsWith("#version 300 es") && vert.contains("in vec4 aFramePosition"));
        for (int n = 0; n < Anime4kPipeline.RESTORE_PASSES; n++) {
            assertEquals("restore pass " + n, Arrays.asList(Anime4kPipeline.restoreSamplers(n)), samplers(restore, n));
        }
        for (int n = 0; n < Anime4kPipeline.UPSCALE_PASSES; n++) {
            assertEquals("upscale pass " + n, Arrays.asList(Anime4kPipeline.upscaleSamplers(n)), samplers(upscale, n));
        }
        assertTrue(Anime4kPipeline.passSource("x", 3).startsWith("#version 300 es\n#define PASS 3\n"));
        // Licences for the export upscaler too.
        assertTrue(asset(app, "licenses/REALESRGAN_LICENSE.txt").contains("BSD 3-Clause"));
        assertTrue(asset(app, "licenses/NCNN_LICENSE.txt").contains("BSD 3-Clause"));
        assertNotNull(asset(app, RealEsrgan.PARAM));
    }

    private static List<String> samplers(String src, int pass) {
        int start = src.indexOf("#if PASS == " + pass + "\n");
        int end = src.indexOf("#endif", start);
        assertTrue("pass " + pass + " present", start >= 0 && end > start);
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("uniform sampler2D (\\w+);").matcher(src.substring(start, end));
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static String asset(Application app, String path) throws Exception {
        try (InputStream in = app.getAssets().open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ Upscale on export

    @Test
    public void exportUpscaleSizesEstimatesAndStatus() {
        assertArrayEquals(new int[]{1706, 960}, ExportUpscaler.outputSize(853, 480));
        assertArrayEquals(new int[]{2560, 1440}, ExportUpscaler.outputSize(1280, 720));
        assertTrue(ExportUpscaler.bitrateFor(2560, 1440) > ExportUpscaler.bitrateFor(1280, 720));
        ClipExporter.Spec s = new ClipExporter.Spec();
        s.startMs = 0;
        s.endMs = 10_000;
        assertFalse("off by default", s.upscale2x);
        long plain = ClipExporter.estimateBytes(s, 854, 480);
        s.upscale2x = true;
        assertTrue(ClipExporter.estimateBytes(s, 854, 480) > plain);
        assertEquals("Upscaling 2× · frame 12 · about 3 min left", ClipExporter.upscaleStatus(12, 170_000));
        assertEquals("Upscaling 2× · frame 3", ClipExporter.upscaleStatus(3, -1));
        assertEquals("Upscaling 2× · frame 290 · under a minute left", ClipExporter.upscaleStatus(290, 4_000));
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void editorOffersUpscaleOnExportOnlyWhenThePhoneCan() throws Exception {
        RealEsrgan.setCachedForTest(new RealEsrgan.Availability(false,
                "Needs Vulkan GPU compute, which this phone doesn't offer.", null));
        ActivityController<ClipEditorActivity> ctl = launchEditor();
        ClipEditorActivity a = ctl.get();
        View row = a.findViewById(R.id.upscale_export_options);
        MaterialSwitch sw = a.findViewById(R.id.upscale_export);
        TextView hint = a.findViewById(R.id.upscale_export_hint);
        if (!BuildConfig.UPSCALE_EXPORT) {
            assertEquals("feature flag off: hidden", View.GONE, row.getVisibility());
            ctl.pause().stop().destroy();
            return;
        }
        assertEquals(View.VISIBLE, row.getVisibility());
        assertFalse("no Vulkan: disabled", sw.isEnabled());
        assertTrue(hint.getText().toString(), hint.getText().toString().startsWith("Unavailable: Needs Vulkan"));

        a.applyUpscaleAvailability(new RealEsrgan.Availability(true, null, "Mali-G68"));
        assertTrue(sw.isEnabled());
        assertTrue(hint.getText().toString().contains("Real-ESRGAN AnimeVideo v3 on Mali-G68"));
        sw.setChecked(true);
        assertTrue(a.spec().upscale2x);
        assertTrue(((TextView) a.findViewById(R.id.editor_estimate)).getText().toString().contains("upscaled 2×"));
        save(a.getWindow().getDecorView().getRootView(), "v18_clip_upscale_export.png", 1080, 2340);

        a.selectGif(true);
        idle();
        assertEquals("GIF: not offered", View.GONE, row.getVisibility());
        a.selectGif(false);
        idle();
        assertEquals(View.VISIBLE, row.getVisibility());
        ctl.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ helpers

    private static ActivityController<PlayerActivity> launchPlayer(Application app) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/Movies/Anime%20480p.mkv"));
        i.setClass(app, PlayerActivity.class);
        ActivityController<PlayerActivity> ctl = Robolectric.buildActivity(PlayerActivity.class, i).setup();
        TestUtil.awaitPlayer(ctl.get());
        return ctl;
    }

    private static ActivityController<ClipEditorActivity> launchEditor() {
        Intent i = new Intent(ApplicationProvider.getApplicationContext(), ClipEditorActivity.class);
        i.setData(Uri.parse("file:///sdcard/Movies/Anime%20480p.mp4"));
        i.putExtra(ClipEditorActivity.EXTRA_TITLE, "Anime 480p.mp4");
        i.putExtra(ClipEditorActivity.EXTRA_POSITION_MS, 30_000L);
        i.putExtra(ClipEditorActivity.EXTRA_DURATION_MS, 120_000L);
        return Robolectric.buildActivity(ClipEditorActivity.class, i).setup();
    }
}
