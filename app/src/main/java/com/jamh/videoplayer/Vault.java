package com.jamh.videoplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Private videos: plain files in {@code filesDir/vault} (app-private storage, excluded from
 * backup and device transfer) plus a small JSON index. Not encrypted: the protection is that no
 * other app (and no gallery) can see them, and the UI sits behind BiometricPrompt. Uninstalling
 * or clearing the app's data deletes them, which the UI says clearly.
 */
final class Vault {

    static final String DIR = "vault";
    static final String AUTHORITY_SUFFIX = ".fileprovider";
    private static final String INDEX = "index.json";
    private static final String THUMBS = ".thumbs";
    private static final long SPACE_MARGIN = 50L * 1024 * 1024;

    interface Progress {
        void onProgress(long done, long total);

        boolean isCancelled();
    }

    static final class Entry {
        String id;
        String file;
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
                    .put("added", addedMs).put("sha256", sha256 == null ? "" : sha256);
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

    static File fileOf(Context ctx, Entry e) {
        return new File(dir(ctx), e.file);
    }

    static File thumbOf(Context ctx, Entry e) {
        return new File(new File(dir(ctx), THUMBS), e.id + ".jpg");
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
        return FileProvider.getUriForFile(ctx, ctx.getPackageName() + AUTHORITY_SUFFIX, fileOf(ctx, e));
    }

    // ------------------------------------------------------------------ index

    static synchronized List<Entry> list(Context ctx) {
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

    private static synchronized void save(Context ctx, List<Entry> entries) throws IOException {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : entries) a.put(e.toJson());
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File tmp = new File(dir(ctx), INDEX + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        File dst = new File(dir(ctx), INDEX);
        if (!tmp.renameTo(dst)) throw new IOException("Couldn't update the private index");
    }

    private static synchronized void add(Context ctx, Entry e) throws IOException {
        List<Entry> l = list(ctx);
        l.add(0, e);
        save(ctx, l);
    }

    @Nullable
    static Entry findByUri(Context ctx, Uri uri) {
        if (!isVaultUri(uri)) return null;
        String last = uri.getLastPathSegment();
        for (Entry e : list(ctx)) if (e.file.equals(last)) return e;
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

    // ------------------------------------------------------------------ import / export

    /**
     * Copies {@code src} into the vault and verifies the copy (byte count against the source size
     * and SHA-256 of the copy against the hash of what was read). Only a verified copy is kept;
     * the caller deletes the original afterwards.
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
        File dst = new File(dir, id + ext);
        String srcHash;
        long copied = 0;
        try {
            MessageDigest md = sha256();
            try (InputStream in = cr.openInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Can't read this video");
                byte[] buf = new byte[256 * 1024];
                int n;
                long lastReport = 0;
                while ((n = in.read(buf)) > 0) {
                    if (progress != null && progress.isCancelled()) throw new IOException("Cancelled");
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    copied += n;
                    if (progress != null && copied - lastReport > 1024 * 1024) {
                        lastReport = copied;
                        progress.onProgress(copied, size);
                    }
                }
                out.flush();
                if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
            }
            srcHash = hex(md.digest());
            if (size > 0 && copied != size) {
                throw new IOException("Copy check failed (" + copied + " of " + size + " bytes)");
            }
            if (!srcHash.equals(sha256Of(tmp))) throw new IOException("Copy check failed (checksum)");
            if (!tmp.renameTo(dst)) throw new IOException("Couldn't store the private copy");
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
        Entry e = new Entry();
        e.id = id;
        e.file = dst.getName();
        e.name = displayName == null || displayName.isEmpty() ? "Video" + ext : displayName;
        e.mime = mime == null || mime.isEmpty() ? "video/mp4" : mime;
        e.size = copied;
        e.addedMs = System.currentTimeMillis();
        e.sha256 = srcHash;
        readMetadataAndThumb(ctx, e, dst);
        try {
            add(ctx, e);
        } catch (IOException ex) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            throw ex;
        }
        return e;
    }

    /**
     * Copies a private video back to Movies/VideoPlayer, checks the size of the new copy and
     * only then removes it from the vault. Returns the new MediaStore/content Uri.
     */
    @WorkerThread
    static Uri moveOut(Context ctx, Entry e) throws IOException {
        File f = fileOf(ctx, e);
        Uri out = MediaSaver.save(ctx, f, e.name, e.mime, false);
        long len = sourceSize(ctx, out);
        if (len >= 0 && len != f.length()) {
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
        thumbOf(ctx, e).delete();
        List<Entry> l = list(ctx);
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

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static void readMetadataAndThumb(Context ctx, Entry e, File f) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
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
                File t = thumbOf(ctx, e);
                //noinspection ResultOfMethodCallIgnored
                t.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(t)) {
                    small.compress(Bitmap.CompressFormat.JPEG, 80, out);
                }
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
