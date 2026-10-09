package com.jamh.videoplayer;

import static com.jamh.videoplayer.TestUtil.idle;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 1.6.0: encrypted, password-protected backup / restore of Private videos, plain export. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class V16BackupTest {

    private final Application app = ApplicationProvider.getApplicationContext();
    private static final char[] PW = "correct horse battery".toCharArray();

    @Before
    public void setUp() {
        VaultBackup.iterations = 1000; // the real 600k is covered by the PBKDF2 vector tests' code path
        wipe();
    }

    @After
    public void tearDown() {
        VaultBackup.iterations = VaultBackup.DEFAULT_ITERATIONS;
        VaultLock.lock();
        wipe();
    }

    private void wipe() {
        for (Vault.Entry e : Vault.readIndex(app)) Vault.delete(app, e);
    }

    // ------------------------------------------------------------------ PBKDF2

    private static byte[] hex(String h) {
        h = h.replace(" ", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    public void pbkdf2MatchesRfc7914AndTheJvm() throws Exception {
        // RFC 7914 §11: PBKDF2-HMAC-SHA256, P="passwd", S="salt", c=1, dkLen=64
        assertArrayEquals(hex("55 ac 04 6e 56 e3 08 9f ec 16 91 c2 25 44 b6 05 f9 41 85 21 6d de 04 65 e6 8b 9d 57 c2 0d ac bc"
                        + "49 ca 9c cc f1 79 b6 45 99 16 64 b3 9d 77 ef 31 7c 71 b8 45 b1 e3 0b d5 09 11 20 41 d3 a1 97 83"),
                VaultBackup.pbkdf2("passwd".toCharArray(), "salt".getBytes("US-ASCII"), 1, 64));
        // RFC 7914 §11: P="Password", S="NaCl", c=80000, dkLen=64
        assertArrayEquals(hex("4d dc d8 f6 0b 98 be 21 83 0c ee 5e f2 27 01 f9 64 1a 44 18 d0 4c 04 14 ae ff 08 87 6b 34 ab 56"
                        + "a1 d4 25 a1 22 58 33 54 9a db 84 1b 51 c9 b3 17 6a 27 2b de bb a1 d0 78 47 8f 62 b3 97 f3 3c 8d"),
                VaultBackup.pbkdf2("Password".toCharArray(), "NaCl".getBytes("US-ASCII"), 80000, 64));
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        Random r = new Random(1);
        for (int i = 0; i < 5; i++) {
            byte[] salt = new byte[16];
            r.nextBytes(salt);
            char[] pw = ("pässwörd-" + i + "-✓").toCharArray();
            byte[] want = f.generateSecret(new PBEKeySpec(pw, salt, 2000 + i, 256)).getEncoded();
            assertArrayEquals(want, VaultBackup.pbkdf2(pw, salt, 2000 + i, 32));
        }
    }

    // ------------------------------------------------------------------ round trip

    private Map<String, byte[]> seed() throws Exception {
        Map<String, byte[]> m = new HashMap<>();
        int[] sizes = {5 * 1024 * 1024 + 17, 120_000, 1};
        String[] names = {"Wedding speech.mp4", "Scan of passport.mkv", "Tiny.mp4"};
        for (int i = 0; i < 3; i++) {
            byte[] d = new byte[sizes[i]];
            new Random(10 + i).nextBytes(d);
            File src = File.createTempFile("src", ".mp4");
            Files.write(src.toPath(), d);
            Vault.Entry e = Vault.importFrom(app, Uri.fromFile(src), names[i], i == 1 ? "video/x-matroska" : "video/mp4", -1, null);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            PreviewScreenshotTest.fakeFrame(360, 203, i).compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
            Vault.writeEncThumb(app, e, jpeg.toByteArray());
            m.put(names[i], d);
        }
        return m;
    }

    private File backup() throws Exception {
        File f = File.createTempFile("vault", VaultBackup.EXT);
        List<long[]> progress = new ArrayList<>();
        try (FileOutputStream out = new FileOutputStream(f)) {
            VaultBackup.export(app, Vault.list(app), PW, out, new VaultBackup.Progress() {
                @Override public void onProgress(long done, long total, int index, int count) {
                    progress.add(new long[]{done, total, index, count});
                }

                @Override public boolean isCancelled() {
                    return false;
                }
            });
        }
        long[] last = progress.get(progress.size() - 1);
        assertEquals("progress reaches the total", last[1], last[0]);
        assertEquals(3, last[3]);
        return f;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private void assertVaultHas(Map<String, byte[]> m) throws Exception {
        List<Vault.Entry> l = Vault.list(app);
        assertEquals(m.size(), l.size());
        for (Vault.Entry e : l) {
            assertTrue(e.enc);
            try (InputStream in = Vault.openStream(app, e)) {
                assertArrayEquals(e.name, m.get(e.name), readAll(in));
            }
            assertNotNull("thumbnail restored", Vault.loadThumb(app, e));
        }
    }

    @Test
    public void backupRoundTripRestoresEverythingOnAFreshInstall() throws Exception {
        Map<String, byte[]> m = seed();
        File f = backup();
        byte[] archive = Files.readAllBytes(f.toPath());
        assertEquals("ULFURBK1", new String(archive, 0, 8, "US-ASCII"));
        byte[] big = m.get("Wedding speech.mp4");
        assertTrue("no plaintext in the archive", indexOf(archive, Arrays.copyOfRange(big, 1_000_000, 1_000_048)) < 0);
        assertTrue("no file names in the archive", indexOf(archive, "Wedding".getBytes("UTF-8")) < 0);

        // "Reinstall": the vault and its key are gone.
        wipe();
        VaultCrypto.testOverride = VaultCrypto.newInMemory();
        VaultBackup.Result r;
        try (InputStream in = new FileInputStream(f)) {
            r = VaultBackup.restore(app, in, PW, null);
        }
        assertEquals(3, r.restored);
        assertTrue(r.failed.toString(), r.failed.isEmpty());
        assertVaultHas(m);

        // Restoring the same backup again doesn't duplicate anything.
        try (InputStream in = new FileInputStream(f)) {
            r = VaultBackup.restore(app, in, PW, null);
        }
        assertEquals(0, r.restored);
        assertEquals(3, r.skipped);
        assertEquals(3, Vault.list(app).size());
    }

    @Test
    public void wrongPasswordOrTamperingRestoresNothing() throws Exception {
        seed();
        File f = backup();
        wipe();
        try (InputStream in = new FileInputStream(f)) {
            VaultBackup.restore(app, in, "wrong password!".toCharArray(), null);
            fail("expected wrong password");
        } catch (VaultBackup.WrongPasswordException expected) {
            // ok
        }
        assertTrue(Vault.listAll(app).isEmpty());
        assertNoTemp();

        // Header is authenticated: lowering the iteration count is detected.
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek(12);
            raf.writeInt(999);
        }
        try (InputStream in = new FileInputStream(f)) {
            VaultBackup.restore(app, in, PW, null);
            fail("expected failure");
        } catch (VaultBackup.WrongPasswordException expected) {
            // ok
        }
        assertTrue(Vault.listAll(app).isEmpty());

        // Not a backup at all.
        File junk = File.createTempFile("junk", ".bin");
        Files.write(junk.toPath(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
        try (InputStream in = new FileInputStream(junk)) {
            VaultBackup.restore(app, in, PW, null);
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("isn't an Ulfur Video backup"));
        }
    }

    @Test
    public void truncatedBackupKeepsCompleteVideosOnly() throws Exception {
        Map<String, byte[]> m = seed();
        File f = backup();
        wipe();
        // Cut it in the middle of the big (first) video... list order is newest first: Tiny, Scan, Wedding.
        byte[] a = Files.readAllBytes(f.toPath());
        Files.write(f.toPath(), Arrays.copyOf(a, a.length - 2_000_000));
        VaultBackup.Result r;
        try (InputStream in = new FileInputStream(f)) {
            r = VaultBackup.restore(app, in, PW, null);
        }
        assertEquals(2, r.restored);
        assertEquals(1, r.failed.size());
        assertTrue(r.failed.get(0), r.failed.get(0).startsWith("Wedding speech.mp4"));
        m.remove("Wedding speech.mp4");
        assertVaultHas(m);
        assertNoTemp();
    }

    @Test
    public void plainExportWritesDecryptedCopies() throws Exception {
        Map<String, byte[]> m = seed();
        File dir = Files.createTempDirectory("plain").toFile();
        int n = VaultBackupFlow.exportPlain(app, Vault.list(app), (name, mime) -> new FileOutputStream(new File(dir, name)), null);
        assertEquals(3, n);
        for (Map.Entry<String, byte[]> e : m.entrySet()) {
            assertArrayEquals(e.getValue(), Files.readAllBytes(new File(dir, e.getKey()).toPath()));
        }
        assertEquals("still in Private", 3, Vault.list(app).size());
    }

    // ------------------------------------------------------------------ UI

    @Test
    public void backupDialogsShowSizeAndCheckThePassword() throws Exception {
        seed();
        VaultLock.unlock();
        ActivityController<VaultActivity> ctl = Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity a = ctl.get();
        a.showContent();
        TestUtil.await(() -> { idle(); return Vault.list(app).size() == 3; }, 5000);
        AlertDialog d = a.backupFlow().startBackup(Vault.list(app));
        idle();
        String msg = ((TextView) d.findViewById(android.R.id.message)).getText().toString();
        assertTrue(msg, msg.startsWith("3 videos · 5.1 MB"));
        View root = d.getWindow().getDecorView();
        ((TextInputEditText) root.findViewWithTag("pw1")).setText("short");
        ((TextInputEditText) root.findViewWithTag("pw2")).setText("short");
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertEquals("Use at least 8 characters", ((TextInputLayout) root.findViewWithTag("pw2layout")).getError().toString());
        ((TextInputEditText) root.findViewWithTag("pw1")).setText("long enough 1");
        ((TextInputEditText) root.findViewWithTag("pw2")).setText("long enough 2");
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertEquals("Passwords don't match", ((TextInputLayout) root.findViewWithTag("pw2layout")).getError().toString());
        assertTrue(d.isShowing());
        V13FeatureTest.write(dialogShot(a, d), "v16_backup_password.png");
        d.dismiss();

        AlertDialog w = a.backupFlow().startPlainExport(Vault.list(app));
        idle();
        String wm = ((TextView) w.findViewById(android.R.id.message)).getText().toString();
        assertTrue(wm, wm.contains("NOT protected"));
        V13FeatureTest.write(dialogShot(a, w), "v16_plain_export_warning.png");
        w.dismiss();
        ctl.pause().stop().destroy();
    }

    private static Bitmap dialogShot(VaultActivity a, AlertDialog d) {
        Bitmap base = PreviewScreenshotTest.renderToBitmap(a.getWindow().getDecorView(), 1080, 2340);
        View dv = d.getWindow().getDecorView();
        int w = 1080 - 2 * 72;
        dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340 - 60, View.MeasureSpec.AT_MOST));
        dv.layout(0, 0, w, dv.getMeasuredHeight());
        android.graphics.Canvas c = new android.graphics.Canvas(base);
        c.drawColor(0x99000000);
        c.translate(72, (2340 - dv.getMeasuredHeight()) / 2f);
        dv.draw(c);
        return base;
    }

    // ------------------------------------------------------------------ helpers

    private void assertNoTemp() {
        File[] fs = Vault.dir(app).listFiles();
        if (fs == null) return;
        for (File f : fs) assertFalse(f.getName(), f.getName().startsWith(".tmp-") || f.getName().startsWith(".mig-"));
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
