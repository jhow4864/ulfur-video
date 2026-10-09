package com.jamh.videoplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1.6: finds a subtitle file with the same name as the video next to it ("Film.mkv" →
 * "Film.srt" / "Film.vtt", then "Film.en.srt"-style variants). Works where the folder can be
 * read: file:// paths, and MediaStore videos whose folder is readable or whose subtitle file is
 * visible in MediaStore. Private videos and streams are skipped. Call off the main thread.
 */
final class SubtitleFinder {

    static final String[] EXTENSIONS = {".srt", ".vtt"};

    static final class Found {
        final Uri uri;
        final String name;

        Found(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private SubtitleFinder() {
    }

    static String baseName(@Nullable String fileName) {
        if (fileName == null) return "";
        int slash = fileName.lastIndexOf('/');
        String n = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /**
     * Pure: picks the best subtitle among {@code names} for a video called {@code videoName}.
     * Exact base name wins (.srt before .vtt), then "base.&lt;tag&gt;.srt/.vtt" (e.g. language), shortest tag first.
     */
    @Nullable
    static String pick(@Nullable String videoName, List<String> names) {
        String base = baseName(videoName).toLowerCase(Locale.ROOT);
        if (base.isEmpty() || names == null) return null;
        for (String ext : EXTENSIONS) {
            for (String n : names) {
                if (n != null && n.toLowerCase(Locale.ROOT).equals(base + ext)) return n;
            }
        }
        String best = null;
        for (String ext : EXTENSIONS) {
            for (String n : names) {
                if (n == null) continue;
                String l = n.toLowerCase(Locale.ROOT);
                if (l.startsWith(base + ".") && l.endsWith(ext) && l.length() > base.length() + ext.length() + 1) {
                    if (best == null || n.length() < best.length()) best = n;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    @WorkerThread
    @Nullable
    static Found find(Context ctx, @Nullable Uri video, @Nullable String displayName) {
        if (video == null || VideoKey.isNetwork(video) || Vault.isVaultUri(video)) return null;
        try {
            if ("file".equals(video.getScheme()) && video.getPath() != null) {
                File f = new File(video.getPath());
                return inDir(f.getParentFile(), displayName != null ? displayName : f.getName());
            }
            if ("content".equals(video.getScheme()) && MediaStore.AUTHORITY.equals(video.getAuthority())) {
                return fromMediaStore(ctx, video, displayName);
            }
        } catch (RuntimeException ignored) {
            // No access to the folder: nothing to auto-load (the picker still works).
        }
        return null;
    }

    @Nullable
    private static Found inDir(@Nullable File dir, String videoName) {
        if (dir == null || !dir.canRead()) return null;
        String[] list = dir.list();
        if (list == null) return null;
        List<String> names = new ArrayList<>();
        for (String n : list) names.add(n);
        String hit = pick(videoName, names);
        if (hit == null) return null;
        File f = new File(dir, hit);
        return f.canRead() ? new Found(Uri.fromFile(f), hit) : null;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static Found fromMediaStore(Context ctx, Uri video, @Nullable String displayName) {
        String data = null, rel = null, name = displayName;
        List<String> cols = new ArrayList<>();
        cols.add(MediaStore.MediaColumns.DATA);
        cols.add(MediaStore.MediaColumns.DISPLAY_NAME);
        if (Build.VERSION.SDK_INT >= 29) cols.add(MediaStore.MediaColumns.RELATIVE_PATH);
        try (Cursor c = ctx.getContentResolver().query(video, cols.toArray(new String[0]), null, null, null)) {
            if (c != null && c.moveToFirst()) {
                data = c.getString(0);
                if (name == null) name = c.getString(1);
                if (cols.size() > 2) rel = c.getString(2);
            }
        }
        if (name == null && data != null) name = new File(data).getName();
        if (name == null) return null;
        // 1) Readable folder (Android 9 and older, or legacy/all-files access).
        if (data != null) {
            Found f = inDir(new File(data).getParentFile(), name);
            if (f != null) return f;
        }
        // 2) Android 11+: subtitle files are indexed (MEDIA_TYPE_SUBTITLE); use the rows we can see.
        if (Build.VERSION.SDK_INT >= 29 && rel != null) {
            Uri files = MediaStore.Files.getContentUri("external");
            String base = baseName(name);
            List<String> names = new ArrayList<>();
            List<Long> ids = new ArrayList<>();
            try (Cursor c = ctx.getContentResolver().query(files,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? ESCAPE '\\'",
                    new String[]{rel, base.replace("%", "\\%").replace("_", "\\_") + "%"}, null)) {
                while (c != null && c.moveToNext()) {
                    ids.add(c.getLong(0));
                    names.add(c.getString(1));
                }
            }
            String hit = pick(name, names);
            if (hit != null) {
                long id = ids.get(names.indexOf(hit));
                return new Found(ContentUris.withAppendedId(files, id), hit);
            }
        }
        return null;
    }
}
