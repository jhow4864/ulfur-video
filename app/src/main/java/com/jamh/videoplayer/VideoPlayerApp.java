package com.jamh.videoplayer;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/** Applies Material You (wallpaper-based) colours on Android 12+; the dark theme stays the default. */
public class VideoPlayerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
