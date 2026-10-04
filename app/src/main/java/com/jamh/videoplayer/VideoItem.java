package com.jamh.videoplayer;

import android.net.Uri;

/** A single video row from MediaStore. */
public final class VideoItem {
    public final long id;
    public final Uri uri;
    public final String title;
    public final String displayName;
    public final long durationMs;
    public final long sizeBytes;
    public final long dateAddedSec;
    public final String bucketId;
    public final String bucketName;
    public final int width;
    public final int height;
    /** Rotation metadata in degrees (MediaStore ORIENTATION, API 29+), 0 if unknown. */
    public final int orientation;

    public static final long SHORT_CLIP_MS = 3 * 60_000L;

    public VideoItem(long id, Uri uri, String displayName, long durationMs, long sizeBytes,
                     long dateAddedSec, String bucketId, String bucketName, int width, int height) {
        this(id, uri, displayName, durationMs, sizeBytes, dateAddedSec, bucketId, bucketName, width, height, 0);
    }

    public VideoItem(long id, Uri uri, String displayName, long durationMs, long sizeBytes,
                     long dateAddedSec, String bucketId, String bucketName, int width, int height,
                     int orientation) {
        this.orientation = orientation;
        this.id = id;
        this.uri = uri;
        this.displayName = displayName == null ? "Video" : displayName;
        int dot = this.displayName.lastIndexOf('.');
        this.title = dot > 0 ? this.displayName.substring(0, dot) : this.displayName;
        this.durationMs = durationMs;
        this.sizeBytes = sizeBytes;
        this.dateAddedSec = dateAddedSec;
        this.bucketId = bucketId == null ? "" : bucketId;
        this.bucketName = bucketName == null || bucketName.isEmpty() ? "Internal storage" : bucketName;
        this.width = width;
        this.height = height;
    }

    /** True if the video is displayed taller than wide (rotation metadata taken into account). */
    public boolean isPortrait() {
        if (width <= 0 || height <= 0) return false;
        boolean rotated = orientation % 180 != 0;
        return rotated ? width > height : height > width;
    }

    public boolean isShort() {
        return durationMs > 0 && durationMs < SHORT_CLIP_MS;
    }

    /** Clips feed filter: 0 = portrait or short, 1 = portrait only, 2 = short only. */
    public boolean matchesClipFilter(int filter) {
        switch (filter) {
            case 1: return isPortrait();
            case 2: return isShort();
            default: return isPortrait() || isShort();
        }
    }

    /** Stable key used for resume positions. */
    public String resumeKey() {
        return "ms:" + id;
    }
}
