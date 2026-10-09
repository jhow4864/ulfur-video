package com.jamh.videoplayer;

import android.app.Application;
import android.util.Log;

/** Ulfur Video: fixed Ulfur dark palette (no wallpaper-based DynamicColors since 1.4.0). */
public class VideoPlayerApp extends Application {
    private static final String TAG = "VideoPlayerApp";
    /** Last uncaught crash (see {@link CrashGuard}). */
    static final String CRASH_FILE = CrashGuard.CRASH_FILE;

    @Override
    public void onCreate() {
        super.onCreate();
        // 1.9.1: crash log + safe mode after two quick crashes in a row.
        try {
            CrashGuard.install(this);
        } catch (Throwable t) {
            Log.e(TAG, "CrashGuard.install failed", t);
        }
        try {
            VaultLock.install(this); // Private videos re-lock when the app goes to the background
        } catch (Throwable t) {
            Log.e(TAG, "VaultLock.install failed", t);
        }
        // 1.6.0: encrypt Private videos from older versions in place (resumable, one at a time).
        // Skipped in safe mode (retried on the next normal start).
        try {
            if (VaultMigration.autoStart && !CrashGuard.safeMode()) VaultMigration.start(this);
        } catch (Throwable t) {
            Log.e(TAG, "VaultMigration.start failed", t);
        }
    }
}
