package com.jamh.videoplayer;

/**
 * Picked up by Robolectric automatically ("Test" + application class name). Robolectric has no
 * AndroidKeyStore, so the vault uses a random in-memory Tink keyset, and the background
 * encryption migration only runs when a test calls it.
 */
public class TestVideoPlayerApp extends VideoPlayerApp {
    @Override
    public void onCreate() {
        VaultCrypto.testOverride = VaultCrypto.newInMemory();
        VaultMigration.autoStart = false;
        CastSupport.disabled = true; // no Play services in Robolectric; Cast tests use Caster.testRemote
        super.onCreate();
    }
}
