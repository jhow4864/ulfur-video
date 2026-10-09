package com.jamh.videoplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 1.6 (bookmarks / remembered speed): a stable per-video key that does not depend on where the
 * file lives. It is a SHA-256 of the file size plus its first and last 64 KiB, so the same video
 * keeps its bookmarks and speed when it is moved into Private (copied into the vault and served
 * through FileProvider) or back out to Movies/UlfurVideo, renamed, or re-indexed by MediaStore.
 * Reads at most 128 KiB, so it is cheap even for multi-GB films. Call off the main thread.
 *
 * <p>Fallbacks: network streams use their URL ({@code url:...}); a local file that can't be read
 * uses size + duration + display name ({@code meta:...}) when those are known, else its URI.
 */
final class VideoKey {

    static final int CHUNK = 64 * 1024;
    static final String PREFIX_CONTENT = "v1:";

    private static final LruCache<String, String> CACHE = new LruCache<>(64);

    private VideoKey() {
    }

    /** Pure: the content key for a file of {@code size} bytes with the given head and tail chunks. */
    static String contentKey(long size, byte[] head, byte[] tail) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(ByteBuffer.allocate(8).putLong(size).array());
            md.update(head == null ? new byte[0] : head);
            md.update((byte) 0x7C);
            md.update(tail == null ? new byte[0] : tail);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(PREFIX_CONTENT);
            for (int i = 0; i < 16; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pure: metadata fallback (size + duration + display name, extension and case ignored). */
    @Nullable
    static String metaKey(long sizeBytes, long durationMs, @Nullable String displayName) {
        if (sizeBytes <= 0 || displayName == null || displayName.isEmpty()) return null;
        String n = displayName;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return "meta:" + sizeBytes + "|" + Math.max(0, durationMs / 1000) + "|" + n.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isNetwork(Uri uri) {
        String s = uri.getScheme();
        return "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s) || "rtsp".equalsIgnoreCase(s);
    }

    /** Worker thread: the stable key for {@code uri} (cached per URI string); null only for a null URI. */
    @WorkerThread
    @Nullable
    static String of(Context ctx, Uri uri, long sizeHint, long durationHint, @Nullable String nameHint) {
        if (uri == null) return null;
        if (isNetwork(uri)) return "url:" + uri;
        String cached = CACHE.get(uri.toString());
        if (cached != null) return cached;
        String key = null;
        try {
            key = readContentKey(ctx, uri);
        } catch (IOException | RuntimeException ignored) {
        }
        if (key == null) key = metaKey(sizeHint, durationHint, nameHint);
        // Last resort (unreadable and nothing known about it): at least stable for this URI.
        if (key == null) key = "uri:" + uri;
        CACHE.put(uri.toString(), key);
        return key;
    }

    @WorkerThread
    @Nullable
    static String readContentKey(Context ctx, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (!f.isFile()) return null;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                return fromChannel(raf.getChannel(), raf.length());
            }
        }
        ContentResolver cr = ctx.getContentResolver();
        // Seekable descriptor first (MediaStore, FileProvider/vault, most document providers).
        try (AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r")) {
            if (afd != null) {
                ParcelFileDescriptor pfd = afd.getParcelFileDescriptor();
                long start = afd.getStartOffset();
                long len = afd.getLength();
                try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                    FileChannel ch = in.getChannel();
                    if (len == AssetFileDescriptor.UNKNOWN_LENGTH || len <= 0) len = ch.size() - start;
                    if (len > 0) return fromChannel(ch, start, len);
                } catch (IOException ignored) {
                    // Pipe (not seekable): fall through to a plain stream.
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        // Not seekable: hash the head only; size unknown → combine with what we read.
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return null;
            byte[] head = readUpTo(in, CHUNK);
            if (head.length == 0) return null;
            return contentKey(-head.length, head, null);
        }
    }

    static String fromChannel(FileChannel ch, long size) throws IOException {
        return fromChannel(ch, 0, size);
    }

    static String fromChannel(FileChannel ch, long start, long size) throws IOException {
        byte[] head = readAt(ch, start, (int) Math.min(CHUNK, size));
        long tailLen = Math.min(CHUNK, Math.max(0, size - head.length));
        byte[] tail = tailLen > 0 ? readAt(ch, start + size - tailLen, (int) tailLen) : new byte[0];
        return contentKey(size, head, tail);
    }

    private static byte[] readAt(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos + buf.position());
            if (n < 0) break;
        }
        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        byte[] b = new byte[max];
        int off = 0, n;
        while (off < max && (n = in.read(b, off, max - off)) > 0) off += n;
        byte[] out = new byte[off];
        System.arraycopy(b, 0, out, 0, off);
        return out;
    }

    /** Tests only. */
    static void clearCache() {
        CACHE.evictAll();
    }
}
