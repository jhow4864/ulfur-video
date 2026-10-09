package com.jamh.videoplayer;

import android.content.Context;
import android.opengl.GLES20;

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
 *  PLAN_UPSCALE:  input ──EASU (or Catmull-Rom)──▶ intermediate (out size) ──RCAS──▶ output
 *  PLAN_SHARPEN:  input ──RCAS──▶ output (same size)
 *  PLAN_COPY:     input ──copy──▶ output (same size)
 * </pre>
 *
 * HDR input (linear BT.2020, float textures) is always copied unchanged: the shaders are tuned for
 * gamma-encoded SDR, and the player removes the effect for HDR videos anyway.
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
    private final GlProgram copyProgram;
    private final GlProgram upscaleProgram; // null for HDR
    private final GlProgram rcasProgram;    // null for HDR

    private Upscaler.Plan plan;
    private int inWidth;
    private int inHeight;
    private int midTexId = C.INDEX_UNSET;
    private int midFboId = C.INDEX_UNSET;
    private final int[] boundFbo = new int[1];

    UpscaleShaderProgram(Context context, boolean useHdr, UpscaleEffect effect)
            throws VideoFrameProcessingException {
        super(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1);
        this.effect = effect;
        this.useHdr = useHdr;
        try {
            copyProgram = program(context, COPY);
            if (useHdr) {
                upscaleProgram = null;
                rcasProgram = null;
            } else {
                upscaleProgram = program(context, effect.quality == Upscaler.QUALITY_LITE ? LITE : EASU);
                rcasProgram = program(context, RCAS);
            }
        } catch (IOException | GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
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
        plan = useHdr
                ? new Upscaler.Plan(Upscaler.PLAN_COPY, inputWidth, inputHeight, 0f)
                : Upscaler.plan(inputWidth, inputHeight, effect.boxWidth, effect.boxHeight, effect.mode);
        try {
            deleteIntermediate();
            if (plan.kind == Upscaler.PLAN_UPSCALE) {
                midTexId = GlUtil.createTexture(plan.outWidth, plan.outHeight, /* useHighPrecisionColorComponents= */ false);
                midFboId = GlUtil.createFboForTexture(midTexId);
            }
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
        return new Size(plan.outWidth, plan.outHeight);
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
        try {
            switch (plan.kind) {
                case Upscaler.PLAN_UPSCALE: {
                    // BaseGlShaderProgram has bound the output texture's FBO; remember it.
                    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFbo, 0);
                    int outFbo = boundFbo[0];
                    GlUtil.focusFramebufferUsingCurrentContext(midFboId, plan.outWidth, plan.outHeight);
                    upscaleProgram.use();
                    upscaleProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                    upscaleProgram.setFloatsUniform("uInSize", new float[]{inWidth, inHeight});
                    upscaleProgram.setFloatsUniform("uOutSize", new float[]{plan.outWidth, plan.outHeight});
                    upscaleProgram.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                    GlUtil.focusFramebufferUsingCurrentContext(outFbo, plan.outWidth, plan.outHeight);
                    drawRcas(midTexId, plan.outWidth, plan.outHeight);
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
            GlUtil.checkGlError();
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e, presentationTimeUs);
        }
    }

    private void drawRcas(int texId, int w, int h) throws GlUtil.GlException {
        rcasProgram.use();
        rcasProgram.setSamplerTexIdUniform("uTexSampler", texId, 0);
        rcasProgram.setFloatsUniform("uSize", new float[]{w, h});
        rcasProgram.setFloatUniform("uSharpness", plan.sharpness);
        rcasProgram.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void deleteIntermediate() throws GlUtil.GlException {
        if (midFboId != C.INDEX_UNSET) GlUtil.deleteFbo(midFboId);
        if (midTexId != C.INDEX_UNSET) GlUtil.deleteTexture(midTexId);
        midFboId = C.INDEX_UNSET;
        midTexId = C.INDEX_UNSET;
    }

    @Override
    public void release() throws VideoFrameProcessingException {
        super.release();
        try {
            deleteIntermediate();
            copyProgram.delete();
            if (upscaleProgram != null) upscaleProgram.delete();
            if (rcasProgram != null) rcasProgram.delete();
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
    }
}
