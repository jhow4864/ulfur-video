package com.jamh.videoplayer;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;

import java.io.IOException;

/**
 * GL side of {@link UpscaleEffect}. Runs on Media3's video-effects GL thread.
 *
 * <pre>
 *  PLAN_UPSCALE:      input ──EASU (or Catmull-Rom)──▶ intermediate (out size) ──RCAS──▶ output
 *  PLAN_SHARPEN:      input ──RCAS──▶ output (same size)
 *  PLAN_COPY:         input ──copy──▶ output (same size)
 *  PLAN_A4K_UPSCALE:  input ──Anime4K Restore──▶ restored ──Anime4K CNN x2──▶ cnn (2x)
 *                                                  ──EASU up / Catmull-Rom down (or none)──▶ output
 *  PLAN_A4K_RESTORE:  input ──Anime4K Restore──▶ output (same size)
 * </pre>
 *
 * HDR input (linear BT.2020, float textures) is always copied unchanged: the shaders are tuned for
 * gamma-encoded SDR, and the player removes the effect for HDR videos anyway.
 *
 * <p>1.8.0: if Sharp can't be built on this GPU (no ES 3 / half-float targets, or a compile error),
 * this program quietly routes as Balanced and flags it in {@link Upscaler.FrameTimes}; the player
 * then shows "Sharp → Balanced" and keeps it for the session. The passes are timed on the GPU
 * ({@link GpuFrameTimer}) for {@link Upscaler.SharpGuard}.
 */
@OptIn(markerClass = UnstableApi.class)
final class UpscaleShaderProgram extends BaseGlShaderProgram {

    static final String VERTEX = "shaders/ulfur_upscale_vertex.glsl";
    static final String EASU = "shaders/ulfur_fsr_easu.glsl";
    static final String LITE = "shaders/ulfur_catmull_rom.glsl";
    static final String RCAS = "shaders/ulfur_rcas.glsl";
    static final String COPY = "shaders/ulfur_copy.glsl";

    private final UpscaleEffect effect;
    private final boolean useHdr;
    /** Mode actually routed: Sharp becomes Balanced here when Anime4K can't run on this GPU. */
    private final int mode;
    private final GlProgram copyProgram;
    private final GlProgram upscaleProgram; // null for HDR
    private final GlProgram rcasProgram;    // null for HDR
    @Nullable private GlProgram easuProgram;  // Sharp's resampler (2x -> larger)
    @Nullable private GlProgram liteProgram;  // Sharp's resampler (2x -> smaller, or Lite)
    @Nullable private Anime4kPipeline anime4k;
    @Nullable private final GpuFrameTimer timer;

    private Upscaler.Plan plan;
    private int inWidth;
    private int inHeight;
    private int midTexId = C.INDEX_UNSET;
    private int midFboId = C.INDEX_UNSET;
    private int restoredTexId = C.INDEX_UNSET;
    private int restoredFboId = C.INDEX_UNSET;
    private int cnnTexId = C.INDEX_UNSET;
    private int cnnFboId = C.INDEX_UNSET;
    private final int[] boundFbo = new int[1];

    UpscaleShaderProgram(Context context, boolean useHdr, UpscaleEffect effect)
            throws VideoFrameProcessingException {
        super(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1);
        this.effect = effect;
        this.useHdr = useHdr;
        int routed = effect.mode;
        try {
            copyProgram = program(context, COPY);
            if (useHdr) {
                upscaleProgram = null;
                rcasProgram = null;
            } else {
                upscaleProgram = program(context, effect.quality == Upscaler.QUALITY_LITE ? LITE : EASU);
                rcasProgram = program(context, RCAS);
                if (routed == Upscaler.MODE_SHARP) {
                    String why = Anime4kPipeline.unsupportedReason();
                    if (why == null) {
                        try {
                            anime4k = new Anime4kPipeline(context, /* withUpscale= */ true);
                            easuProgram = effect.quality == Upscaler.QUALITY_LITE ? program(context, EASU) : upscaleProgram;
                            liteProgram = effect.quality == Upscaler.QUALITY_LITE ? upscaleProgram : program(context, LITE);
                        } catch (IOException | GlUtil.GlException | RuntimeException e) {
                            why = "shader error: " + e.getMessage();
                            anime4k = null;
                            GLES20.glGetError();
                        }
                    }
                    if (why != null) {
                        routed = Upscaler.MODE_BALANCED;
                        if (effect.frameTimes != null) effect.frameTimes.markSharpUnsupported(why);
                    }
                }
            }
        } catch (IOException | GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
        mode = routed;
        timer = useHdr || effect.frameTimes == null ? null : new GpuFrameTimer(effect.frameTimes);
    }

    private static GlProgram program(Context context, String fragment) throws IOException, GlUtil.GlException {
        GlProgram p = new GlProgram(context, VERTEX, fragment);
        p.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
        return p;
    }

    @Override
    public Size configure(int inputWidth, int inputHeight) throws VideoFrameProcessingException {
        inWidth = inputWidth;
        inHeight = inputHeight;
        plan = Upscaler.route(useHdr, inputWidth, inputHeight, effect.boxWidth, effect.boxHeight, mode, effect.quality);
        try {
            deleteIntermediates();
            if (plan.kind == Upscaler.PLAN_UPSCALE) {
                midTexId = GlUtil.createTexture(plan.outWidth, plan.outHeight, /* useHighPrecisionColorComponents= */ false);
                midFboId = GlUtil.createFboForTexture(midTexId);
            } else if (plan.isAnime4k() && anime4k != null) {
                anime4k.configure(inputWidth, inputHeight);
                if (plan.kind == Upscaler.PLAN_A4K_UPSCALE) {
                    restoredTexId = GlUtil.createTexture(inputWidth, inputHeight, false);
                    restoredFboId = GlUtil.createFboForTexture(restoredTexId);
                    if (plan.resample != Upscaler.RESAMPLE_NONE) {
                        cnnTexId = GlUtil.createTexture(plan.cnnWidth, plan.cnnHeight, false);
                        cnnFboId = GlUtil.createFboForTexture(cnnTexId);
                    }
                }
            }
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
        if (timer != null) timer.restartWarmup();
        return new Size(plan.outWidth, plan.outHeight);
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
        try {
            if (timer != null) timer.begin();
            switch (plan.kind) {
                case Upscaler.PLAN_UPSCALE: {
                    // BaseGlShaderProgram has bound the output texture's FBO; remember it.
                    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFbo, 0);
                    int outFbo = boundFbo[0];
                    GlUtil.focusFramebufferUsingCurrentContext(midFboId, plan.outWidth, plan.outHeight);
                    resample(upscaleProgram, inputTexId, inWidth, inHeight);

                    GlUtil.focusFramebufferUsingCurrentContext(outFbo, plan.outWidth, plan.outHeight);
                    drawRcas(midTexId, plan.outWidth, plan.outHeight);
                    break;
                }
                case Upscaler.PLAN_A4K_UPSCALE: {
                    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFbo, 0);
                    int outFbo = boundFbo[0];
                    Anime4kPipeline a4k = anime4k;
                    a4k.restore(inputTexId, restoredFboId);
                    if (plan.resample == Upscaler.RESAMPLE_NONE) {
                        a4k.upscale(restoredTexId, outFbo);
                    } else {
                        a4k.upscale(restoredTexId, cnnFboId);
                        GlUtil.focusFramebufferUsingCurrentContext(outFbo, plan.outWidth, plan.outHeight);
                        resample(plan.resample == Upscaler.RESAMPLE_EASU ? easuProgram : liteProgram,
                                cnnTexId, plan.cnnWidth, plan.cnnHeight);
                    }
                    GlUtil.focusFramebufferUsingCurrentContext(outFbo, plan.outWidth, plan.outHeight);
                    break;
                }
                case Upscaler.PLAN_A4K_RESTORE: {
                    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFbo, 0);
                    int outFbo = boundFbo[0];
                    anime4k.restore(inputTexId, outFbo);
                    GlUtil.focusFramebufferUsingCurrentContext(outFbo, plan.outWidth, plan.outHeight);
                    break;
                }
                case Upscaler.PLAN_SHARPEN:
                    drawRcas(inputTexId, inWidth, inHeight);
                    break;
                default:
                    copyProgram.use();
                    copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                    copyProgram.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    break;
            }
            if (timer != null) timer.end();
            GlUtil.checkGlError();
        } catch (GlUtil.GlException | RuntimeException e) {
            throw new VideoFrameProcessingException(e, presentationTimeUs);
        }
    }

    /** EASU or Catmull-Rom from {@code texId} ({@code w x h}) into the bound framebuffer at the output size. */
    private void resample(GlProgram p, int texId, int w, int h) throws GlUtil.GlException {
        p.use();
        p.setSamplerTexIdUniform("uTexSampler", texId, 0);
        p.setFloatsUniform("uInSize", new float[]{w, h});
        p.setFloatsUniform("uOutSize", new float[]{plan.outWidth, plan.outHeight});
        p.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawRcas(int texId, int w, int h) throws GlUtil.GlException {
        rcasProgram.use();
        rcasProgram.setSamplerTexIdUniform("uTexSampler", texId, 0);
        rcasProgram.setFloatsUniform("uSize", new float[]{w, h});
        rcasProgram.setFloatUniform("uSharpness", plan.sharpness);
        rcasProgram.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void deleteIntermediates() throws GlUtil.GlException {
        if (midFboId != C.INDEX_UNSET) GlUtil.deleteFbo(midFboId);
        if (midTexId != C.INDEX_UNSET) GlUtil.deleteTexture(midTexId);
        if (restoredFboId != C.INDEX_UNSET) GlUtil.deleteFbo(restoredFboId);
        if (restoredTexId != C.INDEX_UNSET) GlUtil.deleteTexture(restoredTexId);
        if (cnnFboId != C.INDEX_UNSET) GlUtil.deleteFbo(cnnFboId);
        if (cnnTexId != C.INDEX_UNSET) GlUtil.deleteTexture(cnnTexId);
        midFboId = midTexId = restoredFboId = restoredTexId = cnnFboId = cnnTexId = C.INDEX_UNSET;
    }

    @Override
    public void release() throws VideoFrameProcessingException {
        super.release();
        try {
            deleteIntermediates();
            copyProgram.delete();
            if (upscaleProgram != null) upscaleProgram.delete();
            if (rcasProgram != null) rcasProgram.delete();
            if (easuProgram != null && easuProgram != upscaleProgram) easuProgram.delete();
            if (liteProgram != null && liteProgram != upscaleProgram) liteProgram.delete();
            if (anime4k != null) anime4k.release();
            if (timer != null) timer.release();
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
    }
}
