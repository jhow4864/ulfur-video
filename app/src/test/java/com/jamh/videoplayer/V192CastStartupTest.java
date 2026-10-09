package com.jamh.videoplayer;

import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 1.9.2: Google's MiniControllerFragment crashed every launch on the Android 17 beta
 * ("Attempt to read from null array" in MiniControllerFragment.onCreateView, from
 * FragmentActivity.onStart). Startup must not create any Cast fragment, must not need Cast at
 * all, and Ulfur's own mini bar only appears while something is casting.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V192CastStartupTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        CastSupport.resetForTest();
        CastSupport.disabled = true;
        Caster.testRemote = null;
        CrashGuard.clearReport(app);
        CrashGuard.markStable(app);
        CrashGuard.setSafeModeForTest(false);
    }

    @After
    public void tearDown() {
        CastSupport.resetForTest();
        CastSupport.disabled = true;
        Caster.testRemote = null;
        CrashGuard.clearReport(app);
        CrashGuard.markStable(app);
        CrashGuard.setSafeModeForTest(false);
        VaultLock.lock();
    }

    private static void assertNoCastFragments(FragmentActivity a) {
        assertNoCastFragments(a.getSupportFragmentManager());
        assertNoFragmentContainers(a.getWindow().getDecorView());
    }

    private static void assertNoCastFragments(FragmentManager fm) {
        for (Fragment f : fm.getFragments()) {
            String n = f.getClass().getName();
            assertFalse("Cast fragment at startup: " + n,
                    n.contains("MiniController") || n.startsWith("com.google.android.gms.cast"));
            assertNoCastFragments(f.getChildFragmentManager());
        }
    }

    private static void assertNoFragmentContainers(View v) {
        assertFalse("no <fragment>/FragmentContainerView in the layout", v instanceof FragmentContainerView);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) assertNoFragmentContainers(g.getChildAt(i));
        }
    }

    private ActivityController<PlayerActivity> launchPlayer() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/Movies/Brighton%20beach%20sunset.mp4"));
        i.setClass(app, PlayerActivity.class);
        ActivityController<PlayerActivity> ctl = Robolectric.buildActivity(PlayerActivity.class, i).setup();
        TestUtil.awaitPlayer(ctl.get());
        return ctl;
    }

    @Test
    public void mainVaultAndPlayerStartWithCastUnavailableAndNoCastFragment() {
        // Cast "unavailable" for real: not force-disabled, but Robolectric has no Play services.
        CastSupport.disabled = false;

        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        idle();
        MainActivity m = main.get();
        assertNoCastFragments(m);
        assertNotNull(m.castBar());
        assertFalse("mini bar hidden: nothing is casting", m.castBar().isShowing());
        assertNull("bar not even inflated at startup", m.castBar().barView());
        assertEquals(View.GONE, m.findViewById(R.id.cast_mini_controller).getVisibility());
        assertFalse(CastSupport.isReady());
        main.pause().stop().destroy();

        ActivityController<VaultActivity> vault = Robolectric.buildActivity(VaultActivity.class).setup();
        idle();
        assertNoCastFragments(vault.get());
        assertFalse(vault.get().castBar().isShowing());
        assertEquals(View.GONE, vault.get().findViewById(R.id.vault_cast_mini_controller).getVisibility());
        vault.pause().stop().destroy();

        ActivityController<PlayerActivity> player = launchPlayer();
        idle();
        assertNoCastFragments(player.get());
        assertEquals(View.GONE, player.get().findViewById(R.id.btn_cast).getVisibility());
        player.pause().stop().destroy();
    }

    @Test
    public void castIsNotEvenInitialisedAfterACrashOrInSafeMode() {
        CastSupport.disabled = false;
        CrashGuard.onCrash(app, Thread.currentThread(), new NullPointerException("Attempt to read from null array"), 500);
        assertTrue(CastSupport.skipped(app));
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        idle();
        assertEquals("crash report pending: Cast skipped", 0, CastSupport.initAttempts);
        assertNotNull("crash dialog shown from onCreate", main.get().maybeShowCrashReport());
        assertNoCastFragments(main.get());
        main.pause().stop().destroy();

        CrashGuard.clearReport(app);
        CrashGuard.setSafeModeForTest(true);
        CastSupport.resetForTest();
        assertTrue(CastSupport.skipped(app));
        ActivityController<VaultActivity> vault = Robolectric.buildActivity(VaultActivity.class).setup();
        idle();
        assertEquals("safe mode: Cast skipped", 0, CastSupport.initAttempts);
        vault.pause().stop().destroy();

        CrashGuard.setSafeModeForTest(false);
        assertFalse(CastSupport.skipped(app));
    }

    @Test
    public void ulfurMiniBarShowsOnlyWhileCastingAndStopEndsTheSession() {
        V17FeatureTest.FakeRemote remote = new V17FeatureTest.FakeRemote();
        Caster.testRemote = remote;
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        idle();
        MainActivity m = main.get();
        CastMiniBar bar = m.castBar();
        bar.refresh();
        assertFalse("connected but nothing of ours playing: hidden", bar.isShowing());

        bar.bind(new CastMiniBar.State("Living Room TV", "Brighton beach sunset", true));
        assertTrue(bar.isShowing());
        View v = bar.barView();
        assertNotNull(v);
        assertEquals("Brighton beach sunset",
                ((android.widget.TextView) v.findViewById(R.id.cast_mini_title)).getText().toString());
        assertEquals("Casting to Living Room TV",
                ((android.widget.TextView) v.findViewById(R.id.cast_mini_subtitle)).getText().toString());
        assertNoCastFragments(m);

        v.findViewById(R.id.cast_mini_stop).performClick();
        assertFalse(remote.connected);
        assertFalse(bar.isShowing());
        main.pause().stop().destroy();
    }

    @Test
    public void noLayoutOrSourceUsesGooglesMiniControllerFragment() throws Exception {
        File layouts = new File("src/main/res/layout");
        if (!layouts.isDirectory()) layouts = new File("app/src/main/res/layout");
        File[] files = layouts.listFiles();
        assertNotNull(files);
        for (File f : files) {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(f.getName(), s.contains("MiniControllerFragment"));
            assertFalse(f.getName(), s.contains("<fragment"));
        }
        File src = new File(layouts.getParentFile().getParentFile(), "java/com/jamh/videoplayer");
        File[] java = src.listFiles();
        assertNotNull(java);
        for (File f : java) {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(f.getName(), s.contains("import com.google.android.gms.cast.framework.media.widget.MiniControllerFragment"));
            assertFalse(f.getName(), s.contains("new MiniControllerFragment"));
        }
    }
}
