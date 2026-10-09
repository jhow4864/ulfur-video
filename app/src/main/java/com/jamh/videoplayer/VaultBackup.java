package com.jamh.videoplayer;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 1.6.0: password-protected backup of Private videos, to restore after a reinstall or on
 * another phone (the vault key itself never leaves the Android Keystore).
 *
 * <p>Format ({@code .ulfurbak}):
 * <pre>
 *   "ULFURBK1" | u32 version | u32 PBKDF2 iterations | u8 salt length | salt      (header = AAD)
 *   AES-GCM-HKDF streaming ciphertext (4 KB segments, Tink) of:
 *     u32 manifest length | manifest JSON (entries + thumbnail lengths)
 *     for each entry: thumbnail JPEG bytes, then the video's plaintext bytes
 * </pre>
 * The key is PBKDF2-HMAC-SHA256(password, salt, iterations) → 32 bytes, used as the HKDF input
 * key of Tink's AesGcmHkdfStreaming (the same streaming AEAD as the vault). Everything is
 * streamed: memory use doesn't depend on video size. A wrong password or any change to the
 * file fails authentication. Restoring re-encrypts each video with this phone's vault key
 * and checks its SHA-256 against the manifest before adding it.
 */
final class VaultBackup {

    static final byte[] MAGIC = "ULFURBK1".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;
    static final int DEFAULT_ITERATIONS = 600_000;
    static final String MIME = "application/octet-stream";
    static final String EXT = ".ulfurbak";
    private static final long SPACE_MARGIN = 50L * 1024 * 1024;

    /** Iterations for new backups (tests lower it to stay fast). */
    @VisibleForTesting
    static int iterations = DEFAULT_ITERATIONS;

    interface Progress {
        /** @param done bytes processed, @param total bytes overall, @param index 0-based video, @param count videos */
        void onProgress(long done, long total, int index, int count);

        boolean isCancelled();
    }

    static final class WrongPasswordException extends IOException {
        WrongPasswordException() {
            super("Wrong password, or the backup file is damaged");
        }
    }

    static final class Result {
        int restored;
        int skipped;        // already in Private (same id + same checksum)
        final List<String> failed = new ArrayList<>();
    }

    private VaultBackup() {
    }

    // ------------------------------------------------------------------ export

    static long totalBytes(Context ctx, List<Vault.Entry> entries) {
        long t = 0;
        for (Vault.Entry e : entries) t += Math.max(0, e.size);
        return t;
    }

    /** Writes an encrypted backup of {@code entries} to {@code rawOut} (flushed, not closed). */
    @WorkerThread
    static void export(Context ctx, List<Vault.Entry> entries, char[] password, OutputStream rawOut,
                       @Nullable Progress progress) throws IOException {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        int iter = iterations;
        byte[] header = header(VERSION, iter, salt);
        // Thumbnails are small: collect them first so the manifest can carry their lengths.
        List<byte[]> thumbs = new ArrayList<>();
        JSONArray arr = new JSONArray();
        try {
            for (Vault.Entry e : entries) {
                byte[] t = readThumb(ctx, e);
                thumbs.add(t);
                JSONObject o = e.toJson();
                o.remove("store");
                o.remove("enc");
                o.put("thumbLen", t.length);
                arr.put(o);
            }
        } catch (JSONException ex) {
            throw new IOException(ex);
        }
        byte[] manifest;
        try {
            manifest = new JSONObject().put("version", VERSION).put("created", System.currentTimeMillis())
                    .put("entries", arr).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            throw new IOException(ex);
        }
        long total = totalBytes(ctx, entries);
        long done = 0;
        byte[] key = pbkdf2(password, salt, iter, 32);
        try {
            StreamingAead sa = aead(key);
            OutputStream buffered = new BufferedOutputStream(rawOut, 64 * 1024);
            buffered.write(header);
            try (DataOutputStream out = new DataOutputStream(sa.newEncryptingStream(nonClosing(buffered), header))) {
                out.writeInt(manifest.length);
                out.write(manifest);
                byte[] buf = new byte[256 * 1024];
                for (int i = 0; i < entries.size(); i++) {
                    Vault.Entry e = entries.get(i);
                    out.write(thumbs.get(i));
                    MessageDigest md = Vault.sha256();
                    long n = 0;
                    try (InputStream in = Vault.openStream(ctx, e)) {
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            if (progress != null && progress.isCancelled()) throw new IOException("Cancelled");
                            out.write(buf, 0, r);
                            md.update(buf, 0, r);
                            n += r;
                            done += r;
                            if (progress != null) progress.onProgress(done, total, i, entries.size());
                        }
                    }
                    if (n != e.size) throw new IOException("\u201c" + e.name + "\u201d changed while backing up");
                    if (e.sha256 != null && !e.sha256.isEmpty() && !e.sha256.equals(Vault.hex(md.digest()))) {
                        throw new IOException("\u201c" + e.name + "\u201d didn't match its checksum; backup stopped");
                    }
                }
            }
            buffered.flush();
        } catch (GeneralSecurityException ex) {
            throw new IOException("Encryption failed", ex);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] readThumb(Context ctx, Vault.Entry e) {
        File enc = Vault.encThumbOf(ctx, e);
        try {
            if (enc.isFile()) {
                try (InputStream in = VaultCrypto.get(ctx).newDecryptingStream(new java.io.FileInputStream(enc),
                        VaultCrypto.aadThumb(e.id))) {
                    return readAll(in);
                }
            }
            File plain = Vault.thumbOf(ctx, e);
            if (plain.isFile()) return java.nio.file.Files.readAllBytes(plain.toPath());
        } catch (IOException | GeneralSecurityException | RuntimeException ignored) {
            // thumbnails are cosmetic
        }
        return new byte[0];
    }

    // ------------------------------------------------------------------ import

    /** Reads only the (unencrypted) header: checks it's a backup. Returns {version, iterations}. */
    static int[] peekHeader(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] magic = new byte[MAGIC.length];
        d.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("This isn't an Ulfur Video backup");
        return new int[]{d.readInt(), d.readInt()};
    }

    /** Restores a backup into Private videos. Videos already there (same id + checksum) are skipped. */
    @WorkerThread
    static Result restore(Context ctx, InputStream rawIn, char[] password, @Nullable Progress progress) throws IOException {
        DataInputStream hin = new DataInputStream(new BufferedInputStream(rawIn, 64 * 1024));
        byte[] magic = new byte[MAGIC.length];
        try {
            hin.readFully(magic);
        } catch (EOFException e) {
            throw new IOException("This isn't an Ulfur Video backup");
        }
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("This isn't an Ulfur Video backup");
        int version = hin.readInt();
        if (version != VERSION) throw new IOException("This backup was made by a newer Ulfur Video");
        int iter = hin.readInt();
        int saltLen = hin.readUnsignedByte();
        if (iter < 1 || iter > 50_000_000 || saltLen < 8 || saltLen > 64) throw new IOException("Damaged backup header");
        byte[] salt = new byte[saltLen];
        hin.readFully(salt);
        byte[] header = header(version, iter, salt);
        byte[] key = pbkdf2(password, salt, iter, 32);
        Result res = new Result();
        try {
            StreamingAead sa = aead(key);
            DataInputStream in = new DataInputStream(sa.newDecryptingStream(hin, header));
            int mlen;
            byte[] mbytes;
            try {
                mlen = in.readInt();
                if (mlen < 2 || mlen > 64 * 1024 * 1024) throw new WrongPasswordException();
                mbytes = new byte[mlen];
                in.readFully(mbytes);
            } catch (WrongPasswordException ex) {
                throw ex;
            } catch (IOException ex) {
                // The first segment didn't authenticate: wrong password (or a modified file).
                throw new WrongPasswordException();
            }
            List<Vault.Entry> entries = new ArrayList<>();
            List<Integer> thumbLens = new ArrayList<>();
            try {
                JSONArray arr = new JSONObject(new String(mbytes, StandardCharsets.UTF_8)).getJSONArray("entries");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Vault.Entry e = Vault.Entry.fromJson(o);
                    e.enc = false;
                    e.store = "";
                    entries.add(e);
                    thumbLens.add(o.optInt("thumbLen", 0));
                }
            } catch (JSONException ex) {
                throw new IOException("Damaged backup (manifest)", ex);
            }
            long total = 0;
            for (Vault.Entry e : entries) total += e.size;
            File dir = Vault.dir(ctx);
            if (dir.getUsableSpace() < total + SPACE_MARGIN) {
                throw new IOException("Not enough free space: the backup needs " + Fmt.size(total));
            }
            Set<String> existing = new HashSet<>();
            for (Vault.Entry e : Vault.readIndex(ctx)) existing.add(e.id + "|" + e.sha256);
            Set<String> ids = new HashSet<>();
            for (Vault.Entry e : Vault.readIndex(ctx)) ids.add(e.id);
            long done = 0;
            for (int i = 0; i < entries.size(); i++) {
                Vault.Entry e = entries.get(i);
                byte[] thumb = new byte[thumbLens.get(i)];
                try {
                    in.readFully(thumb);
                } catch (IOException ex) {
                    res.failed.add(e.name + ": backup is incomplete");
                    break;
                }
                boolean skip = existing.contains(e.id + "|" + e.sha256);
                try {
                    if (skip) {
                        skipFully(in, e.size, progress);
                        res.skipped++;
                    } else {
                        if (ids.contains(e.id)) e.id = UUID.randomUUID().toString().replace("-", "");
                        restoreOne(ctx, in, e, thumb, progress, done, total, i, entries.size());
                        ids.add(e.id);
                        res.restored++;
                    }
                } catch (IOException ex) {
                    res.failed.add(e.name + ": " + (ex.getMessage() == null ? "read error" : ex.getMessage()));
                    if (ex.getMessage() != null && ex.getMessage().equals("Cancelled")) break;
                    // The stream position is unknown after a failure inside this video: stop here.
                    for (int j = i + 1; j < entries.size(); j++) res.failed.add(entries.get(j).name + ": not restored");
                    break;
                }
                done += e.size;
                if (progress != null) progress.onProgress(done, total, i, entries.size());
            }
        } catch (GeneralSecurityException ex) {
            throw new IOException("Decryption failed", ex);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        return res;
    }

    private static void restoreOne(Context ctx, DataInputStream in, Vault.Entry e, byte[] thumb,
                                   @Nullable Progress progress, long base, long total, int index, int count) throws IOException {
        File dir = Vault.dir(ctx);
        File tmp = new File(dir, ".tmp-" + e.id);
        File dst = new File(dir, Vault.encName(e.id));
        boolean added = false;
        try {
            MessageDigest md = Vault.sha256();
            InputStream bounded = new BoundedInputStream(in, e.size);
            long n = Vault.encryptTo(ctx, bounded, tmp, VaultCrypto.aadVideo(e.id), md, new Vault.Progress() {
                @Override public void onProgress(long done, long t) {
                    if (progress != null) progress.onProgress(base + done, total, index, count);
                }

                @Override public boolean isCancelled() {
                    return progress != null && progress.isCancelled();
                }
            }, total, 0);
            String hash = Vault.hex(md.digest());
            if (n != e.size) throw new IOException("backup is incomplete");
            if (e.sha256 != null && !e.sha256.isEmpty() && !e.sha256.equals(hash)) throw new IOException("checksum mismatch");
            Object[] check = Vault.decryptAndHash(ctx, tmp, VaultCrypto.aadVideo(e.id));
            if ((Long) check[0] != n || !hash.equals(check[1])) throw new IOException("verification failed");
            if (!tmp.renameTo(dst)) throw new IOException("couldn't store it");
            e.enc = true;
            e.store = dst.getName();
            e.file = e.id + Vault.extensionOf(e.name, e.mime);
            e.sha256 = hash;
            if (thumb.length > 0) {
                try {
                    Vault.writeEncThumb(ctx, e, thumb);
                } catch (IOException ignored) {
                    // cosmetic
                }
            }
            Vault.addEntry(ctx, e);
            added = true;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (!added) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                //noinspection ResultOfMethodCallIgnored
                Vault.encThumbOf(ctx, e).delete();
            }
        }
    }

    private static void skipFully(InputStream in, long n, @Nullable Progress p) throws IOException {
        byte[] buf = new byte[256 * 1024];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("backup is incomplete");
            n -= r;
        }
    }

    // ------------------------------------------------------------------ crypto

    static byte[] header(int version, int iter, byte[] salt) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            DataOutputStream d = new DataOutputStream(b);
            d.write(MAGIC);
            d.writeInt(version);
            d.writeInt(iter);
            d.writeByte(salt.length);
            d.write(salt);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toByteArray();
    }

    private static StreamingAead aead(byte[] key) throws GeneralSecurityException {
        return new AesGcmHkdfStreaming(key, "HmacSha256", 32, 4096, 0);
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018). Implemented on {@link Mac} because the
     * "PBKDF2WithHmacSHA256" SecretKeyFactory only exists from Android 8 (minSdk is 7.0).
     */
    static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int dkLen) {
        try {
            if (password.length == 0) throw new IllegalArgumentException("empty password");
            java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password));
            byte[] pw = new byte[bb.remaining()];
            bb.get(pw);
            if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pw, "HmacSHA256"));
            Arrays.fill(pw, (byte) 0);
            int hLen = mac.getMacLength();
            int blocks = (dkLen + hLen - 1) / hLen;
            byte[] out = new byte[dkLen];
            byte[] u = new byte[hLen];
            byte[] t = new byte[hLen];
            for (int i = 1; i <= blocks; i++) {
                mac.update(salt);
                mac.update(new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
                mac.doFinal(u, 0);
                System.arraycopy(u, 0, t, 0, hLen);
                for (int c = 1; c < iterations; c++) {
                    mac.update(u);
                    mac.doFinal(u, 0);
                    for (int k = 0; k < hLen; k++) t[k] ^= u[k];
                }
                System.arraycopy(t, 0, out, (i - 1) * hLen, Math.min(hLen, dkLen - (i - 1) * hLen));
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ streams

    private static OutputStream nonClosing(OutputStream o) {
        return new FilterOutputStream(o) {
            @Override public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override public void close() throws IOException {
                out.flush(); // the caller closes the SAF stream
            }
        };
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** Exactly {@code limit} bytes of the underlying stream (EOF after that). */
    static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new EOFException("backup is incomplete");
            left -= n;
            return n;
        }

        @Override public void close() {
            // the outer stream stays open for the next entry
        }
    }
}
