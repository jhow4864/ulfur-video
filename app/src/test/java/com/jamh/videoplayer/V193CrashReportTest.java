package com.jamh.videoplayer;

import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
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
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 1.9.3: the crash report is shown at most once, never from another version, and can be dismissed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V193CrashReportTest {

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
    }

    @Test
    public void reportFromAnotherVersionIsDeletedOnStartupAndNeverShown() throws Exception {
        File f = CrashGuard.crashFile(app);
        Files.write(f.toPath(), ("Ulfur Video 1.9.1 (15) · Android 17 (API 37) · Google Pixel 10\n"
                + "java.lang.NullPointerException: Attempt to read from null array\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(15, CrashGuard.reportVersionCode(CrashGuard.pendingReport(app)));
        CrashGuard.install(app);
        assertFalse("stale 1.9.1 report deleted at startup", f.exists());

        Files.write(f.toPath(), "garbage without a header".getBytes(StandardCharsets.UTF_8));
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        idle();
        assertFalse(f.exists());
        assertNull(ctl.get().maybeShowCrashReport());
        ctl.pause().stop().destroy();
    }

    @Test
    public void currentReportIsDeletedWhenReadShownOnceAndDismissable() {
        CrashGuard.onCrash(app, Thread.currentThread(), new IllegalStateException("once only"), 500);
        assertEquals(BuildConfig.VERSION_CODE, CrashGuard.reportVersionCode(CrashGuard.pendingReport(app)));

        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        idle();
        assertFalse("deleted as soon as it was read", CrashGuard.crashFile(app).exists());
        Dialog shown = ShadowDialog.getLatestDialog();
        assertTrue(shown instanceof AlertDialog);
        AlertDialog d = (AlertDialog) shown;
        assertTrue(d.isShowing());
        assertSame("onResume doesn't open a second one", d, ctl.get().maybeShowCrashReport());
        assertEquals("Copy crash log", d.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertEquals("Share", d.getButton(DialogInterface.BUTTON_NEUTRAL).getText().toString());
        assertEquals("Dismiss", d.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());
        assertEquals(View.VISIBLE, d.getButton(DialogInterface.BUTTON_NEGATIVE).getVisibility());
        assertTrue(org.robolectric.Shadows.shadowOf(d).isCancelable());

        d.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();
        assertFalse(d.isShowing());
        assertNull("at most once per crash", ctl.get().maybeShowCrashReport());
        ctl.pause().stop().destroy();

        ActivityController<MainActivity> again = Robolectric.buildActivity(MainActivity.class).setup();
        idle();
        assertNull(again.get().maybeShowCrashReport());
        again.pause().stop().destroy();
    }
}
