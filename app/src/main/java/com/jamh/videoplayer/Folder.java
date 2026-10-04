package com.jamh.videoplayer;

import java.util.ArrayList;
import java.util.List;

/** A MediaStore bucket (folder) grouping videos. */
public final class Folder {
    public final String bucketId;
    public final String name;
    public final List<VideoItem> videos = new ArrayList<>();
    public long totalSize;
    public long latestDate;

    public Folder(String bucketId, String name) {
        this.bucketId = bucketId;
        this.name = name;
    }

    public VideoItem cover() {
        VideoItem best = null;
        for (VideoItem v : videos) if (best == null || v.dateAddedSec > best.dateAddedSec) best = v;
        return best;
    }
}
