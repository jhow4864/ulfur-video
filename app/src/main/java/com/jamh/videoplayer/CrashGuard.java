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

    static void clearReport(Context c) {
        //noinspection ResultOfMethodCallIgnored
        crashFile(c).delete();
    }
}
