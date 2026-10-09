package com.jamh.videoplayer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies an exported file into shared storage: Movies/VideoPlayer (MP4) or Pictures/VideoPlayer (GIF). */
final class MediaSaver {

    static final String FOLDER = "UlfurVideo"; // was "VideoPlayer" up to 1.3.0; existing files there are left alone

    private MediaSaver() {
    }

    /** Returns a content Uri suitable for ACTION_VIEW / ACTION_SEND. */
    static Uri save(Context ctx, File src, String displayName, String mime) throws IOException {
        return save(ctx, src, displayName, mime, true);
    }

    /**
     * @param allowAppFallback false for "move out of Private": the copy must land in shared
     *                         storage (app-specific storage is deleted on uninstall too).
     */
    static Uri save(Context ctx, File src, String displayName, String mime, boolean allowAppFallback) throws IOException {
        boolean video = mime.startsWith("video/");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = video
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = cr.insert(collection, v);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot open output");
                copy(in, out);
            } catch (IOException e) {
                cr.delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return uri;
        }
        // Android 9 and older: public directory (WRITE_EXTERNAL_STORAGE, requested by the editor),
        // falling back to app-specific storage when the permission was refused.
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), FOLDER);
        boolean publicOk = (dir.isDirectory() || dir.mkdirs()) && dir.canWrite();
        if (!publicOk && !allowAppFallback) {
            throw new IOException("Storage permission is needed to save to Movies/" + FOLDER);
        }
        if (!publicOk) {
            File base = ctx.getExternalFilesDir(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES);
            if (base == null) base = new File(ctx.getFilesDir(), video ? "Movies" : "Pictures");
            dir = base;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File dst = uniqueFile(dir, displayName);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            copy(in, out);
        }
        if (publicOk) {
            MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
        }
        try {
            return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", dst);
        } catch (IllegalArgumentException e) {
            return Uri.fromFile(dst);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    /** "name.mp4", then "name (1).mp4"... so an existing file is never overwritten. */
    static File uniqueFile(File dir, String displayName) {
        File f = new File(dir, displayName);
        if (!f.exists()) return f;
        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        String ext = dot > 0 ? displayName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + " " + System.currentTimeMillis() + ext);
    }
}
