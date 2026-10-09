package com.jamh.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * 1.6: remembered playback speed per video (keyed by {@link VideoKey}, SharedPreferences
 * "speeds"), the global default speed, and the subtitle display settings. Kept out of
 * {@link Prefs} so this feature is self-contained.
 */
final class PlaybackMemory {

    private static final String SETTINGS = "settings";
    private static final String SPEEDS = "speeds";

    static final float MIN_SPEED = 0.25f, MAX_SPEED = 4f;
    /** Subtitle text sizes: Small, Medium (the 1.5.0 size), Large, Extra large. */
    static final String[] SUB_SIZE_LABELS = {"Small", "Medium", "Large", "Extra large"};
    static final float[] SUB_SIZE_SCALE = {0.8f, 1.0f, 1.3f, 1.65f};
    static final int SUB_SIZE_DEFAULT = 1;
    /** 1.5.0's fractional subtitle size (Media3 default 0.0533 × 1.1). */
    static final float SUB_BASE_FRACTION = 0.0533f * 1.1f;

    private PlaybackMemory() {
    }

    private static SharedPreferences s(Context c) {
        return c.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SPEEDS, Context.MODE_PRIVATE);
    }

    static float sanitizeSpeed(float v) {
        if (Float.isNaN(v) || v <= 0f) return 1f;
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, v));
    }

    // ------------------------------------------------------------------ speed

    /** Global default for videos without a remembered speed (1.0x unless changed). */
    static float defaultSpeed(Context c) {
        return sanitizeSpeed(s(c).getFloat("default_speed", 1f));
    }

    static void setDefaultSpeed(Context c, float v) {
        s(c).edit().putFloat("default_speed", sanitizeSpeed(v)).apply();
    }

    /** The speed remembered for this video, or null (then {@link #defaultSpeed} applies). */
    @Nullable
    static Float videoSpeed(Context c, @Nullable String videoKey) {
        if (videoKey == null || !sp(c).contains(videoKey)) return null;
        return sanitizeSpeed(sp(c).getFloat(videoKey, 1f));
    }

    /**
     * Remembers {@code speed} for this video. Choosing the default speed forgets the per-video
     * value, so a later change of the default applies to it again.
     */
    static void setVideoSpeed(Context c, @Nullable String videoKey, float speed) {
        if (videoKey == null) return;
        if (Math.abs(sanitizeSpeed(speed) - defaultSpeed(c)) < 0.001f) sp(c).edit().remove(videoKey).apply();
        else sp(c).edit().putFloat(videoKey, sanitizeSpeed(speed)).apply();
    }

    /** The speed a video should start at. */
    static float startSpeed(Context c, @Nullable String videoKey) {
        Float v = videoSpeed(c, videoKey);
        return v != null ? v : defaultSpeed(c);
    }

    // ------------------------------------------------------------------ subtitles

    static boolean subtitlesOn(Context c) {
        return s(c).getBoolean("subs_on", true);
    }

    static void setSubtitlesOn(Context c, boolean on) {
        s(c).edit().putBoolean("subs_on", on).apply();
    }

    static int subtitleSize(Context c) {
        int v = s(c).getInt("subs_size", SUB_SIZE_DEFAULT);
        return v < 0 || v >= SUB_SIZE_SCALE.length ? SUB_SIZE_DEFAULT : v;
    }

    static void setSubtitleSize(Context c, int index) {
        if (index < 0 || index >= SUB_SIZE_SCALE.length) index = SUB_SIZE_DEFAULT;
        s(c).edit().putInt("subs_size", index).apply();
    }

    static float subtitleFraction(int sizeIndex) {
        if (sizeIndex < 0 || sizeIndex >= SUB_SIZE_SCALE.length) sizeIndex = SUB_SIZE_DEFAULT;
        return SUB_BASE_FRACTION * SUB_SIZE_SCALE[sizeIndex];
    }

    /** Auto-load a same-name .srt/.vtt next to the video (default on). */
    static boolean autoLoadSubtitles(Context c) {
        return s(c).getBoolean("subs_auto_load", true);
    }

    static void setAutoLoadSubtitles(Context c, boolean on) {
        s(c).edit().putBoolean("subs_auto_load", on).apply();
    }
}
