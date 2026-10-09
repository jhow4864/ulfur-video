package com.jamh.videoplayer;

import android.content.res.AssetManager;
import android.os.Build;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

/**
 * 1.8.0 "Upscale on export (2x)": Real-ESRGAN AnimeVideo v3 ({@code realesr-animevideov3-x2},
 * BSD-3-Clause) running on ncnn's Vulkan compute (BSD-3-Clause), in {@code libulfur_esrgan.so}
 * (arm64-v8a only; see app/src/main/cpp). Licences: assets/licenses/REALESRGAN_LICENSE.txt,
 * NCNN_LICENSE.txt, GLSLANG_LICENSE.txt.
 *
 * <p>Not thread-safe: one instance per export, used from the export worker thread.
 */
final class RealEsrgan implements AutoCloseable {

    static final String PARAM = "models/realesr-animevideov3-x2.param";
    static final String BIN = "models/realesr-animevideov3-x2.bin";
    /** Input tile edge (px). The x2 model runs a x4 network internally, so this bounds GPU memory. */
    static final int GPU_TILE = 128;

    /** Why the option is disabled, or null when it can be used. */
    static final class Availability {
        final boolean available;
        @Nullable final String reason;
        @Nullable final String device;

        Availability(boolean available, @Nullable String reason, @Nullable String device) {
            this.available = available;
            this.reason = reason;
            this.device = device;
        }
    }

    private static volatile Availability cached;

    /** Feature flag + ABI + native library + Vulkan device. Slow the first time (call off the main thread). */
    static Availability probe() {
        Availability a = cached;
        if (a != null) return a;
        a = probeUncached();
        cached = a;
        return a;
    }

    @Nullable
    static Availability cachedOrNull() {
        return cached;
    }

    /** For tests. */
    static void setCachedForTest(@Nullable Availability a) {
        cached = a;
    }

    private static Availability probeUncached() {
        if (!BuildConfig.UPSCALE_EXPORT) return new Availability(false, "Not included in this build.", null);
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi)) arm64 = true;
        if (!arm64) return new Availability(false, "Needs a 64-bit ARM phone (arm64).", null);
        try {
            System.loadLibrary("ulfur_esrgan");
        } catch (Throwable t) {
            return new Availability(false, "The upscaler library couldn't be loaded on this phone.", null);
        }
        String device;
        try {
            device = nativeVulkanDevice();
        } catch (Throwable t) {
            device = null;
        }
        if (device == null || device.isEmpty()) {
            return new Availability(false, "Needs Vulkan GPU compute, which this phone doesn't offer.", null);
        }
        return new Availability(true, null, device);
    }

    private long handle;

    RealEsrgan(AssetManager assets) {
        handle = nativeCreate(assets, PARAM, BIN, true, GPU_TILE);
        if (handle == 0) throw new IllegalStateException("Couldn't load the Real-ESRGAN model");
    }

    /** RGBA in (w x h) → RGBA out (2w x 2h). @return 0 ok, 1 cancelled, &lt;0 error. */
    int process(ByteBuffer in, int w, int h, ByteBuffer out) {
        return nativeProcess(handle, in, w, h, w * 4, out, w * 2 * 4);
    }

    /** Safe from any thread; the current frame stops at the next tile. */
    void cancel() {
        long h = handle;
        if (h != 0) nativeCancel(h);
    }

    @Override
    public void close() {
        long h = handle;
        handle = 0;
        if (h != 0) nativeDestroy(h);
    }

    // ------------------------------------------------------------------ native

    @Nullable static native String nativeVulkanDevice();

    static native long nativeCreate(AssetManager assets, String param, String bin, boolean gpu, int tile);

    static native int nativeProcess(long handle, ByteBuffer in, int w, int h, int inStride, ByteBuffer out, int outStride);

    static native void nativeCancel(long handle);

    static native void nativeDestroy(long handle);

    static native void nativeYuvToRgba(ByteBuffer y, int yOff, int yRow, int yPix, ByteBuffer u, int uOff,
                                       ByteBuffer v, int vOff, int uvRow, int uvPix, int w, int h,
                                       boolean bt709, ByteBuffer rgba, int stride);

    static native void nativeRgbaToYuv(ByteBuffer rgba, int stride, int w, int h, boolean bt709,
                                       ByteBuffer y, int yOff, int yRow, int yPix, ByteBuffer u, int uOff,
                                       ByteBuffer v, int vOff, int uvRow, int uvPix);
}
