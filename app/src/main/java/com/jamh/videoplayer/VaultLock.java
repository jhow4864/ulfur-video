package com.jamh.videoplayer;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Unlock state for Private videos. Unlocking lasts while any of our activities is visible; as
 * soon as the app goes to the background (no started activity, not a rotation) it locks again.
 */
final class VaultLock implements Application.ActivityLifecycleCallbacks {

    private static volatile boolean unlocked;
    private int started; // per Application instance

    private VaultLock() {
    }

    static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new VaultLock());
    }

    static boolean isUnlocked() {
        return unlocked;
    }

    static void unlock() {
        unlocked = true;
    }

    static void lock() {
        unlocked = false;
        pickerOpenSince = 0;
    }

    /** Visible for tests: the app moved to the background. */
    static void onAppBackgrounded() {
        if (pickerOpenSince > 0 && System.currentTimeMillis() - pickerOpenSince < PICKER_GRACE_MS) return;
        unlocked = false;
    }

    // 1.6.0: the system file picker (backup / restore / export) puts the app in the background.
    // Don't lock for that (it would ask for the fingerprint again mid-flow), but only while
    // a picker we opened is showing, and never for longer than PICKER_GRACE_MS.
    static final long PICKER_GRACE_MS = 5 * 60 * 1000;
    private static volatile long pickerOpenSince;

    static void pickerOpening() {
        if (unlocked) pickerOpenSince = System.currentTimeMillis();
    }

    static void pickerClosed() {
        pickerOpenSince = 0;
    }

    @Override
    public void onActivityStarted(@NonNull Activity a) {
        started++;
    }

    @Override
    public void onActivityStopped(@NonNull Activity a) {
        started = Math.max(0, started - 1);
        if (started == 0 && !a.isChangingConfigurations()) onAppBackgrounded();
    }

    @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) { }
    @Override public void onActivityResumed(@NonNull Activity a) { }
    @Override public void onActivityPaused(@NonNull Activity a) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) { }
    @Override public void onActivityDestroyed(@NonNull Activity a) { }
}
