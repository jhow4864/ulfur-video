package com.jamh.videoplayer;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.UnstableApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 1.8.0 Sharp: Anime4K v4 Restore CNN (M) and Upscale CNN x2 (M) as OpenGL ES 3.0 passes on Media3's
 * video-effects GL thread. Used by {@link UpscaleShaderProgram}.
 *
 * <pre>
 *  restore():  src ─conv×7 (RGBA16F feature maps)─▶ final 1×1 conv + src ─▶ dst     (source size)
 *  upscale():  src ─conv×7─▶ conv 1×1 (4 sub-pixels) ─▶ depth-to-space + bilinear 2× src ─▶ dst (2× size)
 * </pre>
 *
 * Shaders: {@code assets/shaders/ulfur_a4k_restore_cnn_m.glsl} and
 * {@code ulfur_a4k_upscale_cnn_x2_m.glsl}, generated from the original mpv shaders by
 * {@code tools/anime4k-port/port.py}. Anime4K © 2019-2021 bloc97, MIT licence
 * ({@code assets/licenses/ANIME4K_LICENSE.txt}). The feature maps hold signed values, so they need
 * colour-renderable half-float textures (ES 3.2, or ES 3.0/3.1 + EXT_color_buffer_(half_)float).
 */
@OptIn(markerClass = UnstableApi.class)
final class Anime4kPipeline {

    static final String VERTEX = "shaders/ulfur_a4k_vertex.glsl";
    static final String RESTORE = "shaders/ulfur_a4k_restore_cnn_m.glsl";
    static final String UPSCALE = "shaders/ulfur_a4k_upscale_cnn_x2_m.glsl";
    static final int RESTORE_PASSES = 8;
    static final int UPSCALE_PASSES = 9;
    static final int FEATURE_MAPS = 8;

    private static final String[] CONV = {"conv2d_tf", "conv2d_1_tf", "conv2d_2_tf", "conv2d_3_tf",
            "conv2d_4_tf", "conv2d_5_tf", "conv2d_6_tf"};

    /** Sampler names bound by each pass, in texture-unit order. */
    static String[] restoreSamplers(int pass) {
        if (pass == 0) return new String[]{"MAIN"};
        if (pass < 7) return new String[]{CONV[pass - 1]};
        String[] s = new String[8];
        s[0] = "MAIN";
        System.arraycopy(CONV, 0, s, 1, 7);
        return s;
    }

    static String[] upscaleSamplers(int pass) {
        if (pass == 0) return new String[]{"MAIN"};
        if (pass < 7) return new String[]{CONV[pass - 1]};
        if (pass == 7) return CONV.clone();
        return new String[]{"MAIN", "conv2d_last_tf"};
    }

    private final Pass[] restore = new Pass[RESTORE_PASSES];
    @Nullable private final Pass[] upscale;
    private final int[] vbo = new int[1];
    private final int[] featTex = new int[FEATURE_MAPS];
    private final int[] featFbo = new int[FEATURE_MAPS];
    private int width;
    private int height;

    Anime4kPipeline(Context context, boolean withUpscale) throws IOException, GlUtil.GlException {
        String vs = asset(context, VERTEX);
        String rs = asset(context, RESTORE);
        for (int i = 0; i < RESTORE_PASSES; i++) restore[i] = new Pass(vs, rs, i, restoreSamplers(i));
        if (withUpscale) {
            String us = asset(context, UPSCALE);
            upscale = new Pass[UPSCALE_PASSES];
            for (int i = 0; i < UPSCALE_PASSES; i++) upscale[i] = new Pass(vs, us, i, upscaleSamplers(i));
        } else {
            upscale = null;
        }
        float[] quad = {-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1};
        FloatBuffer fb = ByteBuffer.allocateDirect(quad.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(quad).position(0);
        GLES20.glGenBuffers(1, vbo, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.length * 4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        java.util.Arrays.fill(featTex, C.INDEX_UNSET);
        java.util.Arrays.fill(featFbo, C.INDEX_UNSET);
        GlUtil.checkGlError();
    }

    // ------------------------------------------------------------------ capability check

    static long contextMajorVersion() {
        try {
            return GlUtil.getContextMajorVersion();
        } catch (Throwable t) {
            return 2;
        }
    }

    /** Null when the current context can run Sharp, otherwise a short reason for the toast. */
    @Nullable
    static String unsupportedReason() {
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        if (contextMajorVersion() < 3) return "needs OpenGL ES 3 (" + version + ")";
        String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        boolean es32 = version != null && (version.contains("ES 3.2") || version.matches(".*ES 3\\.[3-9].*"));
        boolean floatTargets = es32 || (ext != null && (ext.contains("GL_EXT_color_buffer_half_float")
                || ext.contains("GL_EXT_color_buffer_float")));
        return floatTargets ? null : "needs half-float render targets";
    }

    // ------------------------------------------------------------------ resources

    /** Allocates the feature maps for a {@code w x h} source (once per size). */
    void configure(int w, int h) throws GlUtil.GlException {
        if (w == width && h == height && featTex[0] != C.INDEX_UNSET) return;
        deleteMaps();
        width = w;
        height = h;
        int maps = upscale != null ? FEATURE_MAPS : FEATURE_MAPS - 1;
        for (int i = 0; i < maps; i++) {
            featTex[i] = GlUtil.createTexture(w, h, /* useHighPrecisionColorComponents= */ true); // RGBA16F
            featFbo[i] = GlUtil.createFboForTexture(featTex[i]);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, featFbo[0]);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new GlUtil.GlException("Anime4K: half-float framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    // ------------------------------------------------------------------ passes

    /** Restore CNN: {@code srcTex} (w x h) → {@code dstFbo} (w x h). */
    void restore(int srcTex, int dstFbo) throws GlUtil.GlException {
        draw(restore[0], featFbo[0], width, height, srcTex);
        for (int i = 1; i < 7; i++) draw(restore[i], featFbo[i], width, height, featTex[i - 1]);
        draw(restore[7], dstFbo, width, height, srcTex, featTex[0], featTex[1], featTex[2], featTex[3],
                featTex[4], featTex[5], featTex[6]);
    }

    /** Upscale CNN x2: {@code srcTex} (w x h, GL_LINEAR) → {@code dstFbo} (2w x 2h). */
    void upscale(int srcTex, int dstFbo) throws GlUtil.GlException {
        Pass[] u = upscale;
        if (u == null) throw new GlUtil.GlException("Anime4K: built without the upscale network");
        draw(u[0], featFbo[0], width, height, srcTex);
        for (int i = 1; i < 7; i++) draw(u[i], featFbo[i], width, height, featTex[i - 1]);
        draw(u[7], featFbo[7], width, height, featTex[0], featTex[1], featTex[2], featTex[3], featTex[4],
                featTex[5], featTex[6]);
        draw(u[8], dstFbo, width * 2, height * 2, srcTex, featTex[7]);
    }

    private void draw(Pass p, int fbo, int w, int h, int... textures) throws GlUtil.GlException {
        GlUtil.focusFramebufferUsingCurrentContext(fbo, w, h);
        GLES20.glUseProgram(p.program);
        for (int unit = 0; unit < textures.length; unit++) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[unit]);
            GLES20.glUniform1i(p.samplers[unit], unit);
        }
        GLES20.glUniform2i(p.srcSize, width, height);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GlUtil.checkGlError();
    }

    private void deleteMaps() throws GlUtil.GlException {
        for (int i = 0; i < FEATURE_MAPS; i++) {
            if (featFbo[i] != C.INDEX_UNSET) GlUtil.deleteFbo(featFbo[i]);
            if (featTex[i] != C.INDEX_UNSET) GlUtil.deleteTexture(featTex[i]);
            featFbo[i] = C.INDEX_UNSET;
            featTex[i] = C.INDEX_UNSET;
        }
        width = 0;
        height = 0;
    }

    void release() throws GlUtil.GlException {
        deleteMaps();
        for (Pass p : restore) if (p != null) GLES20.glDeleteProgram(p.program);
        if (upscale != null) for (Pass p : upscale) if (p != null) GLES20.glDeleteProgram(p.program);
        GLES20.glDeleteBuffers(1, vbo, 0);
        GlUtil.checkGlError();
    }

    // ------------------------------------------------------------------ programs

    static String asset(Context context, String path) throws IOException {
        try (InputStream in = context.getAssets().open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** "#version 300 es" + "#define PASS n" + the shared multi-pass source. */
    static String passSource(String shared, int pass) {
        return "#version 300 es\n#define PASS " + pass + "\n" + shared;
    }

    private static final class Pass {
        final int program;
        final int[] samplers;
        final int srcSize;

        Pass(String vertex, String fragment, int pass, String[] samplerNames) throws GlUtil.GlException {
            int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, passSource(fragment, pass));
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glBindAttribLocation(program, 0, "aFramePosition");
            GLES20.glLinkProgram(program);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            if (ok[0] != GLES20.GL_TRUE) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new GlUtil.GlException("Anime4K pass " + pass + " link failed: " + log);
            }
            samplers = new int[samplerNames.length];
            for (int i = 0; i < samplerNames.length; i++) {
                samplers[i] = GLES20.glGetUniformLocation(program, samplerNames[i]);
            }
            srcSize = GLES20.glGetUniformLocation(program, "uSrcSize");
        }

        private static int compile(int type, String src) throws GlUtil.GlException {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] != GLES20.GL_TRUE) {
                String log = GLES20.glGetShaderInfoLog(s);
                GLES20.glDeleteShader(s);
                throw new GlUtil.GlException("Anime4K shader compile failed: " + log);
            }
            return s;
        }
    }
}
