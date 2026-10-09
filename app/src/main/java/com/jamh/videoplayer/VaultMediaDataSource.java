package com.jamh.videoplayer;

import android.content.Context;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * Random-access plaintext of a Private video for MediaMetadataRetriever (thumbnails, scrub
 * previews, clip/GIF export, probing). Backed by Tink's seekable decrypting channel: each
 * {@code readAt} decrypts only the 4 KB segments it touches.
 */
final class VaultMediaDataSource extends MediaDataSource {

    private final SeekableByteChannel channel;
    private final long size;
    private boolean closed;

    VaultMediaDataSource(Context ctx, Vault.Entry e) throws IOException {
        channel = Vault.openSeekable(ctx, e);
        size = channel.size();
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
        if (closed) throw new IOException("closed");
        if (position >= size) return -1;
        if (length == 0) return 0;
        channel.position(position);
        ByteBuffer bb = ByteBuffer.wrap(buffer, offset, (int) Math.min(length, size - position));
        int total = 0;
        while (bb.hasRemaining()) {
            int n = channel.read(bb);
            if (n < 0) break;
            total += n;
        }
        return total == 0 ? -1 : total;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * {@code mmr.setDataSource(ctx, uri)}, except that Private videos are decrypted on the fly.
     * Returns something to close after {@code mmr.release()} (never null).
     */
    static Closeable setDataSource(Context ctx, MediaMetadataRetriever mmr, Uri uri) throws IOException {
        if (Vault.isVaultUri(uri)) {
            Vault.Entry e = Vault.findByUri(ctx, uri);
            if (e == null) throw new IOException("This private video is gone");
            VaultMediaDataSource src = new VaultMediaDataSource(ctx, e);
            try {
                mmr.setDataSource(src);
            } catch (RuntimeException ex) {
                src.close();
                throw ex;
            }
            return src;
        }
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) mmr.setDataSource(uri.getPath());
        else mmr.setDataSource(ctx, uri);
        return () -> { };
    }

    static void closeQuietly(@Nullable Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
