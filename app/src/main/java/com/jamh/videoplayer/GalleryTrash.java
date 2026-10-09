package com.jamh.videoplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.List;

/** 1.6.0: videos in Android's Trash (Android 11+, MediaStore IS_TRASHED). */
final class GalleryTrash {

    static final class Item {
        final VideoItem video;
        /** When Android deletes it for good (epoch seconds), or 0 if unknown. */
        final long expiresSec;

        Item(VideoItem video, long expiresSec) {
            this.video = video;
            this.expiresSec = expiresSec;
        }
    }

    /** Tests (Robolectric has no MediaProvider trash) can supply the list. */
    @VisibleForTesting
    static List<Item> overrideForTest;

    private GalleryTrash() {
    }

    @WorkerThread
    static List<Item> query(Context ctx) {
        if (overrideForTest != null) return new ArrayList<>(overrideForTest);
        if (Build.VERSION.SDK_INT < 30) return new ArrayList<>();
        return query30(ctx);
    }

    @RequiresApi(30)
    private static List<Item> query30(Context ctx) {
        List<Item> out = new ArrayList<>();
        Uri base = VideoRepository.collection();
        String[] proj = {
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT, MediaStore.MediaColumns.DATE_EXPIRES};
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
        args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                MediaStore.MediaColumns.DATE_EXPIRES + " ASC");
        try (Cursor c = ctx.getContentResolver().query(base, proj, args, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                VideoItem v = new VideoItem(id, ContentUris.withAppendedId(base, id), c.getString(1), c.getLong(2),
                        c.getLong(3), c.getLong(4), c.getString(5), c.getString(6), c.getInt(7), c.getInt(8));
                out.add(new Item(v, c.isNull(9) ? 0 : c.getLong(9)));
            }
        } catch (RuntimeException ignored) {
            // permission revoked / provider error
        }
        return out;
    }

    /** "12 days left", "Deleted tomorrow", ... */
    static String timeLeft(long expiresMs, long now) {
        if (expiresMs <= 0) return "In trash";
        long days = (long) Math.ceil((expiresMs - now) / 86_400_000.0);
        if (days <= 0) return "Deleted today";
        if (days == 1) return "1 day left";
        return days + " days left";
    }
}
