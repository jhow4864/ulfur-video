package com.jamh.videoplayer;

import android.app.Application;

/** Ulfur Video: fixed Ulfur dark palette (no wallpaper-based DynamicColors since 1.4.0). */
public class VideoPlayerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        VaultLock.install(this); // Private videos re-lock when the app goes to the background
    }
}
