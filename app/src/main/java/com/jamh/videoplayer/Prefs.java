package com.jamh.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings and per-video resume positions. */
public final class Prefs {
    private static final String FILE = "settings";
    private static final String RESUME_FILE = "resume";

    private Prefs() {}

    private static SharedPreferences s(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static SharedPreferences r(Context c) {
        return c.getSharedPreferences(RESUME_FILE, Context.MODE_PRIVATE);
    }

    public static boolean isGrid(Context c) { return s(c).getBoolean("grid", true); }
    public static void setGrid(Context c, boolean v) { s(c).edit().putBoolean("grid", v).apply(); }
    public static int sort(Context c) { return s(c).getInt("sort", Sort.DATE); }
    public static void setSort(Context c, int v) { s(c).edit().putInt("sort", v).apply(); }
    public static boolean preferSoftwareDecoder(Context c) { return s(c).getBoolean("sw_decoder", false); }
    public static void setPreferSoftwareDecoder(Context c, boolean v) { s(c).edit().putBoolean("sw_decoder", v).apply(); }

    public static boolean dialogueBoost(Context c) { return s(c).getBoolean("dialogue_boost", false); }
    public static void setDialogueBoost(Context c, boolean v) { s(c).edit().putBoolean("dialogue_boost", v).apply(); }
    public static boolean backgroundAudio(Context c) { return s(c).getBoolean("background_audio", false); }
    public static void setBackgroundAudio(Context c, boolean v) { s(c).edit().putBoolean("background_audio", v).apply(); }
    /** 0 = portrait or short, 1 = portrait only, 2 = short (< 3 min) only. */
    /** Old behaviour: rotate the player to the video's orientation when it opens (default off). */
    public static boolean autoRotateToVideo(Context c) { return s(c).getBoolean("auto_rotate_video", false); }
    public static void setAutoRotateToVideo(Context c, boolean v) { s(c).edit().putBoolean("auto_rotate_video", v).apply(); }

    public static int clipsFilter(Context c) { return s(c).getInt("clips_filter", 0); }
    public static void setClipsFilter(Context c, int v) { s(c).edit().putInt("clips_filter", v).apply(); }

    /** 1.4.0: the Move to Private warning; false once the user ticks "Don't ask me again". */
    public static boolean warnBeforePrivate(Context c) { return s(c).getBoolean("warn_before_private", true); }
    public static void setWarnBeforePrivate(Context c, boolean v) { s(c).edit().putBoolean("warn_before_private", v).apply(); }

    /** 1.4.1: "Allow media management" prompt; true once the user ticks "Don't ask again". */
    public static boolean mediaManagePromptOff(Context c) { return s(c).getBoolean("media_manage_prompt_off", false); }
    public static void setMediaManagePromptOff(Context c, boolean v) { s(c).edit().putBoolean("media_manage_prompt_off", v).apply(); }

    public static boolean defaultBannerDismissed(Context c) { return s(c).getBoolean("default_banner_dismissed", false); }
    public static void setDefaultBannerDismissed(Context c) { s(c).edit().putBoolean("default_banner_dismissed", true).apply(); }

    /** Returns {positionMs, durationMs} or null. */
    public static long[] getResume(Context c, String key) {
        String v = r(c).getString(key, null);
        if (v == null) return null;
        try {
            String[] p = v.split(",");
            return new long[]{Long.parseLong(p[0]), p.length > 1 ? Long.parseLong(p[1]) : 0};
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void saveResume(Context c, String key, long posMs, long durMs) {
        if (key == null) return;
        boolean nearEnd = durMs > 0 && (durMs - posMs < 5000 || posMs > durMs * 0.97);
        if (posMs < 3000 || nearEnd) {
            r(c).edit().remove(key).apply();
        } else {
            r(c).edit().putString(key, posMs + "," + Math.max(durMs, 0)).apply();
        }
    }

    public static void clearResume(Context c, String key) {
        if (key != null) r(c).edit().remove(key).apply();
    }
}
