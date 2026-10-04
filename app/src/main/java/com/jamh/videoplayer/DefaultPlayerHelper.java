package com.jamh.videoplayer;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Detects which app is the system default for opening videos, and helps the user make
 * Video Player the default (Android does not allow an app to set itself as default).
 */
public final class DefaultPlayerHelper {

    /** Extra added to the setup ACTION_VIEW intent so PlayerActivity can return straight away. */
    public static final String EXTRA_DEFAULT_SETUP = "com.jamh.videoplayer.extra.DEFAULT_SETUP";

    private static final Uri PROBE_URI = Uri.parse("content://media/external/video/media/1");
    private static final String FILE_PROVIDER_AUTHORITY = BuildConfigHolder.AUTHORITY;

    public enum State { THIS_APP, NONE, OTHER_APP }

    /** Result of a default-handler check. */
    public static final class Status {
        public final State state;
        public final String packageName; // for OTHER_APP
        public final String appLabel;    // for OTHER_APP
        public final State mkvState;     // the same check for video/x-matroska
        public final String mkvLabel;
        public final String mkvPackage;

        Status(State state, String pkg, String label, State mkvState, String mkvPackage, String mkvLabel) {
            this.state = state;
            this.packageName = pkg;
            this.appLabel = label;
            this.mkvState = mkvState;
            this.mkvLabel = mkvLabel;
            this.mkvPackage = mkvPackage;
        }

        public String summary(Context c) {
            switch (state) {
                case THIS_APP: return c.getString(R.string.app_name) + " is your default";
                case OTHER_APP: return "Default: " + appLabel;
                default: return "No default set";
            }
        }

        /** Extra note when MKV files are handled differently from MP4. */
        public String mkvNote(Context c) {
            if (mkvState == state) return null;
            switch (mkvState) {
                case THIS_APP: return "MKV files already open in " + c.getString(R.string.app_name) + ".";
                case OTHER_APP: return "MKV files currently open in " + mkvLabel + ".";
                default: return "No default is set for MKV files.";
            }
        }
    }

    private DefaultPlayerHelper() {}

    public static Status check(Context context) {
        Object[] mp4 = resolve(context, "video/mp4");
        Object[] mkv = resolve(context, "video/x-matroska");
        return new Status((State) mp4[0], (String) mp4[1], (String) mp4[2],
                (State) mkv[0], (String) mkv[1], (String) mkv[2]);
    }

    /** Returns {State, packageName, label}. */
    private static Object[] resolve(Context context, String mime) {
        PackageManager pm = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW).setDataAndType(PROBE_URI, mime);
        ResolveInfo ri = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return new Object[]{State.NONE, null, null};
        ActivityInfo ai = ri.activityInfo;
        String pkg = ai.packageName;
        if (context.getPackageName().equals(pkg)) return new Object[]{State.THIS_APP, pkg, null};
        if (isResolver(ai)) return new Object[]{State.NONE, null, null};
        return new Object[]{State.OTHER_APP, pkg, label(pm, pkg)};
    }

    /** The system disambiguation dialog ("Open with") means nothing is set as default. */
    private static boolean isResolver(ActivityInfo ai) {
        String pkg = ai.packageName == null ? "" : ai.packageName;
        String name = ai.name == null ? "" : ai.name;
        return "android".equals(pkg) || pkg.contains("intentresolver")
                || name.endsWith("ResolverActivity") || name.contains("Chooser");
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Opens the App info page of {@code pkg} (Open by default > Clear default preferences). */
    public static boolean openAppDetails(Context context, String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    /**
     * Builds a real ACTION_VIEW intent for a video (no Intent.createChooser), so Android shows its
     * disambiguation dialog with "Just once" / "Always".
     *
     * @param libraryVideo first video of the library, or null to use the bundled sample clip.
     * @param mime         MIME type to register the default for (Android stores "Always" per type).
     */
    public static Intent buildSetupIntent(Context context, Uri libraryVideo, String mime) throws IOException {
        Uri uri = libraryVideo != null ? libraryVideo : sampleUri(context);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra(EXTRA_DEFAULT_SETUP, true);
        return i;
    }

    /** Copies res/raw/sample_video.mp4 into the cache and exposes it through the FileProvider. */
    private static Uri sampleUri(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "sample");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File out = new File(dir, "sample_video.mp4");
        if (!out.exists() || out.length() == 0) {
            try (InputStream in = context.getResources().openRawResource(R.raw.sample_video);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, out);
    }

    /** Small holder so the authority string lives in one place. */
    static final class BuildConfigHolder {
        static final String AUTHORITY = "com.jamh.videoplayer.fileprovider";
    }
}
