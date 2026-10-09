package com.jamh.videoplayer;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/** Ulfur Video: fixed Ulfur dark palette (no wallpaper-based DynamicColors since 1.4.0). */
public class VideoPlayerApp extends Application {
    private static final String TAG = "VideoPlayerApp";
    /** Last uncaught crash, readable later (e.g. via run-as or a future "Share crash log" item). */
    static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger();
        try {
            VaultLock.install(this); // Private videos re-lock when the app goes to the background
        } catch (Throwable t) {
            Log.e(TAG, "VaultLock.install failed", t);
        }
        // 1.6.0: encrypt Private videos from older versions in place (resumable, one at a time).
        try {
            if (VaultMigration.autoStart) VaultMigration.start(this);
        } catch (Throwable t) {
            Log.e(TAG, "VaultMigration.start failed", t); // retried on next launch
        }
    }

    /** Writes the stack trace of any fatal crash to filesDir/last_crash.txt, then defers to the default handler. */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final File out = new File(getFilesDir(), CRASH_FILE);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try (PrintWriter w = new PrintWriter(new FileWriter(out, false))) {
                w.println(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") thread=" + thread.getName()
                        + " time=" + System.currentTimeMillis());
                error.printStackTrace(w);
            } catch (Throwable ignored) {
                // best effort only
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
