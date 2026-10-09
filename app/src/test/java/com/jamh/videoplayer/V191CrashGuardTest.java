package com.jamh.videoplayer;

import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** 1.9.1: crash log shown on next launch, safe mode after two quick crashes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V191CrashGuardTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        CrashGuard.clearReport(app);
        CrashGuard.markStable(app);
        CrashGuard.setSafeModeForTest(false);
    }

    @After
    public void tearDown() {
        CrashGuard.clearReport(app);
        CrashGuard.markStable(app);
        CrashGuard.setSafeModeForTest(false);
        Prefs.setUpscaleMode(app, Upscaler.MODE_OFF);
    }

    @Test
    public void twoQuickCrashesInARowStartSafeModeAndStabilityClearsIt() {
        CrashGuard.onCrash(app, Thread.currentThread(), new IllegalStateException("boom 1"), 800);
        assertEquals(1, CrashGuard.quickCrashes(app));
        String report = CrashGuard.pendingReport(app);
        assertNotNull(report);
        assertTrue(report.contains("Ulfur Video " + BuildConfig.VERSION_NAME));
        assertTrue(report.contains("IllegalStateException: boom 1"));
        CrashGuard.install(app);
        assertFalse("one crash is not a loop", CrashGuard.safeMode());

        CrashGuard.onCrash(app, Thread.currentThread(), new IllegalStateException("boom 2"), 1200);
        assertEquals(2, CrashGuard.quickCrashes(app));
        CrashGuard.install(app);
        assertTrue(CrashGuard.safeMode());
        // Safe mode: Upscale forced Off, but the saved choice survives.
        Prefs.setUpscaleMode(app, Upscaler.MODE_SHARP);
        assertEquals(Upscaler.MODE_OFF, Prefs.upscaleMode(app));
        CrashGuard.setSafeModeForTest(false);
        assertEquals(Upscaler.MODE_SHARP, Prefs.upscaleMode(app));

        // Alive for 10 s: counter cleared, next start is normal.
        CrashGuard.markStable(app);
        assertEquals(0, CrashGuard.quickCrashes(app));
        // A crash long after start resets the streak instead of counting.
        CrashGuard.onCrash(app, Thread.currentThread(), new RuntimeException("late"), 60_000);
        assertEquals(0, CrashGuard.quickCrashes(app));
    }

    @Test
    public void nextLaunchShowsCrashLogWithCopyAndShareAndSafeModeBanner() {
        CrashGuard.onCrash(app, Thread.currentThread(), new NullPointerException("trace me"), 500);
        CrashGuard.setSafeModeForTest(true);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        idle();
        assertEquals(View.VISIBLE, a.findViewById(R.id.safe_mode_banner).getVisibility());
        AlertDialog d = a.maybeShowCrashReport();
        assertNotNull(d);
        assertTrue(d.isShowing());
        assertEquals("Copy crash log", d.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertEquals("Share", d.getButton(DialogInterface.BUTTON_NEUTRAL).getText().toString());
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        assertTrue(cm.getPrimaryClip().getItemAt(0).getText().toString().contains("NullPointerException: trace me"));
        assertNull("shown once", CrashGuard.pendingReport(app));
        assertNull(a.maybeShowCrashReport());
        ctl.pause().stop().destroy();

        CrashGuard.setSafeModeForTest(false);
        ActivityController<MainActivity> ctl2 = Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals(View.GONE, ctl2.get().findViewById(R.id.safe_mode_banner).getVisibility());
        ctl2.pause().stop().destroy();
    }
}
