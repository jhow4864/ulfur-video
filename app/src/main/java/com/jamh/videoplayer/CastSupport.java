package com.jamh.videoplayer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.7.0: Google Cast is optional. On phones without Google Play services (or when the Cast
 * module can't load) every entry point quietly hides: no cast buttons, no mini bar,
 * and the rest of the app works as before.
 *
 * <p>1.9.2: Cast can never block or crash launch any more.
 * <ul>
 *   <li>The CastContext is created lazily and asynchronously
 *       ({@link CastContext#getSharedInstance(Context, java.util.concurrent.Executor)}); until it is
 *       ready every Cast entry point simply stays hidden and shows itself afterwards.</li>
 *   <li>Google's {@code MiniControllerFragment} is gone (it crashed in onCreateView on the
 *       Android 17 beta: "Attempt to read from null array" in MiniControllerFragment.zza, during
 *       the fragment transaction in FragmentActivity.onStart). {@link CastMiniBar} replaces it.</li>
 *   <li>Cast is skipped entirely in safe mode and while a crash report is pending.</li>
 * </ul>
 */
final class CastSupport {

    /** Tests: force Cast off (Robolectric has no Play services anyway). */
    static volatile boolean disabled;
    private static volatile boolean failed;
    @Nullable private static volatile CastContext ready;
    private static boolean starting;
    private static final List<Runnable> waiters = new ArrayList<>();
    private static ExecutorService castExecutor;
    /** Tests: how many times an async CastContext init was started. */
    @VisibleForTesting static volatile int initAttempts;

    private CastSupport() {
    }

    /** Cast is off in safe mode and right after a crash (until the report is dismissed / next launch). */
    static boolean skipped(Context ctx) {
        if (disabled || failed) return true;
        try {
            if (CrashGuard.safeMode()) return true;
            return CrashGuard.hasPendingReport(ctx);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The shared CastContext if it is already initialised, else null (and the async init is
     * kicked off). Never blocks, never throws. Main thread.
     */
    @MainThread
    @Nullable
    static CastContext context(Context ctx) {
        CastContext cc = ready;
        if (cc != null) return cc;
        warmUp(ctx);
        return null;
    }

    /** Starts the async CastContext init once (no-op when unavailable/skipped). Main thread. */
    @MainThread
    static void warmUp(Context ctx) {
        if (ready != null || starting || skipped(ctx)) return;
        starting = true;
        initAttempts++;
        try {
            Context app = ctx.getApplicationContext();
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
                fail();
                return;
            }
            if (castExecutor == null) castExecutor = Executors.newSingleThreadExecutor();
            Handler main = new Handler(Looper.getMainLooper());
            CastContext.getSharedInstance(app, castExecutor)
                    .addOnCompleteListener(main::post, task -> {
                        starting = false;
                        try {
                            CastContext cc = task.isSuccessful() ? task.getResult() : null;
                            if (cc == null) {
                                fail();
                                return;
                            }
                            Caster.get().attach(app, cc);
                            ready = cc;
                            flush();
                        } catch (Throwable t) {
                            fail();
                        }
                    });
        } catch (Throwable t) {
            // No Play services, outdated Cast module, or a ROM without the dynamite loader.
            fail();
        }
    }

    private static void fail() {
        failed = true;
        starting = false;
        synchronized (waiters) {
            waiters.clear();
        }
    }

    private static void flush() {
        List<Runnable> run;
        synchronized (waiters) {
            run = new ArrayList<>(waiters);
            waiters.clear();
        }
        for (Runnable r : run) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // a Cast UI hook must never take the app down
            }
        }
    }

    /** Runs {@code r} on the main thread once Cast is ready (now, if it already is; never if unavailable). */
    @MainThread
    static void whenReady(Context ctx, Runnable r) {
        if (ready != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
            return;
        }
        if (skipped(ctx)) return;
        synchronized (waiters) {
            waiters.add(r);
        }
        warmUp(ctx);
    }

    static boolean isAvailable(Context ctx) {
        return context(ctx) != null;
    }

    static boolean isReady() {
        return ready != null;
    }

    /** Library / Vault toolbar: the MediaRouteActionProvider item, hidden until Cast is ready. */
    static void setUpMenuButton(Context ctx, Menu menu, int itemId) {
        MenuItem item = menu.findItem(itemId);
        if (item == null) return;
        if (context(ctx) == null) {
            item.setVisible(false);
            whenReady(ctx, () -> {
                if (menu.findItem(itemId) == item) setUpMenuButtonNow(ctx, menu, item, itemId);
            });
            return;
        }
        setUpMenuButtonNow(ctx, menu, item, itemId);
    }

    private static void setUpMenuButtonNow(Context ctx, Menu menu, MenuItem item, int itemId) {
        try {
            CastButtonFactory.setUpMediaRouteButton(ctx, menu, itemId);
            item.setVisible(true);
        } catch (Throwable t) {
            item.setVisible(false);
        }
    }

    /** Player top bar button; GONE until Cast is ready. It shows itself only when a TV is around. */
    static void setUpButton(Context ctx, MediaRouteButton button) {
        button.setVisibility(View.GONE);
        whenReady(ctx, () -> {
            try {
                CastButtonFactory.setUpMediaRouteButton(ctx, button);
                button.setVisibility(View.VISIBLE); // the button itself hides while no TV is around
            } catch (Throwable t) {
                button.setVisibility(View.GONE);
            }
        });
    }

    @VisibleForTesting
    static void resetForTest() {
        failed = false;
        ready = null;
        starting = false;
        initAttempts = 0;
        synchronized (waiters) {
            waiters.clear();
        }
    }
}
