package com.jamh.videoplayer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 1.6.0: encrypts Private videos added by older versions, in place, one file at a time.
 *
 * <p>Per file: encrypt into {@code .mig-<id>} (fsynced) → decrypt it again and compare size and
 * SHA-256 with the plaintext → rename to {@code <id>.uvx} → atomically switch the index entry
 * to the encrypted file (fsynced) → only then delete the plaintext. At every moment the index
 * points to a complete, verified copy, so a crash or power cut can't lose a video;
 * {@link #recover} tidies up half-finished steps on the next run. A file that can't be
 * encrypted (no space, I/O error, failed check, key unavailable) stays plaintext and readable,
 * is reported, and is retried on the next launch.
 */
final class VaultMigration {

    static final String TMP_PREFIX = ".mig-";
    private static final long SPACE_MARGIN = 50L * 1024 * 1024;

    /** Snapshot of the migration's progress. */
    static final class State {
        boolean running;
        boolean finished;
        int done;
        int total;
        long bytesDone;
        long bytesTotal;
        long bytesDoneBase; // bytes of files already handled
        @Nullable String current;
        final List<String> failed = new ArrayList<>(); // "name: reason"

        State copy() {
            State s = new State();
            s.running = running;
            s.finished = finished;
            s.done = done;
            s.total = total;
            s.bytesDone = bytesDone;
            s.bytesTotal = bytesTotal;
            s.bytesDoneBase = bytesDoneBase;
            s.current = current;
            s.failed.addAll(failed);
            return s;
        }
    }

    interface Listener {
        void onMigrationState(State s);
    }

    /** Test hook: called after each phase; throwing simulates a crash or a failure there. */
    @VisibleForTesting
    interface Hook {
        void at(String phase, Vault.Entry e) throws IOException;
    }

    static final String PHASE_WRITTEN = "written", PHASE_VERIFIED = "verified", PHASE_RENAMED = "renamed",
            PHASE_INDEXED = "indexed", PHASE_PLAIN_DELETED = "plain-deleted";

    @VisibleForTesting static volatile Hook hook;
    /** Tests turn this off so the app doesn't migrate behind their back. */
    @VisibleForTesting static volatile boolean autoStart = true;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static State state = new State();
    private static Thread worker;

    private VaultMigration() {
    }

    static synchronized State state() {
        return state.copy();
    }

    static void addListener(Listener l) {
        listeners.add(l);
        l.onMigrationState(state());
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Starts the background migration if there's anything to do and it isn't running yet. */
    static synchronized void start(Context ctx) {
        if (worker != null && worker.isAlive()) return;
        Context app = ctx.getApplicationContext();
        worker = new Thread(() -> {
            try {
                run(app);
            } catch (RuntimeException ignored) {
                // never crash the app over this; whatever is left is retried next launch
            }
        }, "vault-migration");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    @VisibleForTesting
    static synchronized void awaitIdleForTest(long ms) throws InterruptedException {
        if (worker != null) worker.join(ms);
    }

    /** Entries that are still stored as plaintext. */
    static List<Vault.Entry> pending(Context ctx) {
        List<Vault.Entry> out = new ArrayList<>();
        for (Vault.Entry e : Vault.readIndex(ctx)) {
            if (!e.enc && Vault.plainFileOf(ctx, e).isFile()) out.add(e);
        }
        return out;
    }

    /** Runs the whole migration on the calling thread. Returns the final state. */
    @WorkerThread
    static State run(Context ctx) {
        recover(ctx);
        List<Vault.Entry> todo = pending(ctx);
        State s = new State();
        s.running = !todo.isEmpty();
        s.total = todo.size();
        for (Vault.Entry e : todo) s.bytesTotal += Vault.plainFileOf(ctx, e).length();
        publish(s);
        for (Vault.Entry e : todo) {
            s.current = e.name;
            publish(s);
            long len = Vault.plainFileOf(ctx, e).length();
            try {
                migrateOne(ctx, e, s);
            } catch (IOException | RuntimeException ex) {
                String why = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                s.failed.add(e.name + ": " + why);
            }
            s.done++;
            s.bytesDoneBase += len;
            s.bytesDone = Math.min(s.bytesTotal, s.bytesDoneBase);
            publish(s);
        }
        // Thumbnails of already-encrypted entries that still have a plaintext copy.
        for (Vault.Entry e : Vault.readIndex(ctx)) {
            if (e.enc) migrateThumb(ctx, e);
        }
        s.running = false;
        s.finished = true;
        s.current = null;
        publish(s);
        return s.copy();
    }

    /**
     * Tidies up after a crash: removes temp files and unindexed encrypted copies, and deletes
     * plaintext that is already safely encrypted (the index points to the verified copy).
     */
    static void recover(Context ctx) {
        File dir = Vault.dir(ctx);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                if (f.getName().startsWith(TMP_PREFIX)) f.delete();
            }
        }
        for (Vault.Entry e : Vault.readIndex(ctx)) {
            File enc = new File(dir, Vault.encName(e.id));
            File plain = Vault.plainFileOf(ctx, e);
            if (!e.enc) {
                // Encrypted copy written but the index never switched to it: redo from scratch.
                if (plain.isFile() && enc.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    enc.delete();
                }
            } else if (Vault.fileOf(ctx, e).isFile() && plain.isFile()
                    && !plain.getName().equals(e.store)) {
                //noinspection ResultOfMethodCallIgnored
                plain.delete();
            }
        }
    }

    private static void migrateOne(Context ctx, Vault.Entry e, State s) throws IOException {
        File dir = Vault.dir(ctx);
        File plain = Vault.plainFileOf(ctx, e);
        long len = plain.length();
        if (dir.getUsableSpace() < len + SPACE_MARGIN) {
            throw new IOException("Not enough free space to encrypt it (needs " + Fmt.size(len) + ")");
        }
        File tmp = new File(dir, TMP_PREFIX + e.id);
        File enc = new File(dir, Vault.encName(e.id));
        boolean indexed = false;
        try {
            MessageDigest md = Vault.sha256();
            long base = s.bytesDoneBase;
            long written;
            try (InputStream in = new FileInputStream(plain)) {
                written = Vault.encryptTo(ctx, in, tmp, VaultCrypto.aadVideo(e.id), md, new Vault.Progress() {
                    @Override public void onProgress(long done, long total) {
                        s.bytesDone = done;
                        publish(s);
                    }

                    @Override public boolean isCancelled() {
                        return false;
                    }
                }, s.bytesTotal, base);
            }
            String hash = Vault.hex(md.digest());
            phase(PHASE_WRITTEN, e);
            if (written != len) throw new IOException("Read " + written + " of " + len + " bytes");
            Object[] check = Vault.decryptAndHash(ctx, tmp, VaultCrypto.aadVideo(e.id));
            if ((Long) check[0] != len || !hash.equals(check[1])) {
                throw new IOException("Encrypted copy didn't verify");
            }
            phase(PHASE_VERIFIED, e);
            if (!tmp.renameTo(enc)) throw new IOException("Couldn't store the encrypted copy");
            phase(PHASE_RENAMED, e);
            Vault.Entry upd = Vault.markEncrypted(ctx, e.id, enc.getName(), hash);
            indexed = true;
            phase(PHASE_INDEXED, e);
            if (!plain.delete() && plain.exists()) {
                // Harmless: recover() deletes it next time. The video itself is safe either way.
                return;
            }
            phase(PHASE_PLAIN_DELETED, e);
            migrateThumb(ctx, upd);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (!indexed) {
                //noinspection ResultOfMethodCallIgnored
                enc.delete();
            }
        }
    }

    /** Re-saves a legacy plaintext thumbnail encrypted, then removes the plaintext one. */
    static void migrateThumb(Context ctx, Vault.Entry e) {
        File plain = Vault.thumbOf(ctx, e);
        if (!plain.isFile()) return;
        try {
            if (!Vault.encThumbOf(ctx, e).isFile()) Vault.writeEncThumb(ctx, e, Files.readAllBytes(plain.toPath()));
            //noinspection ResultOfMethodCallIgnored
            plain.delete();
        } catch (IOException | RuntimeException ignored) {
            // cosmetic; retried next launch
        }
    }

    private static void phase(String p, Vault.Entry e) throws IOException {
        Hook h = hook;
        if (h != null) h.at(p, e);
    }

    private static void publish(State s) {
        State snap;
        synchronized (VaultMigration.class) {
            state = s.copy();
            snap = state.copy();
        }
        if (listeners.isEmpty()) return;
        MAIN.post(() -> {
            for (Listener l : listeners) l.onMigrationState(snap);
        });
    }
}
