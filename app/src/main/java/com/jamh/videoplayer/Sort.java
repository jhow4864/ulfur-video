package com.jamh.videoplayer;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class Sort {
    public static final int DATE = 0, NAME = 1, SIZE = 2, DURATION = 3;

    private Sort() {}

    public static void videos(List<VideoItem> list, int mode) {
        Comparator<VideoItem> byName = (a, b) -> a.title.compareToIgnoreCase(b.title);
        Comparator<VideoItem> c;
        switch (mode) {
            case NAME: c = byName; break;
            case SIZE: c = (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes); break;
            case DURATION: c = (a, b) -> Long.compare(b.durationMs, a.durationMs); break;
            default: c = (a, b) -> Long.compare(b.dateAddedSec, a.dateAddedSec); break;
        }
        Collections.sort(list, c.thenComparing(byName));
    }

    public static void folders(List<Folder> list, int mode) {
        Comparator<Folder> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
        Comparator<Folder> c;
        switch (mode) {
            case NAME: c = byName; break;
            case SIZE: c = (a, b) -> Long.compare(b.totalSize, a.totalSize); break;
            case DURATION: c = (a, b) -> Integer.compare(b.videos.size(), a.videos.size()); break;
            default: c = (a, b) -> Long.compare(b.latestDate, a.latestDate); break;
        }
        Collections.sort(list, c.thenComparing(byName));
    }
}
