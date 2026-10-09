package com.jamh.videoplayer;

import static com.jamh.videoplayer.PreviewScreenshotTest.OUT;
import static com.jamh.videoplayer.PreviewScreenshotTest.fakeFrame;
import static com.jamh.videoplayer.PreviewScreenshotTest.renderToBitmap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;

import com.google.common.collect.ImmutableList;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 1.5.0: real-time upscaling (plan, watchdog, persistence, UI, shader assets). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class V15FeatureTest {

    // ------------------------------------------------------------------ plan

    @Test
    public void upscalesOnlyWhenVideoIsSmallerThanTheScreen() {
        // 720p on a 1080p-wide landscape phone (2400x1080 surface fitted to 16:9 = 1920x1080).
        Upscaler.Plan p = Upscaler.plan(1280, 720, 1920, 1080, Upscaler.MODE_BALANCED);
        assertEquals(Upscaler.PLAN_UPSCALE, p.kind);
        assertEquals(1920, p.outWidth);
        assertEquals(1080, p.outHeight);
        assertTrue(p.sharpness > 0f && p.sharpness < 1f);

        // 1080p video on a 1080p surface: Balanced leaves it alone, Sharp only sharpens.
        assertEquals(Upscaler.PLAN_COPY, Upscaler.plan(1920, 1080, 1920, 1080, Upscaler.MODE_BALANCED).kind);
        Upscaler.Plan s = Upscaler.plan(1920, 1080, 1920, 1080, Upscaler.MODE_SHARP);
        assertEquals(Upscaler.PLAN_SHARPEN, s.kind);
        assertEquals(1920, s.outWidth);

        // 4K: never processed (over the 1080p budget).
        assertEquals(Upscaler.PLAN_COPY, Upscaler.plan(3840, 2160, 2400, 1080, Upscaler.MODE_SHARP).kind);
        // Off: always pass-through.
        assertEquals(Upscaler.PLAN_COPY, Upscaler.plan(640, 360, 1920, 1080, Upscaler.MODE_OFF).kind);
    }

    @Test
    public void outputStaysWithinTheOneThousandEightyPBudgetAndKeepsAspect() {
        // 480p on a 1440p screen: capped at ~1080p, not 2560x1440.
        Upscaler.Plan p = Upscaler.plan(854, 480, 2560, 1440, Upscaler.MODE_SHARP);
        assertEquals(Upscaler.PLAN_UPSCALE, p.kind);
        assertTrue((long) p.outWidth * p.outHeight <= Upscaler.MAX_OUTPUT_PIXELS + 4000);
        assertEquals(854 / 480.0, p.outWidth / (double) p.outHeight, 0.01);
        // 240p: at most 4x.
        Upscaler.Plan tiny = Upscaler.plan(320, 240, 1920, 1080, Upscaler.MODE_BALANCED);
        assertTrue(tiny.outWidth <= 320 * 4 && tiny.outHeight <= 240 * 4);
        // Portrait video in a portrait surface.
        Upscaler.Plan port = Upscaler.plan(360, 640, 1080, 1920, Upscaler.MODE_BALANCED);
        assertEquals(Upscaler.PLAN_UPSCALE, port.kind);
        assertEquals(1080, port.outWidth);
        assertEquals(1920, port.outHeight);
        // Barely smaller than the screen: not worth a pass.
        assertEquals(Upscaler.PLAN_COPY, Upscaler.plan(1800, 1012, 1920, 1080, Upscaler.MODE_BALANCED).kind);
    }

    @Test
    public void sharpModeIsSharperThanBalanced() {
        float b = Upscaler.plan(640, 360, 1920, 1080, Upscaler.MODE_BALANCED).sharpness;
        float s = Upscaler.plan(640, 360, 1920, 1080, Upscaler.MODE_SHARP).sharpness;
        assertTrue(s > b);
        assertEquals(1f, Upscaler.sharpnessForStops(0f), 1e-6);
        assertEquals(0.5f, Upscaler.sharpnessForStops(1f), 1e-6);
    }

    @Test
    public void modesCycleAndSanitize() {
        assertEquals(Upscaler.MODE_BALANCED, Upscaler.nextMode(Upscaler.MODE_OFF));
        assertEquals(Upscaler.MODE_SHARP, Upscaler.nextMode(Upscaler.MODE_BALANCED));
        assertEquals(Upscaler.MODE_OFF, Upscaler.nextMode(Upscaler.MODE_SHARP));
        assertEquals(Upscaler.MODE_OFF, Upscaler.sanitizeMode(7));
        assertEquals("Balanced", Upscaler.modeLabel(Upscaler.MODE_BALANCED));
    }

    // ------------------------------------------------------------------ watchdog

    @Test
    public void watchdogStepsDownOnlyAfterTwoBadWindows() {
        Upscaler.Watchdog w = new Upscaler.Watchdog();
        assertFalse(w.onWindow(20, 60));   // 33 % dropped: strike 1
        assertTrue(w.onWindow(20, 60));    // strike 2 → step down
        assertFalse(w.onWindow(20, 60));   // counter restarted
        assertFalse(w.onWindow(2, 60));    // healthy window resets the strikes
        assertFalse(w.onWindow(20, 60));
        assertFalse(w.onWindow(5, 4));     // too few frames to judge
        w.reset();
        assertFalse(w.onWindow(20, 60));
    }

    // ------------------------------------------------------------------ HDR / errors

    @Test
    public void hdrVideoIsDetectedAndNeverUpscaled() {
        Format hdr = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(1920).setHeight(1080)
                .setColorInfo(new ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
                        .setColorTransfer(C.COLOR_TRANSFER_ST2084).setColorRange(C.COLOR_RANGE_LIMITED).build())
                .build();
        Format sdr = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1280).setHeight(720).build();
        assertTrue(PlayerActivity.isHdr(tracks(hdr)));
        assertFalse(PlayerActivity.isHdr(tracks(sdr)));
        assertTrue(PlayerActivity.isVideoEffectsError(new PlaybackException("gl", null,
                PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED)));
        assertFalse(PlayerActivity.isVideoEffectsError(new PlaybackException("io", null,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)));
    }

    private static Tracks tracks(Format f) {
        TrackGroup g = new TrackGroup(f);
        return new Tracks(ImmutableList.of(new Tracks.Group(g, false,
                new int[]{C.FORMAT_HANDLED}, new boolean[]{true})));
    }

    // ------------------------------------------------------------------ shaders

    @Test
    public void shaderAssetsShipWithCreditAndMatchTheUniforms() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        String easu = asset(app, UpscaleShaderProgram.EASU);
        String rcas = asset(app, UpscaleShaderProgram.RCAS);
        String lite = asset(app, UpscaleShaderProgram.LITE);
        String vert = asset(app, UpscaleShaderProgram.VERTEX);
        asset(app, UpscaleShaderProgram.COPY);
        String lic = asset(app, "licenses/FSR1_LICENSE.txt");
        for (String s : new String[]{easu, rcas}) {
            assertTrue(s.contains("Advanced Micro Devices"));
            assertTrue(s.contains("MIT"));
        }
        assertTrue(lic.contains("Permission is hereby granted, free of charge"));
        assertTrue(easu.contains("uniform vec2 uInSize") && easu.contains("uniform vec2 uOutSize"));
        assertTrue(lite.contains("uniform vec2 uInSize") && lite.contains("uniform vec2 uOutSize"));
        assertTrue(rcas.contains("uniform vec2 uSize") && rcas.contains("uniform float uSharpness"));
        assertTrue(vert.contains("attribute vec4 aFramePosition"));
    }

    private static String asset(Application app, String path) throws Exception {
        try (InputStream in = app.getAssets().open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ UI

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void upscaleButtonCyclesPersistsAndShowsALabel() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        assertEquals("default off", Upscaler.MODE_OFF, Prefs.upscaleMode(app));
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/Movies/Old%20VHS%20rip.mp4"));
        i.setClass(app, PlayerActivity.class);
        ActivityController<PlayerActivity> ctl = Robolectric.buildActivity(PlayerActivity.class, i).setup();
        TestUtil.awaitPlayer(ctl.get());
        PlayerActivity a = ctl.get();
        assertFalse("no effects pipeline while Upscale is off", (boolean) TestUtil.field(a, "upscalePipeline"));

        ImageButton btn = a.findViewById(R.id.btn_upscale);
        assertNotNull(btn);
        btn.performClick();
        TestUtil.idle();
        assertEquals(Upscaler.MODE_BALANCED, Prefs.upscaleMode(app));
        assertTrue("player rebuilt with the GL pipeline", (boolean) TestUtil.field(a, "upscalePipeline"));
        assertNotNull(TestUtil.field(a, "player"));
        assertEquals(ContextCompat.getColor(a, R.color.accent),
                ImageViewCompat.getImageTintList(btn).getDefaultColor());
        TextView label = a.findViewById(R.id.upscale_toast_title);
        assertEquals(View.VISIBLE, a.findViewById(R.id.upscale_toast).getVisibility());
        assertEquals("Upscale · Balanced", label.getText().toString());

        // Render the top bar with the button on + the quick label.
        FrameLayout root = a.findViewById(R.id.player_root);
        ImageView frame = new ImageView(a);
        frame.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.setImageBitmap(fakeFrame(1600, 900, 3));
        root.addView(frame, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((TextView) a.findViewById(R.id.title)).setText("Old VHS rip");
        a.showUpscaleToast("Upscale · Balanced", "640×360 → 1920×1080");
        SeekBar sb = a.findViewById(R.id.seekbar);
        sb.setMax(2634);
        sb.setProgress(812);
        a.findViewById(R.id.controls).setVisibility(View.VISIBLE);
        a.findViewById(R.id.controls).setAlpha(1f);
        a.findViewById(R.id.buffering).setVisibility(View.GONE);
        write(renderToBitmap(root, 2340, 1080), "player_upscale.png");

        // Sheet row shows the state; the setting is global (survives a new player).
        View sheet = a.getLayoutInflater().inflate(R.layout.sheet_player_menu, null);
        a.bindMenuSheet(sheet, new Dialog(a));
        String sub = ((TextView) sheet.findViewById(R.id.upscale_value)).getText().toString();
        assertTrue(sub, sub.startsWith("Balanced"));

        btn.performClick(); // Sharp
        TestUtil.idle();
        assertEquals(Upscaler.MODE_SHARP, Prefs.upscaleMode(app));
        btn.performClick(); // Off: instant, effects cleared
        TestUtil.idle();
        assertEquals(Upscaler.MODE_OFF, Prefs.upscaleMode(app));
        assertEquals("Upscale off", ((TextView) a.findViewById(R.id.upscale_toast_title)).getText().toString());
        assertEquals(0xFFFFFFFF, ImageViewCompat.getImageTintList(btn).getDefaultColor());
        ctl.pause().stop().destroy();
    }

    private static void write(Bitmap b, String name) throws Exception {
        OUT.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(OUT, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }
}
