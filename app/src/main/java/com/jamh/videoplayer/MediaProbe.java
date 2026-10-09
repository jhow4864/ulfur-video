package com.jamh.videoplayer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;

/**
 * Probes size / duration / bitrate of a local URI that is not in MediaStore (files shared or
 * opened from Photos, Files, Drive...). Used to size the player's buffers. Call off the main thread.
 */
final class MediaProbe {

    static final class Result {
        @Nullable String displayName;
        long sizeBytes = -1;
        long durationMs = -1;
        long bitrateBps = -1;
        int width, height;

        /** Container bitrate if known, else size / duration, else -1. */
        long effectiveBitrate() {
            if (bitrateBps > 0) return bitrateBps;
            if (sizeBytes > 0 && durationMs > 0) return sizeBytes * 8_000L / durationMs;
            return -1;
        }
    }

    private MediaProbe() {
    }

    @WorkerThread
    static Result probe(Context ctx, Uri uri) {
        Result r = new Result();
        String scheme = uri.getScheme();
        if ("file".equals(scheme) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            r.displayName = f.getName();
            if (f.isFile()) r.sizeBytes = f.length();
        } else if (Vault.isVaultUri(uri)) {
            // Private (encrypted) video: the index knows the real name and plaintext size.
            Vault.Entry e = Vault.findByUri(ctx, uri);
            if (e != null) {
                r.displayName = e.name;
                r.sizeBytes = e.size;
            }
        } else if ("content".equals(scheme)) {
            try (Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    if (!c.isNull(0)) r.displayName = c.getString(0);
                    if (!c.isNull(1)) r.sizeBytes = c.getLong(1);
                }
            } catch (RuntimeException ignored) {
                // Some providers reject projections; fall through to the descriptor.
            }
            if (r.sizeBytes <= 0) {
                try (AssetFileDescriptor afd = ctx.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    if (afd != null && afd.getLength() > 0) r.sizeBytes = afd.getLength();
                } catch (Exception ignored) {
                }
            }
        }
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        java.io.Closeable src = null;
        try {
            src = VaultMediaDataSource.setDataSource(ctx, mmr, uri);
            r.durationMs = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            r.bitrateBps = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            r.width = (int) parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            r.height = (int) parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        } catch (RuntimeException | java.io.IOException ignored) {
            // Unsupported container (FFmpeg-only formats) or unreadable: bitrate stays unknown.
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
            VaultMediaDataSource.closeQuietly(src);
        }
        return r;
    }

    private static long parse(@Nullable String s) {
        try {
            return s == null ? -1 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
