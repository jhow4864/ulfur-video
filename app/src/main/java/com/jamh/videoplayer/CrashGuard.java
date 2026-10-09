package com.jamh.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 1.9.1 crash safety net.
 * <ul>
 *   <li>Any uncaught crash is written to {@code filesDir/last_crash.txt}; the next launch shows it
 *       with Copy crash log / Share (see {@link MainActivity}).</li>
 *   <li>Two crashes in a row, each within {@link #QUICK_MS} of the process starting, put the next
 *       start in <b>safe mode</b>: Upscale forced Off (the saved choice is kept), the Private
 *       encryption migration skipped, and a "Started in safe mode" banner. Surviving
 *       {@link #QUICK_MS} clears the counter.</li>
 * </ul>
 */
final class CrashGuard {

    static final String CRASH_FILE = "last_crash.txt";
    static final String PREFS = "crash_guard";
    static final long QUICK_MS = 10_000;
    static final int SAFE_MODE_AFTER = 2;

    private static volatile boolean safeMode;
    private static long processStartElapsed = SystemClock.elapsedRealtime();

    private CrashGuard() {
    }

    static boolean safeMode() {
        return safeMode;
    }

    @VisibleForTesting
    static void setSafeModeForTest(boolean on) {
        safeMode = on;
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File crashFile(Context c) {
        return new File(c.getFilesDir(), CRASH_FILE);
    }

    /** Call first thing in Application.onCreate. Decides safe mode and installs the handler. */
    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        processStartElapsed = SystemClock.elapsedRealtime();
        safeMode = p(app).getInt("quick_crashes", 0) >= SAFE_MODE_AFTER;
        dropStaleReport(app);
        if (hasPendingReport(app)) crashedLastLaunch = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                onCrash(app, thread, error, SystemClock.elapsedRealtime() - processStartElapsed);
            } catch (Throwable ignored) {
                // best effort only
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
        // Alive for QUICK_MS: not a crash loop (any more).
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> markStable(app), QUICK_MS);
    }

    /** Records a crash (file + quick-crash counter, written synchronously: the process is dying). */
    @VisibleForTesting
    static void onCrash(Context app, Thread thread, Throwable error, long msSinceStart) {
        try (PrintWriter w = new PrintWriter(new FileWriter(crashFile(app), false))) {
            w.println("Ulfur Video " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                    + " · Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")"
                    + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            w.println("thread=" + thread.getName() + " · " + msSinceStart + " ms after start · at "
                    + new java.util.Date());
            error.printStackTrace(w);
        } catch (Throwable ignored) {
        }
        int quick = p(app).getInt("quick_crashes", 0);
        p(app).edit().putInt("quick_crashes", msSinceStart < QUICK_MS ? quick + 1 : 0).commit();
    }

    static void markStable(Context app) {
        if (p(app).getInt("quick_crashes", 0) != 0) p(app).edit().putInt("quick_crashes", 0).apply();
    }

    @VisibleForTesting
    static int quickCrashes(Context c) {
        return p(c).getInt("quick_crashes", 0);
    }

    /** Cheap check (no read): is there a crash report from last time? */
    static boolean hasPendingReport(Context c) {
        File f = crashFile(c);
        return f.isFile() && f.length() > 0;
    }

    /** The saved crash report, or null. */
    @Nullable
    static String pendingReport(Context c) {
        File f = crashFile(c);
        if (!f.isFile()) return null;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return s.isEmpty() ? null : s;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 1.9.3: this process started right after a crash (Cast stays off for this launch). */
    private static volatile boolean crashedLastLaunch;

    static boolean crashedLastLaunch() {
        return crashedLastLaunch;
    }

    private static final java.util.regex.Pattern HEADER =
            java.util.regex.Pattern.compile("^Ulfur Video \\S+ \\((\\d+)\\)");

    /** versionCode in the report's first line, or -1 (unknown / unreadable). */
    static int reportVersionCode(@Nullable String report) {
        if (report == null) return -1;
        java.util.regex.Matcher m = HEADER.matcher(report);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 1.9.3: a report written by another version is stale (it's about old code): delete it. */
    static void dropStaleReport(Context c) {
        try {
            if (!crashFile(c).isFile()) return;
            if (reportVersionCode(pendingReport(c)) != BuildConfig.VERSION_CODE) {
                //noinspection ResultOfMethodCallIgnored
                crashFile(c).delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 1.9.3: reads the report for this version and deletes the file straight away, so each crash is
     * shown at most once, whatever happens to the dialog. Null when there is none (or it is stale).
     */
    @Nullable
    static String takeReport(Context c) {
        dropStaleReport(c);
        String r = pendingReport(c);
        //noinspection ResultOfMethodCallIgnored
        crashFile(c).delete();
        if (r != null) crashedLastLaunch = true;
        return r;
    }

    static void clearReport(Context c) {
        crashedLastLaunch = false;
        //noinspection ResultOfMethodCallIgnored
        crashFile(c).delete();
    }
}
