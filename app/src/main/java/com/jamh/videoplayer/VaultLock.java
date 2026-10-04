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
    }

    /** Visible for tests: the app moved to the background. */
    static void onAppBackgrounded() {
        unlocked = false;
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
