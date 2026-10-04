package com.jamh.videoplayer;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public final class Fmt {
    private Fmt() {}

    public static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.UK, "%d:%02d:%02d", h, m, sec)
                     : String.format(Locale.UK, "%d:%02d", m, sec);
    }

    public static String signedTime(long deltaMs) {
        return (deltaMs < 0 ? "-" : "+") + time(Math.abs(deltaMs));
    }

    /** m:ss.t (tenths) for fine scrubbing / editor. */
    public static String timePrecise(long ms) {
        if (ms < 0) ms = 0;
        return time(ms) + "." + ((ms % 1000) / 100);
    }

    public static String signedTimePrecise(long deltaMs) {
        return (deltaMs < 0 ? "-" : "+") + timePrecise(Math.abs(deltaMs));
    }

    /** m:ss.mmm for the clip editor. */
    public static String timeMillis(long ms) {
        if (ms < 0) ms = 0;
        return time(ms) + String.format(Locale.UK, ".%03d", ms % 1000);
    }

    public static String size(long bytes) {
        if (bytes <= 0) return "";
        double kb = bytes / 1024.0, mb = kb / 1024.0, gb = mb / 1024.0;
        if (gb >= 1) return String.format(Locale.UK, "%.2f GB", gb);
        if (mb >= 1) return String.format(Locale.UK, "%.1f MB", mb);
        return String.format(Locale.UK, "%.0f KB", kb);
    }

    public static String date(long epochSec) {
        if (epochSec <= 0) return "";
        return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.UK).format(new Date(epochSec * 1000));
    }
}
