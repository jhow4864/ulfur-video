package com.jamh.videoplayer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Queries the MediaStore for videos. Call off the main thread. */
public final class VideoRepository {
    private VideoRepository() {}

    public static Uri collection() {
        if (Build.VERSION.SDK_INT >= 29) {
            return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
        return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    private static final String[] PROJECTION = Build.VERSION.SDK_INT >= 29 ? withOrientation() : baseProjection();

    private static String[] withOrientation() {
        String[] base = baseProjection();
        String[] out = java.util.Arrays.copyOf(base, base.length + 1);
        out[base.length] = MediaStore.MediaColumns.ORIENTATION;
        return out;
    }

    private static String[] baseProjection() {
        return new String[]{
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        };
    }

    public static List<VideoItem> query(Context context, String selection, String[] args) {
        List<VideoItem> out = new ArrayList<>();
        ContentResolver cr = context.getContentResolver();
        Uri base = collection();
        try (Cursor c = cr.query(base, PROJECTION, selection, args, null)) {
            if (c == null) return out;
            int iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int iBucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
            int iBucketName = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            int iW = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
            int iH = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
            int iO = Build.VERSION.SDK_INT >= 29 ? c.getColumnIndex(MediaStore.MediaColumns.ORIENTATION) : -1;
            while (c.moveToNext()) {
                long id = c.getLong(iId);
                out.add(new VideoItem(id, ContentUris.withAppendedId(base, id), c.getString(iName),
                        c.getLong(iDur), c.getLong(iSize), c.getLong(iDate), c.getString(iBucket),
                        c.getString(iBucketName), c.getInt(iW), c.getInt(iH),
                        iO >= 0 && !c.isNull(iO) ? c.getInt(iO) : 0));
            }
        } catch (RuntimeException e) {
            // Permission revoked or provider error: return what we have.
        }
        return out;
    }

    public static List<VideoItem> all(Context context) {
        return query(context, null, null);
    }

    public static List<VideoItem> inBucket(Context context, String bucketId) {
        return query(context, MediaStore.Video.Media.BUCKET_ID + "=?", new String[]{bucketId});
    }

    public static List<Folder> folders(List<VideoItem> videos) {
        Map<String, Folder> map = new LinkedHashMap<>();
        for (VideoItem v : videos) {
            Folder f = map.get(v.bucketId);
            if (f == null) {
                f = new Folder(v.bucketId, v.bucketName);
                map.put(v.bucketId, f);
            }
            f.videos.add(v);
            f.totalSize += v.sizeBytes;
            f.latestDate = Math.max(f.latestDate, v.dateAddedSec);
        }
        return new ArrayList<>(map.values());
    }

    /** Tries to map an arbitrary content:// or file:// URI back to a MediaStore row. */
    @SuppressWarnings("deprecation")
    public static VideoItem resolve(Context context, Uri uri) {
        if (uri == null) return null;
        try {
            String path = uri.getPath() == null ? "" : uri.getPath();
            if ("content".equals(uri.getScheme()) && MediaStore.AUTHORITY.equals(uri.getAuthority())
                    && !path.contains("picker") && (path.contains("/video/") || path.contains("/file/"))) {
                long id = Long.parseLong(uri.getLastPathSegment());
                List<VideoItem> l = query(context, MediaStore.Video.Media._ID + "=?", new String[]{String.valueOf(id)});
                return l.isEmpty() ? null : l.get(0);
            }
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                List<VideoItem> l = query(context, MediaStore.Video.Media.DATA + "=?", new String[]{uri.getPath()});
                return l.isEmpty() ? null : l.get(0);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** Best-effort display name for a URI (content providers, file paths, URLs). */
    public static String displayName(Context context, Uri uri) {
        if (uri == null) return "Video";
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null && !n.isEmpty()) return n;
                }
            } catch (RuntimeException ignored) {
            }
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? uri.toString() : last;
    }
}
