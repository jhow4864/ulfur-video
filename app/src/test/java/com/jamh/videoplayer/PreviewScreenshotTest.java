package com.jamh.videoplayer;

import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Dialog;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

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

/** Smoke test + preview renderer: inflates both screens and writes PNG screenshots. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PreviewScreenshotTest {

    static final File OUT = new File(System.getProperty("preview.dir", "/workspace/video-player/previews"));

    private static final String[][] SAMPLES = {
            {"Lake District hike", "Camera"}, {"Birthday party 2026", "Camera"},
            {"Brighton beach sunset", "Camera"}, {"Screen recording 0412", "Screen recordings"},
            {"Concert – Manchester", "Camera"}, {"Kids football final", "Camera"},
            {"Interstellar (2014) 1080p", "Movies"}, {"Planet Earth III S01E01", "Movies"},
            {"WhatsApp Video 2026-09-28", "WhatsApp Video"}, {"Drone flight Snowdonia", "DJI"},
            {"Christmas morning", "Camera"}, {"Wedding speech", "Download"},
    };
    private static final int[][] HUES = {
            {0xFF1D4E89, 0xFF7FB7BE}, {0xFF8E2DE2, 0xFFFF6A88}, {0xFFFF7E5F, 0xFF2B1E3F},
            {0xFF232526, 0xFF414345}, {0xFF41295A, 0xFF2F0743}, {0xFF134E5E, 0xFF71B280},
            {0xFF0F2027, 0xFF2C5364}, {0xFF1F4037, 0xFF99F2C8}, {0xFF3A6073, 0xFF16222A},
            {0xFF56AB2F, 0xFFA8E063}, {0xFFB24592, 0xFFF15F79}, {0xFFDAD299, 0xFFB0DAB9},
    };

    List<VideoItem> fakeVideos() {
        List<VideoItem> list = new ArrayList<>();
        long now = 1_790_000_000L;
        for (int i = 0; i < SAMPLES.length; i++) {
            long dur = (i == 6 ? 169 * 60_000L : i == 7 ? 52 * 60_000L : (35 + i * 47) * 1000L);
            long size = dur / 1000 * (i == 6 ? 1_100_000L : 2_000_000L + i * 150_000L);
            boolean portrait = i == 3 || i == 8 || i == 11;  // phone recordings held upright
            list.add(new VideoItem(1000 + i, Uri.parse("content://media/external/video/media/" + (1000 + i)),
                    SAMPLES[i][0] + ".mp4", dur, size, now - i * 86_400L * 3,
                    String.valueOf(SAMPLES[i][1].hashCode()), SAMPLES[i][1],
                    portrait ? 1080 : 1920, portrait ? 1920 : 1080));
        }
        return list;
    }

    static Bitmap fakeFrame(int w, int h, int seed) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int[] hue = HUES[seed % HUES.length];
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, hue[0], hue[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0x55FFFFFF);
        c.drawCircle(w * (0.25f + 0.05f * (seed % 5)), h * 0.32f, h * 0.12f, p);
        Path hills = new Path();
        hills.moveTo(0, h);
        hills.lineTo(0, h * 0.7f);
        hills.quadTo(w * 0.25f, h * (0.45f + 0.03f * (seed % 4)), w * 0.5f, h * 0.68f);
        hills.quadTo(w * 0.75f, h * 0.5f, w, h * 0.72f);
        hills.lineTo(w, h);
        hills.close();
        p.setColor(0x88000000);
        c.drawPath(hills, p);
        return b;
    }

    @SuppressWarnings("unchecked")
    void seedThumbnails(List<VideoItem> vids) throws Exception {
        Field f = ThumbnailLoader.class.getDeclaredField("CACHE");
        f.setAccessible(true);
        LruCache<Long, Bitmap> cache = (LruCache<Long, Bitmap>) f.get(null);
        for (int i = 0; i < vids.size(); i++) {
            VideoItem v = vids.get(i);
            cache.put(v.id, v.isPortrait() ? fakeFrame(216, 384, i) : fakeFrame(384, 216, i));
        }
    }

    static void save(View root, String name, int w, int h) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);
        root.draw(canvas);
        OUT.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(OUT, name))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }

    static Bitmap renderToBitmap(View root, int w, int h) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);
        root.draw(canvas);
        return bmp;
    }

    /** Library screen (with first-launch banner) + the "Default video player" dialog on top. */
    private void renderDefaultDialog(String file, boolean otherAppIsDefault) throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO);
        if (!otherAppIsDefault) {
            // Several video apps installed, none chosen: Android resolves to its "Open with" dialog.
            for (String mime : new String[]{"video/mp4", "video/x-matroska"}) {
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = new ActivityInfo();
                ri.activityInfo.packageName = "android";
                ri.activityInfo.name = "com.android.internal.app.ResolverActivity";
                ri.activityInfo.applicationInfo = new ApplicationInfo();
                ri.activityInfo.applicationInfo.packageName = "android";
                Intent probe = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse("content://media/external/video/media/1"), mime);
                shadowOf(app.getPackageManager()).addResolveInfoForIntent(probe, ri);
            }
        } else {
            String pkg = "com.google.android.apps.photos";
            PackageInfo pi = new PackageInfo();
            pi.packageName = pkg;
            pi.applicationInfo = new ApplicationInfo();
            pi.applicationInfo.packageName = pkg;
            pi.applicationInfo.nonLocalizedLabel = "Photos";
            shadowOf(app.getPackageManager()).installPackage(pi);
            for (String mime : new String[]{"video/mp4", "video/x-matroska"}) {
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = new ActivityInfo();
                ri.activityInfo.packageName = pkg;
                ri.activityInfo.name = pkg + ".VideoPlayerActivity";
                ri.activityInfo.applicationInfo = pi.applicationInfo;
                ri.nonLocalizedLabel = "Photos";
                Intent probe = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse("content://media/external/video/media/1"), mime);
                shadowOf(app.getPackageManager()).addResolveInfoForIntent(probe, ri);
            }
        }
        List<VideoItem> vids = fakeVideos();
        seedThumbnails(vids);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        Field all = MainActivity.class.getDeclaredField("allVideos");
        all.setAccessible(true);
        all.set(a, vids);
        Method render = MainActivity.class.getDeclaredMethod("render");
        render.setAccessible(true);
        render.invoke(a);
        shadowOf(Looper.getMainLooper()).idle();

        int w = 1080, h = 2340;
        Bitmap base = renderToBitmap(a.getWindow().getDecorView(), w, h);
        if (!otherAppIsDefault) {
            OUT.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(new File(OUT, "library_default_banner.png"))) {
                base.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
        }
        a.showDefaultPlayerDialog();
        shadowOf(Looper.getMainLooper()).idle();
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        View dv = d.getWindow().getDecorView();
        int dw = w - 2 * 72;
        dv.measure(View.MeasureSpec.makeMeasureSpec(dw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
        dv.layout(0, 0, dw, dv.getMeasuredHeight());
        Canvas c = new Canvas(base);
        c.drawColor(0x99000000);
        c.save();
        c.translate(72, (h - dv.getMeasuredHeight()) / 2f);
        dv.draw(c);
        c.restore();
        OUT.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(OUT, file))) {
            base.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void defaultDialogNoDefault() throws Exception {
        renderDefaultDialog("default_dialog_none.png", false);
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void defaultDialogOtherApp() throws Exception {
        renderDefaultDialog("default_dialog_other_app.png", true);
    }

    private void renderLibrary(boolean grid, int tab, String file) throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_EXTERNAL_STORAGE);
        Prefs.setGrid(app, grid);
        Prefs.setDefaultBannerDismissed(app);
        List<VideoItem> vids = fakeVideos();
        seedThumbnails(vids);
        Prefs.saveResume(app, vids.get(0).resumeKey(), vids.get(0).durationMs / 3, vids.get(0).durationMs);
        Prefs.saveResume(app, vids.get(6).resumeKey(), vids.get(6).durationMs * 2 / 3, vids.get(6).durationMs);

        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        shadowOf(Looper.getMainLooper()).idle();
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
        shadowOf(Looper.getMainLooper()).idle();
        View root = a.getWindow().getDecorView();
        save(root, file, 1080, 2340);
        ctl.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void libraryGrid() throws Exception {
        renderLibrary(true, 0, "library_grid.png");
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void libraryList() throws Exception {
        renderLibrary(false, 0, "library_list.png");
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void libraryFolders() throws Exception {
        renderLibrary(true, 1, "library_folders.png");
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void libraryClips() throws Exception {
        renderLibrary(true, 2, "library_clips.png");
    }

    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void playerControls() throws Exception {
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("file:///sdcard/Movies/Brighton%20beach%20sunset.mp4"));
        i.setClass(ApplicationProvider.getApplicationContext(), PlayerActivity.class);
        ActivityController<PlayerActivity> ctl = Robolectric.buildActivity(PlayerActivity.class, i).setup();
        PlayerActivity a = ctl.get();
        Field pf = PlayerActivity.class.getDeclaredField("player");
        pf.setAccessible(true);
        assertNotNull("ExoPlayer should be built", pf.get(a));

        // Preview only: stand-in "video frame" behind the controls (no real decoding in Robolectric).
        FrameLayout root = a.findViewById(R.id.player_root);
        ImageView frame = new ImageView(a);
        frame.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.setImageBitmap(fakeFrame(1600, 900, 2));
        root.addView(frame, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((TextView) a.findViewById(R.id.title)).setText("Brighton beach sunset");
        SeekBar sb = a.findViewById(R.id.seekbar);
        sb.setMax(2634);
        sb.setProgress(1012);
        sb.setSecondaryProgress(1500);
        ((TextView) a.findViewById(R.id.position)).setText("1:41");
        ((TextView) a.findViewById(R.id.duration)).setText("4:23");
        a.findViewById(R.id.controls).setVisibility(View.VISIBLE);
        a.findViewById(R.id.controls).setAlpha(1f);
        a.findViewById(R.id.buffering).setVisibility(View.GONE);
        save(root, "player_controls.png", 2340, 1080);

        // Gesture state 1: horizontal-swipe scrub preview + double-tap skip feedback.
        a.findViewById(R.id.controls).setVisibility(View.GONE);
        TextView seek = a.findViewById(R.id.seek_info);
        seek.setText("+0:25\n2:06 / 4:23");
        seek.setVisibility(View.VISIBLE);
        TextView skip = a.findViewById(R.id.skip_right);
        skip.setText("+20s");
        skip.setVisibility(View.VISIBLE);
        save(root, "player_scrub_skip.png", 2340, 1080);

        // Gesture state 2: volume swipe indicator.
        seek.setVisibility(View.GONE);
        skip.setVisibility(View.GONE);
        a.findViewById(R.id.gesture_panel).setVisibility(View.VISIBLE);
        ((ImageView) a.findViewById(R.id.gesture_icon)).setImageResource(R.drawable.ic_volume);
        ((TextView) a.findViewById(R.id.gesture_text)).setText("9");
        ((android.widget.ProgressBar) a.findViewById(R.id.gesture_progress)).setProgress(60);
        save(root, "player_volume.png", 2340, 1080);
        ctl.pause().stop().destroy();
    }
}
