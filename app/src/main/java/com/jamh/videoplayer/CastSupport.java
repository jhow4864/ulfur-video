package com.jamh.videoplayer;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.media.widget.MiniControllerFragment;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

/**
 * 1.7.0: Google Cast is optional. On phones without Google Play services (or when the Cast
 * module can't load) every entry point quietly hides: no cast buttons, no mini controller,
 * and the rest of the app works as before.
 */
final class CastSupport {

    /** Tests: force Cast off (Robolectric has no Play services anyway). */
    static volatile boolean disabled;
    private static volatile boolean failed;

    private CastSupport() {
    }

    /** The shared CastContext, or null when Cast isn't available on this device. Main thread. */
    @MainThread
    @Nullable
    static CastContext context(Context ctx) {
        if (disabled || failed) return null;
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) != ConnectionResult.SUCCESS) {
                failed = true;
                return null;
            }
            CastContext cc = CastContext.getSharedInstance(ctx.getApplicationContext());
            Caster.get().attach(ctx.getApplicationContext(), cc);
            return cc;
        } catch (Throwable t) {
            // No Play services, outdated Cast module, or a ROM without the dynamite loader.
            failed = true;
            return null;
        }
    }

    static boolean isAvailable(Context ctx) {
        return context(ctx) != null;
    }

    /** Library / Vault toolbar: the MediaRouteActionProvider item, hidden without Cast. */
    static void setUpMenuButton(Context ctx, Menu menu, int itemId) {
        MenuItem item = menu.findItem(itemId);
        if (item == null) return;
        if (context(ctx) == null) {
            item.setVisible(false);
            return;
        }
        try {
            CastButtonFactory.setUpMediaRouteButton(ctx, menu, itemId);
        } catch (Throwable t) {
            item.setVisible(false);
        }
    }

    /** Player top bar button; GONE without Cast. It shows itself only when a TV is around. */
    static void setUpButton(Context ctx, MediaRouteButton button) {
        if (context(ctx) == null) {
            button.setVisibility(View.GONE);
            return;
        }
        try {
            CastButtonFactory.setUpMediaRouteButton(ctx, button);
            button.setVisibility(View.VISIBLE); // the button itself hides while no TV is around
        } catch (Throwable t) {
            button.setVisibility(View.GONE);
        }
    }

    /** Adds the Cast mini controller (shows only while something is casting). */
    static void addMiniController(FragmentActivity a, int containerId) {
        View container = a.findViewById(containerId);
        if (container == null || context(a) == null) return;
        try {
            if (a.getSupportFragmentManager().findFragmentById(containerId) == null) {
                a.getSupportFragmentManager().beginTransaction()
                        .replace(containerId, new MiniControllerFragment())
                        .commitAllowingStateLoss();
            }
            container.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            container.setVisibility(View.GONE);
        }
    }
}
