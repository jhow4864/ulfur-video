package com.jamh.videoplayer;

import java.util.Locale;

/**
 * 1.5.0 real-time upscaling: modes, the per-frame plan and the frame-drop watchdog.
 *
 * <p>Pure Java (no GL) so it can be unit tested. The GPU work lives in {@link UpscaleEffect} /
 * {@link UpscaleShaderProgram}; shader sources are in {@code assets/shaders/ulfur_*.glsl}.
 */
final class Upscaler {

    // ---------------------------------------------------------------- modes (persisted)
    static final int MODE_OFF = 0;
    static final int MODE_BALANCED = 1;
    static final int MODE_SHARP = 2;

    // ---------------------------------------------------------------- upscaler quality
    /** FSR 1.0 EASU (12 taps, edge-adaptive) followed by RCAS. */
    static final int QUALITY_FSR = 0;
    /** Catmull-Rom bicubic (9 bilinear taps) followed by RCAS: the cheaper fallback. */
    static final int QUALITY_LITE = 1;

    // ---------------------------------------------------------------- plan kinds
    static final int PLAN_COPY = 0;     // pass-through
    static final int PLAN_SHARPEN = 1;  // RCAS only, at the video's own size
    static final int PLAN_UPSCALE = 2;  // EASU (or Catmull-Rom) to the screen size, then RCAS

    /**
     * Output budget: ~1080p worth of pixels (1920 x 1088). Above that the system compositor does the
     * last (bilinear) stretch, which keeps mid-range GPUs comfortable on 1440p screens.
     */
    static final int MAX_OUTPUT_PIXELS = 1920 * 1088;
    /** EASU is designed for up to ~2x per axis; beyond 4x it only costs time. */
    static final double MAX_SCALE = 4.0;
    /** Below this the upscale isn't worth a pass (the compositor's own scaling is fine). */
    static final double MIN_SCALE = 1.10;

    /** RCAS strength in FSR "stops" (0 = strongest; each stop halves it). */
    static final float BALANCED_STOPS = 0.8f;
    static final float SHARP_STOPS = 0.25f;
    /** Sharp mode on a video that already fills the screen: lighter sharpening only. */
    static final float SHARPEN_ONLY_STOPS = 0.9f;

    private Upscaler() {}

    static int sanitizeMode(int mode) {
        return mode == MODE_BALANCED || mode == MODE_SHARP ? mode : MODE_OFF;
    }

    /** Off → Balanced → Sharp → Off (the top-bar button). */
    static int nextMode(int mode) {
        switch (sanitizeMode(mode)) {
            case MODE_OFF: return MODE_BALANCED;
            case MODE_BALANCED: return MODE_SHARP;
            default: return MODE_OFF;
        }
    }

    static String modeLabel(int mode) {
        switch (sanitizeMode(mode)) {
            case MODE_BALANCED: return "Balanced";
            case MODE_SHARP: return "Sharp";
            default: return "Off";
        }
    }

    static String qualityLabel(int quality) {
        return quality == QUALITY_LITE ? "Lite (bicubic + RCAS)" : "FSR 1.0 (EASU + RCAS)";
    }

    /** What the shader does for one frame size. */
    static final class Plan {
        final int kind;
        final int outWidth;
        final int outHeight;
        /** RCAS sharpness as a linear factor: exp2(-stops). 0 when no sharpening pass runs. */
        final float sharpness;

        Plan(int kind, int outWidth, int outHeight, float sharpness) {
            this.kind = kind;
            this.outWidth = outWidth;
            this.outHeight = outHeight;
            this.sharpness = sharpness;
        }

        String describe(int inWidth, int inHeight) {
            switch (kind) {
                case PLAN_UPSCALE:
                    return String.format(Locale.UK, "%d×%d → %d×%d", inWidth, inHeight, outWidth, outHeight);
                case PLAN_SHARPEN:
                    return String.format(Locale.UK, "%d×%d, sharpen only (video already fills the screen)", inWidth, inHeight);
                default:
                    return String.format(Locale.UK, "%d×%d, unchanged (video already fills the screen)", inWidth, inHeight);
            }
        }
    }

    static float sharpnessForStops(float stops) {
        return (float) Math.pow(2.0, -stops);
    }

    /**
     * Decides how to process a frame of {@code inW x inH} shown in a {@code boxW x boxH} surface.
     * Upscales only when the video is smaller than the area it is drawn into; otherwise Sharp mode
     * sharpens at native size (if within budget) and Balanced passes the frame through untouched.
     */
    static Plan plan(int inW, int inH, int boxW, int boxH, int mode) {
        mode = sanitizeMode(mode);
        if (mode == MODE_OFF || inW <= 0 || inH <= 0) return new Plan(PLAN_COPY, Math.max(inW, 1), Math.max(inH, 1), 0f);
        float stops = mode == MODE_SHARP ? SHARP_STOPS : BALANCED_STOPS;
        double scale = boxW > 0 && boxH > 0 ? Math.min(boxW / (double) inW, boxH / (double) inH) : 1.0;
        double budget = Math.sqrt(MAX_OUTPUT_PIXELS / ((double) inW * inH));
        double s = Math.min(Math.min(scale, budget), MAX_SCALE);
        if (s >= MIN_SCALE) {
            int ow = Math.max(2, (int) Math.round(inW * s / 2.0) * 2);
            int oh = Math.max(2, (int) Math.round(inH * s / 2.0) * 2);
            return new Plan(PLAN_UPSCALE, ow, oh, sharpnessForStops(stops));
        }
        if (mode == MODE_SHARP && (long) inW * inH <= MAX_OUTPUT_PIXELS) {
            return new Plan(PLAN_SHARPEN, inW, inH, sharpnessForStops(SHARPEN_ONLY_STOPS));
        }
        return new Plan(PLAN_COPY, inW, inH, 0f);
    }

    /**
     * Frame-drop watchdog. Fed every {@link #WINDOW_MS} of steady playback (not paused, seeking,
     * scrubbing or buffering). Two windows in a row above {@link #DROP_RATIO} → step down
     * (FSR → Lite → off).
     */
    static final class Watchdog {
        static final long WINDOW_MS = 2_000;
        static final double DROP_RATIO = 0.12;
        static final int STRIKES = 2;
        private int strikes;

        /** @return true when the upscaler should step down. */
        boolean onWindow(long droppedFrames, double expectedFrames) {
            if (expectedFrames < 10) return false; // too short / unknown frame rate
            if (droppedFrames / expectedFrames > DROP_RATIO) strikes++;
            else strikes = 0;
            if (strikes >= STRIKES) {
                strikes = 0;
                return true;
            }
            return false;
        }

        void reset() {
            strikes = 0;
        }
    }
}
