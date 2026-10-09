package com.jamh.videoplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 1.6.0-pre: Private videos encrypted at rest (Tink StreamingAead), random access, migration safety. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V16EncryptionTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    /** Thrown by the hook to simulate the process dying at a given phase (an Error, so nothing catches it). */
    static final class SimulatedCrash extends Error {
        SimulatedCrash(String phase) {
            super("crash at " + phase);
        }
    }

    @Before
    public void setUp() {
        clearVault();
    }

    @After
    public void tearDown() {
        VaultMigration.hook = null;
        clearVault();
    }

    private void clearVault() {
        for (Vault.Entry e : Vault.readIndex(app)) Vault.delete(app, e);
        deleteTree(Vault.dir(app));
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static byte[] random(int n, int seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private Vault.Entry importBytes(byte[] data, String name) throws Exception {
        File src = File.createTempFile("src", ".mp4");
        Files.write(src.toPath(), data);
        try {
            return Vault.importFrom(app, Uri.fromFile(src), name, "video/mp4", -1, null);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
        }
    }

    /** Reads [pos, pos+len) of a vault Uri through the same DataSource stack the player uses. */
    private static byte[] readRange(DataSource ds, Uri uri, long pos, int len) throws IOException {
        DataSpec spec = new DataSpec.Builder().setUri(uri).setPosition(pos)
                .setLength(len < 0 ? C.LENGTH_UNSET : len).build();
        long opened = ds.open(spec);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            byte[] buf = new byte[7919]; // odd size: crosses 4 KB segment boundaries at varying offsets
            int n;
            while ((n = ds.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) out.write(buf, 0, n);
        } finally {
            ds.close();
        }
        assertEquals(opened, out.size());
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ at rest

    @Test
    public void importStoresOnlyCiphertextAndVerifies() throws Exception {
        byte[] data = random(1_000_000, 1);
        Vault.Entry e = importBytes(data, "Secret.mp4");
        assertTrue(e.enc);
        File stored = Vault.fileOf(app, e);
        assertTrue(stored.getName().endsWith(".uvx"));
        byte[] onDisk = Files.readAllBytes(stored.toPath());
        assertTrue("ciphertext is a bit longer (header + 16-byte tag per segment)", onDisk.length > data.length);
        // No 64-byte run of the plaintext appears in the stored file.
        byte[] probe = Arrays.copyOfRange(data, 500_000, 500_064);
        assertFalse(indexOf(onDisk, probe) >= 0);
        assertFalse("no plaintext copy next to it", Vault.plainFileOf(app, e).exists());
        assertEquals(Vault.sha256Of(writeTemp(data)), e.sha256);
        // Content Uri still uses the logical name; findByUri resolves it.
        Uri vu = Vault.uriFor(app, e);
        assertTrue(Vault.isVaultUri(vu));
        assertEquals(e.id, Vault.findByUri(app, vu).id);
        // The FileProvider no longer serves the vault directory at all.
        try {
            androidx.core.content.FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", stored);
            fail("vault/ must not be exposed by the FileProvider");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void ciphertextIsBoundToItsEntry() throws Exception {
        Vault.Entry a = importBytes(random(50_000, 2), "A.mp4");
        Vault.Entry b = importBytes(random(50_000, 3), "B.mp4");
        // Swap A's ciphertext into B's slot: authentication must fail rather than play A as B.
        Files.copy(Vault.fileOf(app, a).toPath(), Vault.fileOf(app, b).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            readRange(new VaultDataSource(app), Vault.uriFor(app, b), 0, -1);
            fail("expected authentication failure");
        } catch (IOException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------ random access

    @Test
    public void randomAccessSeeksReturnExactBytes() throws Exception {
        byte[] data = random(3 * 1024 * 1024 + 1234, 4); // not a multiple of the segment size
        Vault.Entry e = importBytes(data, "Seek test.mp4");
        Uri uri = Vault.uriFor(app, e);
        // The routing factory is what PlayerFactory wires into ExoPlayer.
        DataSource ds = VaultDataSource.routing(app, new DefaultDataSource.Factory(app)).createDataSource();

        assertArrayEquals("whole file", data, readRange(ds, uri, 0, -1));
        long[] edges = {0, 1, 4095, 4096, 4097, 4096 - 40, 4096 * 2 - 40 /* first segment is shorter (header) */,
                1_000_000, data.length - 1, data.length - 4096, data.length - 5000};
        for (long p : edges) {
            int len = (int) Math.min(10_000, data.length - p);
            assertArrayEquals("pos " + p, Arrays.copyOfRange(data, (int) p, (int) p + len), readRange(ds, uri, p, len));
        }
        // Open-ended from a position (what ExoPlayer does on a seek).
        assertArrayEquals(Arrays.copyOfRange(data, 2_000_001, data.length), readRange(ds, uri, 2_000_001, -1));
        // At EOF: an empty range, not an error.
        assertEquals(0, readRange(ds, uri, data.length, -1).length);

        Random r = new Random(5);
        long t0 = System.nanoTime();
        for (int i = 0; i < 300; i++) {
            int p = r.nextInt(data.length);
            int len = 1 + r.nextInt(Math.min(70_000, data.length - p));
            assertArrayEquals("random seek " + i, Arrays.copyOfRange(data, p, p + len), readRange(ds, uri, p, len));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // Seeking must not decrypt from the start: 300 seeks over 3 MB stay far below "300 full reads".
        assertTrue("300 random seeks took " + ms + " ms", ms < 6000);

        // Out of range is a proper error.
        try {
            readRange(ds, uri, data.length + 1, -1);
            fail("expected out-of-range");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void seekOnlyDecryptsNearbySegments() throws Exception {
        // A 24 MB file: reading 4 KB at the end must be about as fast as at the start.
        byte[] data = random(24 * 1024 * 1024, 6);
        Vault.Entry e = importBytes(data, "Big.mp4");
        Uri uri = Vault.uriFor(app, e);
        VaultDataSource ds = new VaultDataSource(app);
        readRange(ds, uri, 0, 4096); // warm up
        long t0 = System.nanoTime();
        for (int i = 0; i < 50; i++) readRange(ds, uri, data.length - 4096 - i * 997L, 4096);
        long tailMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        readRange(ds, uri, 0, -1);
        long fullMs = (System.nanoTime() - t1) / 1_000_000;
        assertTrue("50 tail seeks " + tailMs + " ms vs one full read " + fullMs + " ms", tailMs < Math.max(50, fullMs));
    }

    @Test
    public void mediaDataSourceReadAtMatchesPlaintext() throws Exception {
        byte[] data = random(600_000, 7);
        Vault.Entry e = importBytes(data, "Retriever.mp4");
        try (VaultMediaDataSource m = new VaultMediaDataSource(app, e)) {
            assertEquals(data.length, m.getSize());
            Random r = new Random(8);
            for (int i = 0; i < 200; i++) {
                int p = r.nextInt(data.length);
                int len = 1 + r.nextInt(20_000);
                byte[] buf = new byte[len + 10];
                int n = m.readAt(p, buf, 5, len);
                int expect = Math.min(len, data.length - p);
                assertEquals(expect, n);
                assertArrayEquals(Arrays.copyOfRange(data, p, p + expect), Arrays.copyOfRange(buf, 5, 5 + n));
            }
            assertEquals(-1, m.readAt(data.length, new byte[10], 0, 10));
        }
    }

    @Test
    @Config(sdk = 28)
    public void moveOutDecryptsAndVerifies() throws Exception {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        byte[] data = random(400_000, 9);
        Vault.Entry e = importBytes(data, "Going public.mp4");
        Uri out = Vault.moveOut(app, e);
        assertNotNull(out);
        File f = new File(out.getPath());
        assertArrayEquals("decrypted copy in Movies/UlfurVideo", data, Files.readAllBytes(f.toPath()));
        assertTrue(Vault.list(app).isEmpty());
        assertFalse(Vault.fileOf(app, e).exists());
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ------------------------------------------------------------------ migration

    /** A pre-1.6.0 entry: plaintext file + plaintext thumbnail, as 1.5.1 stored them. */
    private Vault.Entry legacy(String id, byte[] data) throws Exception {
        Vault.Entry e = new Vault.Entry();
        e.id = id;
        e.file = id + ".mp4";
        e.name = "Legacy " + id + ".mp4";
        e.mime = "video/mp4";
        e.size = data.length;
        e.addedMs = 1000;
        e.sha256 = Vault.sha256Of(writeTemp(data));
        File f = Vault.plainFileOf(app, e);
        Files.write(f.toPath(), data);
        File t = Vault.thumbOf(app, e);
        //noinspection ResultOfMethodCallIgnored
        t.getParentFile().mkdirs();
        Files.write(t.toPath(), new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
        Method add = Vault.class.getDeclaredMethod("add", android.content.Context.class, Vault.Entry.class);
        add.setAccessible(true);
        add.invoke(null, app, e);
        return e;
    }

    private Map<String, byte[]> seedLegacy(int n) throws Exception {
        Map<String, byte[]> m = new HashMap<>();
        for (int i = 0; i < n; i++) {
            byte[] d = random(150_000 + i * 70_001, 100 + i);
            m.put(legacy("old" + i, d).id, d);
        }
        return m;
    }

    /** Every video must be listed and play back byte-for-byte, whatever state it is in. */
    private void assertNothingLost(Map<String, byte[]> expected, String when) throws Exception {
        List<Vault.Entry> l = Vault.list(app);
        assertEquals(when + ": all entries listed", expected.size(), l.size());
        for (Vault.Entry e : l) {
            byte[] want = expected.get(e.id);
            assertNotNull(want);
            assertArrayEquals(when + ": " + e.id, want, readRange(new VaultDataSource(app), Vault.uriFor(app, e), 0, -1));
        }
    }

    private void assertFullyEncrypted(Map<String, byte[]> expected) throws Exception {
        assertNothingLost(expected, "after migration");
        for (Vault.Entry e : Vault.list(app)) {
            assertTrue(e.id + " encrypted", e.enc);
            assertFalse(e.id + " plaintext removed", Vault.plainFileOf(app, e).exists());
            assertFalse(e.id + " plain thumb removed", Vault.thumbOf(app, e).exists());
            assertTrue(e.id + " encrypted thumb", Vault.encThumbOf(app, e).isFile());
            assertEquals(e.id + " uri unchanged", e.id + ".mp4", e.file);
        }
        for (File f : Vault.dir(app).listFiles()) {
            assertFalse("no temp left: " + f.getName(), f.getName().startsWith(".mig-") || f.getName().startsWith(".tmp-"));
        }
        assertTrue(VaultMigration.pending(app).isEmpty());
    }

    @Test
    public void migrationEncryptsLegacyVideosInPlace() throws Exception {
        Map<String, byte[]> m = seedLegacy(3);
        Uri before = Vault.uriFor(app, Vault.list(app).get(0));
        List<String> phases = new ArrayList<>();
        VaultMigration.hook = (p, e) -> phases.add(e.id + ":" + p);
        VaultMigration.State s = VaultMigration.run(app);
        assertTrue(s.finished);
        assertEquals(3, s.done);
        assertEquals(3, s.total);
        assertTrue(s.failed.isEmpty());
        assertEquals(s.bytesTotal, s.bytesDone);
        assertEquals(15, phases.size()); // 5 phases x 3 files, in order, one file at a time
        assertEquals(Arrays.asList("written", "verified", "renamed", "indexed", "plain-deleted"),
                phases.subList(0, 5).stream().map(x -> x.substring(x.indexOf(':') + 1)).collect(java.util.stream.Collectors.toList()));
        assertFullyEncrypted(m);
        assertNotNull("old Uris keep working", Vault.findByUri(app, before));
        // Running again is a no-op.
        assertEquals(0, VaultMigration.run(app).total);
    }

    @Test
    public void migrationSurvivesACrashAtEveryPhase() throws Exception {
        String[] phases = {VaultMigration.PHASE_WRITTEN, VaultMigration.PHASE_VERIFIED, VaultMigration.PHASE_RENAMED,
                VaultMigration.PHASE_INDEXED, VaultMigration.PHASE_PLAIN_DELETED};
        for (String phase : phases) {
            clearVault();
            Map<String, byte[]> m = seedLegacy(3);
            // Crash while handling the second file, after the first one completed.
            VaultMigration.hook = (p, e) -> {
                if (e.id.equals("old1") && p.equals(phase)) throw new SimulatedCrash(phase);
            };
            try {
                VaultMigration.run(app);
                fail("expected the simulated crash at " + phase);
            } catch (SimulatedCrash expected) {
                // the process "died" here: no finally-cleanup beyond what Java runs anyway
            }
            VaultMigration.hook = null;
            assertNothingLost(m, "right after a crash at " + phase);
            // Next launch: recover + resume.
            VaultMigration.State s = VaultMigration.run(app);
            assertTrue(phase + ": " + s.failed, s.failed.isEmpty());
            assertFullyEncrypted(m);
        }
    }

    @Test
    public void migrationSurvivesAHardKillWithLeftovers() throws Exception {
        // Leftovers a real kill -9 could leave (finally blocks never ran): a temp file, an
        // unindexed encrypted copy for a plaintext entry, and plaintext next to an indexed copy.
        Map<String, byte[]> m = seedLegacy(3);
        VaultMigration.hook = (p, e) -> {
            if (e.id.equals("old1") && p.equals(VaultMigration.PHASE_INDEXED)) throw new SimulatedCrash(p);
        };
        try {
            VaultMigration.run(app);
            fail();
        } catch (SimulatedCrash expected) {
            // old0 done; old1 indexed as encrypted but its plaintext is still there
        }
        VaultMigration.hook = null;
        File dir = Vault.dir(app);
        List<Vault.Entry> plain = VaultMigration.pending(app);
        assertEquals("old1 is indexed as encrypted, one other file not started yet", 1, plain.size());
        String notYet = plain.get(0).id;
        Files.write(new File(dir, ".mig-" + notYet).toPath(), random(1000, 1));
        Files.write(new File(dir, notYet + ".uvx").toPath(), random(5000, 2)); // garbage, unindexed
        assertTrue(new File(dir, "old1.mp4").exists());
        assertNothingLost(m, "after kill");
        VaultMigration.State s = VaultMigration.run(app);
        assertTrue(s.failed.toString(), s.failed.isEmpty());
        assertFullyEncrypted(m);
    }

    @Test
    public void aFailedFileStaysPlaintextAndIsReported() throws Exception {
        Map<String, byte[]> m = seedLegacy(3);
        // old1: corrupt the encrypted copy before verification -> the check must catch it.
        VaultMigration.hook = (p, e) -> {
            if (e.id.equals("old1") && p.equals(VaultMigration.PHASE_WRITTEN)) {
                try (RandomAccessFile raf = new RandomAccessFile(new File(Vault.dir(app), ".mig-old1"), "rw")) {
                    raf.seek(5000);
                    int b = raf.read();
                    raf.seek(5000);
                    raf.write(b ^ 0x5A);
                }
            }
            if (e.id.equals("old2") && p.equals(VaultMigration.PHASE_VERIFIED)) throw new IOException("disk full");
        };
        VaultMigration.State s = VaultMigration.run(app);
        VaultMigration.hook = null;
        assertEquals(2, s.failed.size());
        assertTrue(s.failed.toString(), s.failed.stream().anyMatch(f -> f.startsWith("Legacy old1.mp4: ")));
        assertTrue(s.failed.toString(), s.failed.contains("Legacy old2.mp4: disk full"));
        assertNothingLost(m, "after failures");
        Map<String, Vault.Entry> byId = new HashMap<>();
        for (Vault.Entry e : Vault.list(app)) byId.put(e.id, e);
        assertTrue(byId.get("old0").enc);
        assertFalse("left as plaintext", byId.get("old1").enc);
        assertFalse(byId.get("old2").enc);
        assertTrue(Vault.plainFileOf(app, byId.get("old1")).isFile());
        assertFalse("no partial encrypted copy", new File(Vault.dir(app), "old1.uvx").exists());
        assertEquals(2, VaultMigration.pending(app).size());
        // Retried next launch.
        assertTrue(VaultMigration.run(app).failed.isEmpty());
        assertFullyEncrypted(m);
    }

    @Test
    public void deletingDuringMigrationLeavesNoOrphans() throws Exception {
        seedLegacy(2);
        VaultMigration.hook = (p, e) -> {
            if (e.id.equals("old0") && p.equals(VaultMigration.PHASE_RENAMED)) {
                for (Vault.Entry x : Vault.readIndex(app)) if (x.id.equals("old0")) Vault.delete(app, x);
            }
        };
        VaultMigration.State s = VaultMigration.run(app);
        VaultMigration.hook = null;
        assertEquals(1, s.failed.size());
        assertEquals(1, Vault.list(app).size());
        assertFalse(new File(Vault.dir(app), "old0.uvx").exists());
        assertFalse(new File(Vault.dir(app), "old0.mp4").exists());
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void vaultShowsMigrationProgressAndFailures() throws Exception {
        org.robolectric.android.controller.ActivityController<VaultActivity> ctl =
                org.robolectric.Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity a = ctl.get();
        TestUtil.idle();
        VaultLock.unlock();
        a.showContent();
        TestUtil.idle();
        VaultMigration.State st = new VaultMigration.State();
        st.running = true;
        st.total = 12;
        st.done = 4;
        st.bytesTotal = 3_200_000_000L;
        st.bytesDone = 1_150_000_000L;
        a.onMigrationState(st);
        android.view.View card = a.findViewById(R.id.vault_migration);
        assertEquals(android.view.View.VISIBLE, card.getVisibility());
        assertEquals("Encrypting private videos · 5 of 12",
                ((android.widget.TextView) a.findViewById(R.id.vault_migration_title)).getText().toString());
        android.view.View decor = a.getWindow().getDecorView();
        PreviewScreenshotTest.renderToBitmap(decor, 1080, 2340);
        PreviewScreenshotTest.save(decor, "v16_vault_encrypting.png", 1080, 2340);

        VaultMigration.State done = new VaultMigration.State();
        done.finished = true;
        done.failed.add("Wedding speech.mp4: Not enough free space to encrypt it (needs 486 MB)");
        a.onMigrationState(done);
        assertEquals("1 video couldn't be encrypted yet",
                ((android.widget.TextView) a.findViewById(R.id.vault_migration_title)).getText().toString());
        assertEquals(android.view.View.VISIBLE, a.findViewById(R.id.vault_migration_retry).getVisibility());
        PreviewScreenshotTest.renderToBitmap(decor, 1080, 2340);
        PreviewScreenshotTest.save(decor, "v16_vault_encrypt_failed.png", 1080, 2340);

        a.onMigrationState(new VaultMigration.State());
        assertEquals(android.view.View.GONE, card.getVisibility());
        ctl.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ helpers

    private static File writeTemp(byte[] d) throws IOException {
        File f = File.createTempFile("hash", ".bin");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(d);
        }
        f.deleteOnExit();
        return f;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
