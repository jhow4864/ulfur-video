package com.jamh.videoplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import com.google.crypto.tink.StreamingAead;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Private videos in {@code filesDir/vault} (app-private storage, excluded from backup and device
 * transfer) plus a small JSON index; the UI sits behind BiometricPrompt.
 *
 * <p>Since 1.6.0 every video and thumbnail is encrypted at rest with Tink StreamingAead
 * (AES256_GCM_HKDF_4KB, see {@link VaultCrypto}) and stored as {@code <id>.uvx}. Videos added
 * by older versions are encrypted in place by {@link VaultMigration}; until that has finished
 * for an entry it is still a plain file ({@code enc == false}). All reads go through
 * {@link #openSeekable}, {@link VaultDataSource} (player, Transformer) and
 * {@link VaultMediaDataSource} (MediaMetadataRetriever), which handle both forms.
 *
 * <p>Uris stay {@code content://<pkg>.fileprovider/vault/<file>} with the original logical file
 * name, so they don't change when an entry is encrypted. They are only interpreted by our own
 * data sources (the FileProvider no longer serves the vault directory).
 */
final class Vault {

    static final String DIR = "vault";
    static final String AUTHORITY_SUFFIX = ".fileprovider";
    private static final String INDEX = "index.json";
    private static final String THUMBS = ".thumbs";
    static final String ENC_EXT = ".uvx";
    private static final long SPACE_MARGIN = 50L * 1024 * 1024;

    interface Progress {
        void onProgress(long done, long total);

        boolean isCancelled();
    }

    static final class Entry {
        String id;
        /** Logical name used in the Uri; also the storage name while the entry is still plaintext. */
        String file;
        /** 1.6.0: true once the video is stored encrypted in {@link #store}. */
        boolean enc;
        /** Encrypted storage file name ({@code <id>.uvx}), when {@link #enc}. */
        String store;
        /** 1.6.0: moved to the (app-internal, still encrypted) trash at this time; 0 = not trashed. */
        long trashedAt;
        String name;
        String mime;
        long size;
        long durationMs;
        int width;
        int height;
        long addedMs;
        String sha256;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("file", file).put("name", name).put("mime", mime)
                    .put("size", size).put("duration", durationMs).put("w", width).put("h", height)
                    .put("added", addedMs).put("sha256", sha256 == null ? "" : sha256)
                    .put("enc", enc).put("store", store == null ? "" : store).put("trashed", trashedAt);
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optString("id");
            e.file = o.optString("file");
            e.name = o.optString("name", "Video");
            e.mime = o.optString("mime", "video/mp4");
            e.size = o.optLong("size");
            e.durationMs = o.optLong("duration");
            e.width = o.optInt("w");
            e.height = o.optInt("h");
            e.addedMs = o.optLong("added");
            e.sha256 = o.optString("sha256");
            e.enc = o.optBoolean("enc", false);
            e.store = o.optString("store", "");
            e.trashedAt = o.optLong("trashed", 0);
            return e;
        }
    }

    private Vault() {
    }

    static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.isDirectory()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    /** Where the entry's bytes are stored right now (encrypted {@code .uvx} or legacy plaintext). */
    static File fileOf(Context ctx, Entry e) {
        return new File(dir(ctx), e.enc ? e.store : e.file);
    }

    /** The pre-1.6.0 plaintext location (also the logical name). */
    static File plainFileOf(Context ctx, Entry e) {
        return new File(dir(ctx), e.file);
    }

    static String encName(String id) {
        return id + ENC_EXT;
    }

    /** Legacy plaintext JPEG thumbnail (pre-1.6.0; removed by the migration). */
    static File thumbOf(Context ctx, Entry e) {
        return new File(new File(dir(ctx), THUMBS), e.id + ".jpg");
    }

    /** Encrypted thumbnail. */
    static File encThumbOf(Context ctx, Entry e) {
        return new File(new File(dir(ctx), THUMBS), e.id + ".jpg" + ENC_EXT);
    }

    /** content://com.jamh.videoplayer.fileprovider/vault/... (our provider is not exported). */
    static boolean isVaultUri(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return false;
        String auth = uri.getAuthority();
        List<String> seg = uri.getPathSegments();
        return auth != null && auth.endsWith(AUTHORITY_SUFFIX) && auth.startsWith("com.jamh.videoplayer")
                && seg.size() >= 2 && DIR.equals(seg.get(0));
    }

    static Uri uriFor(Context ctx, Entry e) {
        return new Uri.Builder().scheme("content").authority(ctx.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(DIR).appendPath(e.file).build();
    }

    // ------------------------------------------------------------------ index

    /** All entries whose stored file exists (entries in the trash included; see {@link #list}). */
    static synchronized List<Entry> listAll(Context ctx) {
        List<Entry> out = new ArrayList<>();
        File f = new File(dir(ctx), INDEX);
        if (!f.isFile()) return out;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONArray a = new JSONArray(new String(b, 0, off, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                Entry e = Entry.fromJson(a.getJSONObject(i));
                if (fileOf(ctx, e).isFile()) out.add(e); // ignore entries whose file is gone
            }
        } catch (IOException | JSONException ignored) {
        }
        return out;
    }

    /** Entries shown in Private videos (not in the trash). */
    static synchronized List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : listAll(ctx)) if (e.trashedAt <= 0) out.add(e);
        return out;
    }

    // ------------------------------------------------------------------ trash (1.6.0)

    static final long TRASH_DAYS = 30;
    static final long TRASH_MS = TRASH_DAYS * 24L * 60 * 60 * 1000;

    /** Entries in the private trash, most recently trashed first. */
    static synchronized List<Entry> listTrashed(Context ctx) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : listAll(ctx)) if (e.trashedAt > 0) out.add(e);
        out.sort((a, b) -> Long.compare(b.trashedAt, a.trashedAt));
        return out;
    }

    /** Moves entries to the trash (files stay where they are, encrypted). Returns how many. */
    static synchronized int trash(Context ctx, List<Entry> entries, long now) throws IOException {
        return setTrashed(ctx, entries, now);
    }

    static synchronized int restore(Context ctx, List<Entry> entries) throws IOException {
        return setTrashed(ctx, entries, 0);
    }

    private static int setTrashed(Context ctx, List<Entry> entries, long value) throws IOException {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Entry e : entries) ids.add(e.id);
        List<Entry> l = readIndex(ctx);
        int n = 0;
        for (Entry e : l) {
            if (ids.contains(e.id)) {
                e.trashedAt = value;
                n++;
            }
        }
        if (n > 0) save(ctx, l);
        for (Entry e : entries) e.trashedAt = value;
        return n;
    }

    /** Permanently deletes trashed entries older than 30 days. Returns how many. */
    static synchronized int purgeExpired(Context ctx, long now) {
        int n = 0;
        for (Entry e : listTrashed(ctx)) {
            if (now - e.trashedAt >= TRASH_MS) {
                delete(ctx, e);
                n++;
            }
        }
        return n;
    }

    static long expiresAt(Entry e) {
        return e.trashedAt + TRASH_MS;
    }

    /** Raw index, including entries whose file is missing (used by the migration). */
    static synchronized List<Entry> readIndex(Context ctx) {
        List<Entry> out = new ArrayList<>();
        File f = new File(dir(ctx), INDEX);
        if (!f.isFile()) return out;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONArray a = new JSONArray(new String(b, 0, off, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) out.add(Entry.fromJson(a.getJSONObject(i)));
        } catch (IOException | JSONException ignored) {
        }
        return out;
    }

    /**
     * Atomically switches an entry to its verified encrypted copy (keeps every other field as it
     * is in the index right now). Throws if the entry was deleted meanwhile.
     */
    static synchronized Entry markEncrypted(Context ctx, String id, String store, String sha256) throws IOException {
        List<Entry> l = readIndex(ctx);
        for (Entry e : l) {
            if (e.id.equals(id)) {
                e.enc = true;
                e.store = store;
                if (e.sha256 == null || e.sha256.isEmpty()) e.sha256 = sha256;
                save(ctx, l);
                return e;
            }
        }
        throw new IOException("It was removed from Private meanwhile");
    }

    /** Replaces one entry (matched by id) in the index, atomically. */
    static synchronized void update(Context ctx, Entry e) throws IOException {
        List<Entry> l = readIndex(ctx);
        boolean found = false;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(e.id)) {
                l.set(i, e);
                found = true;
            }
        }
        if (!found) throw new IOException("Entry " + e.id + " is no longer in the index");
        save(ctx, l);
    }

    static synchronized void save(Context ctx, List<Entry> entries) throws IOException {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : entries) a.put(e.toJson());
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File tmp = new File(dir(ctx), INDEX + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync(); // the index must be on disk before a plaintext file is deleted
        }
        File dst = new File(dir(ctx), INDEX);
        if (!tmp.renameTo(dst)) throw new IOException("Couldn't update the private index");
    }

    private static synchronized void add(Context ctx, Entry e) throws IOException {
        List<Entry> l = readIndex(ctx);
        l.add(0, e);
        save(ctx, l);
    }

    @Nullable
    static Entry findByUri(Context ctx, Uri uri) {
        if (!isVaultUri(uri)) return null;
        String last = uri.getLastPathSegment();
        for (Entry e : listAll(ctx)) if (e.file.equals(last)) return e;
        return null;
    }

    static long usedBytes(Context ctx) {
        long total = 0;
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) if (f.isFile()) total += f.length();
        File[] thumbs = new File(dir(ctx), THUMBS).listFiles();
        if (thumbs != null) for (File f : thumbs) total += f.length();
        return total;
    }

    // ------------------------------------------------------------------ encrypted reads

    /**
     * Random-access plaintext view of an entry: a Tink seekable decrypting channel over the
     * {@code .uvx} file, or the plain file channel for a not-yet-migrated entry. Caller closes.
     */
    static SeekableByteChannel openSeekable(Context ctx, Entry e) throws IOException {
        FileInputStream in = new FileInputStream(fileOf(ctx, e));
        FileChannel fc = in.getChannel();
        if (!e.enc) return fc;
        try {
            SeekableByteChannel ch = VaultCrypto.get(ctx).newSeekableDecryptingChannel(fc, VaultCrypto.aadVideo(e.id));
            // Tink only knows the plaintext size once it has read (and authenticated) the header.
            ch.read(java.nio.ByteBuffer.allocate(1));
            ch.position(0);
            return ch;
        } catch (GeneralSecurityException | IOException | RuntimeException ex) {
            in.close();
            throw ex instanceof IOException ? (IOException) ex : new IOException("Can't decrypt this video", ex);
        }
    }

    /** Sequential plaintext stream of an entry (move-out, backup). Caller closes. */
    static InputStream openStream(Context ctx, Entry e) throws IOException {
        InputStream in = new FileInputStream(fileOf(ctx, e));
        if (!e.enc) return in;
        try {
            return VaultCrypto.get(ctx).newDecryptingStream(in, VaultCrypto.aadVideo(e.id));
        } catch (GeneralSecurityException | RuntimeException ex) {
            in.close();
            throw new IOException("Can't decrypt this video", ex);
        }
    }

    /**
     * Encrypts {@code in} into {@code out} (fsynced), hashing the plaintext. Returns
     * {plaintext bytes} and fills {@code md}. Does not close {@code in}.
     */
    static long encryptTo(Context ctx, InputStream in, File out, byte[] aad, MessageDigest md,
                          @Nullable Progress progress, long total, long base) throws IOException {
        StreamingAead sa = VaultCrypto.get(ctx);
        long copied = 0;
        try (OutputStream os = sa.newEncryptingStream(VaultCrypto.syncing(new FileOutputStream(out)), aad)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                if (progress != null && progress.isCancelled()) throw new IOException("Cancelled");
                os.write(buf, 0, n);
                md.update(buf, 0, n);
                copied += n;
                if (progress != null && copied - lastReport > 1024 * 1024) {
                    lastReport = copied;
                    progress.onProgress(base + copied, total);
                }
            }
        } catch (GeneralSecurityException ex) {
            throw new IOException("Encryption failed", ex);
        }
        return copied;
    }

    /** Decrypts a whole {@code .uvx} file; returns {size, sha256-hex}. Throws if it fails to authenticate. */
    static Object[] decryptAndHash(Context ctx, File f, byte[] aad) throws IOException {
        MessageDigest md = sha256();
        long size = 0;
        try (InputStream in = VaultCrypto.get(ctx).newDecryptingStream(new FileInputStream(f), aad)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                size += n;
            }
        } catch (GeneralSecurityException ex) {
            throw new IOException("Decryption check failed", ex);
        }
        return new Object[]{size, hex(md.digest())};
    }

    /** The entry's thumbnail (decrypted in memory), or null. */
    @Nullable
    static Bitmap loadThumb(Context ctx, Entry e) {
        File enc = encThumbOf(ctx, e);
        if (enc.isFile()) {
            try (InputStream in = VaultCrypto.get(ctx).newDecryptingStream(new FileInputStream(enc),
                    VaultCrypto.aadThumb(e.id))) {
                return BitmapFactory.decodeStream(in);
            } catch (IOException | GeneralSecurityException | RuntimeException ignored) {
                return null;
            }
        }
        File plain = thumbOf(ctx, e);
        return plain.isFile() ? BitmapFactory.decodeFile(plain.getAbsolutePath()) : null;
    }

    /** Writes an encrypted JPEG thumbnail (via a temp file + rename). */
    static void writeEncThumb(Context ctx, Entry e, byte[] jpeg) throws IOException {
        File t = encThumbOf(ctx, e);
        //noinspection ResultOfMethodCallIgnored
        t.getParentFile().mkdirs();
        File tmp = new File(t.getParentFile(), ".tmp-" + t.getName());
        try (OutputStream os = VaultCrypto.get(ctx).newEncryptingStream(
                VaultCrypto.syncing(new FileOutputStream(tmp)), VaultCrypto.aadThumb(e.id))) {
            os.write(jpeg);
        } catch (GeneralSecurityException ex) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException(ex);
        }
        if (!tmp.renameTo(t)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Couldn't store the thumbnail");
        }
    }

    // ------------------------------------------------------------------ import / export

    /**
     * Encrypts {@code src} into the vault while copying it, then verifies the stored copy by
     * decrypting it again (byte count against the source size and SHA-256 against the hash of
     * what was read). Only a verified copy is kept; the caller deletes the original afterwards.
     */
    @WorkerThread
    static Entry importFrom(Context ctx, Uri src, String displayName, @Nullable String mime,
                            long expectedSize, @Nullable Progress progress) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        long size = sourceSize(ctx, src);          // real file length when the provider knows it
        if (size <= 0) size = expectedSize;
        File dir = dir(ctx);
        if (size > 0 && dir.getUsableSpace() < size + SPACE_MARGIN) {
            throw new IOException("Not enough free space for this video");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String ext = extensionOf(displayName, mime);
        File tmp = new File(dir, ".tmp-" + id);
        File dst = new File(dir, encName(id));
        String srcHash;
        long copied;
        Entry e = new Entry();
        e.id = id;
        try {
            MessageDigest md = sha256();
            try (InputStream in = cr.openInputStream(src)) {
                if (in == null) throw new IOException("Can't read this video");
                copied = encryptTo(ctx, in, tmp, VaultCrypto.aadVideo(id), md, progress, size, 0);
            }
            srcHash = hex(md.digest());
            if (size > 0 && copied != size) {
                throw new IOException("Copy check failed (" + copied + " of " + size + " bytes)");
            }
            Object[] check = decryptAndHash(ctx, tmp, VaultCrypto.aadVideo(id));
            if ((Long) check[0] != copied || !srcHash.equals(check[1])) {
                throw new IOException("Copy check failed (checksum)");
            }
            if (!tmp.renameTo(dst)) throw new IOException("Couldn't store the private copy");
        } catch (IOException | RuntimeException ex) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            throw ex instanceof IOException ? (IOException) ex : new IOException(ex.getMessage(), ex);
        }
        e.file = id + ext;
        e.enc = true;
        e.store = dst.getName();
        e.name = displayName == null || displayName.isEmpty() ? "Video" + ext : displayName;
        e.mime = mime == null || mime.isEmpty() ? "video/mp4" : mime;
        e.size = copied;
        e.addedMs = System.currentTimeMillis();
        e.sha256 = srcHash;
        readMetadataAndThumb(ctx, e);
        try {
            add(ctx, e);
        } catch (IOException ex) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            //noinspection ResultOfMethodCallIgnored
            encThumbOf(ctx, e).delete();
            throw ex;
        }
        return e;
    }

    /**
     * Decrypts a private video into Movies/UlfurVideo, reads the new copy back and compares its
     * size and SHA-256 with what was decrypted, and only then removes it from the vault.
     * Returns the new MediaStore/content Uri.
     */
    @WorkerThread
    static Uri moveOut(Context ctx, Entry e) throws IOException {
        MessageDigest md = sha256();
        long[] count = {0};
        Uri out = MediaSaver.save(ctx, () -> new java.io.FilterInputStream(openStream(ctx, e)) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    md.update(b, off, n);
                    count[0] += n;
                }
                return n;
            }
        }, e.name, e.mime, false);
        String wrote = hex(md.digest());
        boolean ok = count[0] == e.size || e.size <= 0;
        if (ok) {
            try (InputStream back = "file".equals(out.getScheme()) && out.getPath() != null
                    ? new FileInputStream(out.getPath()) : ctx.getContentResolver().openInputStream(out)) {
                if (back != null) {
                    MessageDigest md2 = sha256();
                    long n2 = 0;
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = back.read(buf)) > 0) {
                        md2.update(buf, 0, n);
                        n2 += n;
                    }
                    ok = n2 == count[0] && wrote.equals(hex(md2.digest()));
                }
            } catch (IOException | RuntimeException ex) {
                ok = false;
            }
        }
        if (ok && e.sha256 != null && !e.sha256.isEmpty() && !e.sha256.equals(wrote)) ok = false;
        if (!ok) {
            try {
                ctx.getContentResolver().delete(out, null, null);
            } catch (RuntimeException ignored) {
            }
            throw new IOException("Copy check failed; the video is still in Private");
        }
        delete(ctx, e);
        return out;
    }

    static synchronized void delete(Context ctx, Entry e) {
        //noinspection ResultOfMethodCallIgnored
        fileOf(ctx, e).delete();
        //noinspection ResultOfMethodCallIgnored
        plainFileOf(ctx, e).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir(ctx), encName(e.id)).delete(); // also when the migration switched it meanwhile
        //noinspection ResultOfMethodCallIgnored
        thumbOf(ctx, e).delete();
        //noinspection ResultOfMethodCallIgnored
        encThumbOf(ctx, e).delete();
        PrivateResume.clear(ctx, e.file);
        List<Entry> l = readIndex(ctx);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(e.id)) l.remove(i);
        try {
            save(ctx, l);
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Actual byte length: file length / descriptor length first, the provider's SIZE column last. */
    static long sourceSize(Context ctx, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath()).length();
        try (AssetFileDescriptor afd = ctx.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null && afd.getLength() >= 0) return afd.getLength();
        } catch (IOException | RuntimeException ignored) {
        }
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                long s = c.getLong(0);
                if (s > 0) return s;
            }
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    static String extensionOf(@Nullable String name, @Nullable String mime) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 6) return name.substring(dot).toLowerCase(Locale.ROOT);
        }
        if (mime != null) {
            if (mime.contains("matroska")) return ".mkv";
            if (mime.contains("webm")) return ".webm";
            if (mime.contains("3gpp")) return ".3gp";
            if (mime.contains("quicktime")) return ".mov";
        }
        return ".mp4";
    }

    static String sha256Of(File f) throws IOException {
        MessageDigest md = sha256();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /** Duration/size from the stored (encrypted) copy plus an encrypted thumbnail. Cosmetic. */
    static void readMetadataAndThumb(Context ctx, Entry e) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        VaultMediaDataSource src = null;
        try {
            src = new VaultMediaDataSource(ctx, e);
            mmr.setDataSource(src);
            e.durationMs = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            e.width = (int) parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            e.height = (int) parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = (int) parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rot == 90 || rot == 270) {
                int t = e.width;
                e.width = e.height;
                e.height = t;
            }
            Bitmap bm = mmr.getFrameAtTime(Math.max(0, e.durationMs * 100L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bm != null) {
                float s = Math.min(1f, 360f / Math.max(bm.getWidth(), bm.getHeight()));
                Bitmap small = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * s)),
                        Math.max(1, Math.round(bm.getHeight() * s)), true);
                java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
                writeEncThumb(ctx, e, jpeg.toByteArray());
                if (small != bm) small.recycle();
                bm.recycle();
            }
        } catch (Exception ignored) {
            // metadata/thumbnail are cosmetic
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
            if (src != null) src.close();
        }
    }

    private static long parse(@Nullable String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
