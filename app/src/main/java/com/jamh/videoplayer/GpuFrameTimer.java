package com.jamh.videoplayer;

import android.opengl.GLES20;
import android.opengl.GLES30;

/**
 * 1.8.0: measures how long the upscale passes take on the GPU, on Media3's video-effects GL thread.
 *
 * <ul>
 *   <li>With {@code GL_EXT_disjoint_timer_query} on an OpenGL ES 3 context: a ring of
 *       {@code GL_TIME_ELAPSED_EXT} queries around each frame's passes, read back a few frames later
 *       (never stalls the pipeline); results flagged as disjoint (GPU clock change) are dropped.</li>
 *   <li>Otherwise: every {@link #SAMPLE_EVERY}th frame is bracketed by {@code glFinish()} and timed with
 *       the wall clock. That one frame is serialised, the rest run as before.</li>
 * </ul>
 * Samples go to {@link Upscaler.FrameTimes}; {@link Upscaler.SharpGuard} decides on the main thread.
 */
final class GpuFrameTimer {

    static final int GL_TIME_ELAPSED_EXT = 0x88BF;
    static final int GL_GPU_DISJOINT_EXT = 0x8FBB;
    static final int RING = 4;
    static final int SAMPLE_EVERY = 8;
    /** Frames right after (re)configuration include shader warm-up; don't count them. */
    static final int WARMUP_FRAMES = 6;

    private final Upscaler.FrameTimes sink;
    private boolean queries;
    private final int[] ids = new int[RING];
    private final boolean[] pending = new boolean[RING];
    private int next;
    private boolean active;
    private long frame;
    private long wallStart;
    private boolean wallSampling;
    private final int[] tmp = new int[1];

    GpuFrameTimer(Upscaler.FrameTimes sink) {
        this.sink = sink;
        String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        queries = sink != null && Anime4kPipeline.contextMajorVersion() >= 3
                && ext != null && ext.contains("GL_EXT_disjoint_timer_query");
        if (queries) {
            GLES30.glGenQueries(RING, ids, 0);
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) queries = false;
        }
        if (sink != null) sink.setTimingSource(queries ? "GPU timer queries" : "glFinish + wall clock (sampled)");
    }

    void restartWarmup() {
        frame = 0;
    }

    void begin() {
        if (sink == null) return;
        frame++;
        if (frame <= WARMUP_FRAMES) return;
        if (queries) {
            if (pending[next]) collect();
            if (pending[next]) return; // ring full: skip this frame rather than stall
            GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, ids[next]);
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                // Driver advertises the extension but rejects the ES3 entry point: use the fallback.
                queries = false;
                sink.setTimingSource("glFinish + wall clock (sampled)");
                return;
            }
            active = true;
        } else if (frame % SAMPLE_EVERY == 0) {
            GLES20.glFinish();
            wallStart = System.nanoTime();
            wallSampling = true;
        }
    }

    void end() {
        if (sink == null) return;
        if (active) {
            GLES30.glEndQuery(GL_TIME_ELAPSED_EXT);
            pending[next] = true;
            next = (next + 1) % RING;
            active = false;
            collect();
        } else if (wallSampling) {
            GLES20.glFinish();
            sink.add(System.nanoTime() - wallStart);
            wallSampling = false;
        }
    }

    private void collect() {
        GLES20.glGetIntegerv(GL_GPU_DISJOINT_EXT, tmp, 0);
        boolean disjoint = tmp[0] != 0;
        for (int k = 0; k < RING; k++) {
            int i = (next + k) % RING; // oldest first
            if (!pending[i]) continue;
            GLES30.glGetQueryObjectuiv(ids[i], GLES30.GL_QUERY_RESULT_AVAILABLE, tmp, 0);
            if (tmp[0] == 0) break; // results arrive in order
            GLES30.glGetQueryObjectuiv(ids[i], GLES30.GL_QUERY_RESULT, tmp, 0);
            pending[i] = false;
            if (!disjoint) sink.add(tmp[0] & 0xFFFFFFFFL);
        }
        GLES20.glGetError(); // never leave an error behind for Media3's checks
    }

    void release() {
        if (queries) GLES30.glDeleteQueries(RING, ids, 0);
        queries = false;
    }
}
