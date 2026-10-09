package com.jamh.videoplayer;

import java.util.Locale;

/**
 * 1.5.0 real-time upscaling: modes, the per-frame plan and the frame-drop watchdog.
 * 1.8.0: Sharp is Anime4K v4 (Restore + Upscale CNN x2, M) with an automatic drop back to Balanced;
 * see {@link #route}, {@link SharpGuard} and {@link FrameTimes}.
 *
 * <p>Pure Java (no GL) so it can be unit tested. The GPU work lives in {@link UpscaleEffect} /
 * {@link UpscaleShaderProgram} / {@link Anime4kPipeline}; shader sources are in
 * {@code assets/shaders/ulfur_*.glsl}.
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
    /** 1.8.0 Sharp: Anime4K Restore + Upscale CNN x2 at the source size, then resample to the output. */
    static final int PLAN_A4K_UPSCALE = 3;
    /** 1.8.0 Sharp on a video that already fills the screen (≤ 1080p): Anime4K Restore only. */
    static final int PLAN_A4K_RESTORE = 4;

    // ---------------------------------------------------------------- 1.8.0 resampler after the CNN
    static final int RESAMPLE_NONE = 0;  // the CNN's 2x output is exactly the output size
    static final int RESAMPLE_EASU = 1;  // 2x -> larger output: FSR 1.0 EASU (no RCAS on Anime4K)
    static final int RESAMPLE_LITE = 2;  // 2x -> smaller output, or Lite quality: Catmull-Rom

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

    /** Labels for the long-press picker (1.8.0: Sharp is Anime4K and says what it's good at). */
    static String[] pickerItems() {
        return new String[]{
                "Off",
                "Balanced: FSR 1.0 upscale + gentle sharpening",
                "Sharp: Anime4K CNN · best for anime & cartoons"};
    }

    /** Sharp is dropped to Balanced for the session when the phone can't run it in time. */
    static int effectiveMode(int mode, boolean sharpDowngraded) {
        mode = sanitizeMode(mode);
        return mode == MODE_SHARP && sharpDowngraded ? MODE_BALANCED : mode;
    }

    /** The GL pipeline is only built when something will be processed. HDR never is. */
    static boolean pipelineWanted(int mode, boolean sessionOff, boolean hdr) {
        return sanitizeMode(mode) != MODE_OFF && !sessionOff && !hdr;
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

        /** 1.8.0 Anime4K plans: size of the CNN's 2x output (0 otherwise) and how it reaches outWidth. */
        final int cnnWidth;
        final int cnnHeight;
        final int resample;

        Plan(int kind, int outWidth, int outHeight, float sharpness) {
            this(kind, outWidth, outHeight, sharpness, 0, 0, RESAMPLE_NONE);
        }

        Plan(int kind, int outWidth, int outHeight, float sharpness, int cnnWidth, int cnnHeight, int resample) {
            this.kind = kind;
            this.outWidth = outWidth;
            this.outHeight = outHeight;
            this.sharpness = sharpness;
            this.cnnWidth = cnnWidth;
            this.cnnHeight = cnnHeight;
            this.resample = resample;
        }

        boolean isAnime4k() {
            return kind == PLAN_A4K_UPSCALE || kind == PLAN_A4K_RESTORE;
        }

        String describe(int inWidth, int inHeight) {
            switch (kind) {
                case PLAN_UPSCALE:
                    return String.format(Locale.UK, "%d×%d → %d×%d", inWidth, inHeight, outWidth, outHeight);
                case PLAN_A4K_UPSCALE:
                    return String.format(Locale.UK, "%d×%d → %d×%d · Anime4K 2× CNN%s", inWidth, inHeight, outWidth, outHeight,
                            resample == RESAMPLE_EASU ? " + FSR EASU" : resample == RESAMPLE_LITE ? " + bicubic" : "");
                case PLAN_A4K_RESTORE:
                    return String.format(Locale.UK, "%d×%d, Anime4K Restore only (video already fills the screen)", inWidth, inHeight);
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
     * 1.8.0: what {@link UpscaleShaderProgram} actually runs for one frame size. Off / Balanced are
     * exactly {@link #plan} (FSR 1.0, unchanged since 1.5.0). Sharp:
     * <ul>
     *   <li>HDR input: always copied (never processed);</li>
     *   <li>source above ~1080p (more than {@link #MAX_OUTPUT_PIXELS}): behaves like Balanced;</li>
     *   <li>video smaller than the area it's drawn in: Anime4K Restore + Upscale CNN x2 at the source
     *       size, then FSR EASU (Lite quality: Catmull-Rom) up, or Catmull-Rom down, to the same output
     *       size Balanced would use. No RCAS/CAS on top - the CNN already sharpens;</li>
     *   <li>video already fills the screen: Anime4K Restore only, no upscale.</li>
     * </ul>
     */
    static Plan route(boolean hdr, int inW, int inH, int boxW, int boxH, int mode, int quality) {
        if (hdr || inW <= 0 || inH <= 0) return new Plan(PLAN_COPY, Math.max(inW, 1), Math.max(inH, 1), 0f);
        mode = sanitizeMode(mode);
        if (mode != MODE_SHARP) return plan(inW, inH, boxW, boxH, mode);
        if ((long) inW * inH > MAX_OUTPUT_PIXELS) return plan(inW, inH, boxW, boxH, MODE_BALANCED);
        Plan base = plan(inW, inH, boxW, boxH, MODE_BALANCED);
        if (base.kind == PLAN_UPSCALE) {
            int cw = inW * 2;
            int ch = inH * 2;
            int resample;
            if (cw == base.outWidth && ch == base.outHeight) resample = RESAMPLE_NONE;
            else if (base.outWidth > cw && quality == QUALITY_FSR) resample = RESAMPLE_EASU;
            else resample = RESAMPLE_LITE;
            return new Plan(PLAN_A4K_UPSCALE, base.outWidth, base.outHeight, 0f, cw, ch, resample);
        }
        return new Plan(PLAN_A4K_RESTORE, inW, inH, 0f);
    }

    /**
     * 1.8.0 Sharp safety net, fed every {@link Watchdog#WINDOW_MS} (~2 s) of steady playback with the
     * GPU time the upscale passes took per frame. If the mean is over {@link #BUDGET_FRACTION} of the
     * frame interval, Sharp is dropped to Balanced for the session. One slow frame never triggers it
     * (it's a 2 s mean, and windows with fewer than {@link #MIN_SAMPLES} samples are ignored).
     */
    static final class SharpGuard {
        static final double BUDGET_FRACTION = 0.80;
        static final int MIN_SAMPLES = 8;

        static double budgetMs(double framesPerSecond) {
            return framesPerSecond > 0 ? BUDGET_FRACTION * 1000.0 / framesPerSecond : Double.MAX_VALUE;
        }

        /** @param framesPerSecond video frame rate times playback speed. @return true: drop Sharp. */
        boolean onWindow(double meanGpuMs, int samples, double framesPerSecond) {
            if (samples < MIN_SAMPLES || framesPerSecond <= 0) return false;
            return meanGpuMs > budgetMs(framesPerSecond);
        }
    }

    /**
     * 1.8.0: GPU time per processed frame, written on Media3's GL thread ({@link GpuFrameTimer}) and
     * read on the main thread by the watchdog tick. Also carries "Sharp can't run on this GPU".
     */
    static final class FrameTimes {
        private long sumNs;
        private int count;
        private long lastMeanNs = -1;
        private volatile boolean sharpUnsupported;
        private volatile String sharpUnsupportedReason;
        private volatile String timingSource = "-";

        synchronized void add(long gpuNs) {
            if (gpuNs <= 0 || gpuNs > 2_000_000_000L) return; // disjoint / bogus
            sumNs += gpuNs;
            count++;
        }

        /** Mean of the samples since the last call (ms) and how many there were; clears the window. */
        synchronized double[] drain() {
            double[] r = {count == 0 ? 0 : sumNs / (double) count / 1e6, count};
            if (count > 0) lastMeanNs = sumNs / count;
            sumNs = 0;
            count = 0;
            return r;
        }

        synchronized void reset() {
            sumNs = 0;
            count = 0;
            lastMeanNs = -1;
        }

        /** Last full window's mean GPU time in ms, or -1. */
        synchronized double lastMeanMs() {
            return lastMeanNs < 0 ? -1 : lastMeanNs / 1e6;
        }

        void markSharpUnsupported(String reason) {
            sharpUnsupportedReason = reason;
            sharpUnsupported = true;
        }

        boolean sharpUnsupported() {
            return sharpUnsupported;
        }

        String sharpUnsupportedReason() {
            return sharpUnsupportedReason;
        }

        void clearSharpUnsupported() {
            sharpUnsupported = false;
            sharpUnsupportedReason = null;
        }

        void setTimingSource(String s) {
            timingSource = s;
        }

        String timingSource() {
            return timingSource;
        }
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
