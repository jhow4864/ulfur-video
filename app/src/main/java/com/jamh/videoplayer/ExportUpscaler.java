package com.jamh.videoplayer;

import android.content.Context;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1.8.0 "Upscale on export (2x)": the optional last step of an MP4 clip export. Takes the trimmed /
 * cropped clip Transformer just wrote and streams it frame by frame:
 *
 * <pre>
 *  MediaExtractor ─▶ MediaCodec decoder ─▶ YUV→RGBA ─▶ Real-ESRGAN x2 (ncnn Vulkan, tiled)
 *      ─▶ RGBA→YUV ─▶ MediaCodec H.264 encoder ─▶ MediaMuxer   (+ audio samples copied as-is)
 * </pre>
 *
 * Only one decoded frame and one upscaled frame are held at a time (two direct RGBA buffers), so a
 * long clip never needs more memory than a short one. Runs on the caller's (worker) thread.
 */
final class ExportUpscaler {

    interface Progress {
        /** fraction 0..1, frames done, estimated ms left (-1 unknown). */
        void onProgress(float fraction, int frames, long msLeft);
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("cancelled");
        }
    }

    private static final long TIMEOUT_US = 10_000;
    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;

    private final Context ctx;
    private final AtomicBoolean cancelled;
    @Nullable private volatile RealEsrgan esrgan;

    ExportUpscaler(Context ctx, AtomicBoolean cancelled) {
        this.ctx = ctx.getApplicationContext();
        this.cancelled = cancelled;
    }

    void cancel() {
        RealEsrgan e = esrgan;
        if (e != null) e.cancel();
    }

    /** Output size for a {@code w x h} clip: exactly 2x, rounded down to even numbers. */
    static int[] outputSize(int w, int h) {
        return new int[]{Math.max(2, (w * 2) & ~1), Math.max(2, (h * 2) & ~1)};
    }

    /** H.264 bitrate for the upscaled clip. */
    static int bitrateFor(int w, int h) {
        long px = (long) w * h;
        return px >= 3840L * 2160 * 3 / 4 ? 40_000_000 : px >= 1920L * 1080 ? 16_000_000
                : px >= 1280L * 720 ? 8_000_000 : 4_000_000;
    }

    /** True when this phone has an H.264 encoder that accepts {@code w x h}. */
    static boolean encoderSupports(int w, int h) {
        MediaFormat f = MediaFormat.createVideoFormat(AVC, w, h);
        return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f) != null;
    }

    void run(File in, File out, Progress progress) throws IOException, CancelledException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        MediaCodec enc = null;
        MediaMuxer mux = null;
        boolean muxStarted = false;
        try {
            ex.setDataSource(in.getAbsolutePath());
            int vTrack = -1, aTrack = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (vTrack < 0 && mime.startsWith("video/")) vTrack = i;
                else if (aTrack < 0 && mime.startsWith("audio/")) aTrack = i;
            }
            if (vTrack < 0) throw new IOException("No video in the trimmed clip");
            MediaFormat vf = ex.getTrackFormat(vTrack);
            long durationUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;
            int rotation = vf.containsKey("rotation-degrees") ? vf.getInteger("rotation-degrees") : 0;
            float fps = vf.containsKey(MediaFormat.KEY_FRAME_RATE) ? number(vf, MediaFormat.KEY_FRAME_RATE) : 30f;
            int srcW = vf.getInteger(MediaFormat.KEY_WIDTH), srcH = vf.getInteger(MediaFormat.KEY_HEIGHT);
            boolean srcBt709 = colorStandardIs709(vf, srcH);

            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mux.setOrientationHint(rotation);
            int muxAudio = aTrack >= 0 ? mux.addTrack(ex.getTrackFormat(aTrack)) : -1;

            ex.selectTrack(vTrack);
            dec = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME));
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            dec.configure(vf, null, null, 0);
            dec.start();

            esrgan = new RealEsrgan(ctx.getAssets());
            ByteBuffer rgbaIn = null, rgbaOut = null;
            int w = 0, h = 0, ow = 0, oh = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false, decodeDone = false, encodeDone = false;
            int frames = 0;
            long started = SystemClock.elapsedRealtime();
            long lastPts = 0;

            while (!encodeDone) {
                if (cancelled.get()) throw new CancelledException();
                // 1) feed the decoder
                if (!inputDone) {
                    int ii = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (ii >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(ii);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                // 2) one decoded frame -> upscale -> encoder
                if (!decodeDone) {
                    int oi = dec.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (oi >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (info.size > 0 || !eos) {
                            Image img = dec.getOutputImage(oi);
                            if (img != null) {
                                Rect crop = img.getCropRect();
                                int fw = crop.width() & ~1, fh = crop.height() & ~1;
                                if (enc == null) {
                                    w = fw; h = fh;
                                    int[] os = outputSize(w, h);
                                    ow = os[0]; oh = os[1];
                                    enc = createEncoder(ow, oh, fps);
                                    rgbaIn = ByteBuffer.allocateDirect(w * h * 4);
                                    rgbaOut = ByteBuffer.allocateDirect(w * 2 * h * 2 * 4);
                                }
                                if (fw == w && fh == h) {
                                    imageToRgba(img, crop, w, h, srcBt709, rgbaIn);
                                    img.close();
                                    dec.releaseOutputBuffer(oi, false);
                                    int r = esrgan.process(rgbaIn, w, h, rgbaOut);
                                    if (r == 1 || cancelled.get()) throw new CancelledException();
                                    if (r != 0) throw new IOException("The GPU upscaler failed (code " + r + ")");
                                    lastPts = info.presentationTimeUs;
                                    queueEncoderFrame(enc, encInfo, mux, rgbaOut, ow, oh, lastPts);
                                    frames++;
                                    long elapsed = SystemClock.elapsedRealtime() - started;
                                    float frac = durationUs > 0 ? Math.min(1f, lastPts / (float) durationUs) : 0f;
                                    long left = frac > 0.02f ? (long) (elapsed / frac - elapsed) : -1;
                                    progress.onProgress(frac, frames, left);
                                } else {
                                    img.close();
                                    dec.releaseOutputBuffer(oi, false); // size change mid-stream: skip
                                }
                            } else {
                                dec.releaseOutputBuffer(oi, false);
                            }
                        } else {
                            dec.releaseOutputBuffer(oi, false);
                        }
                        if (eos) {
                            decodeDone = true;
                            if (enc == null) throw new IOException("The clip had no decodable frames");
                            int ei;
                            while ((ei = enc.dequeueInputBuffer(TIMEOUT_US)) < 0) {
                                if (drainEncoder(enc, encInfo, mux, false) == DRAIN_FORMAT) startMuxer(enc, mux);
                            }
                            enc.queueInputBuffer(ei, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                    }
                }
                // 3) drain the encoder into the muxer
                if (enc != null) {
                    if (drainEncoder(enc, encInfo, mux, decodeDone) == DRAIN_FORMAT) {
                        startMuxer(enc, mux);
                        drainEncoder(enc, encInfo, mux, decodeDone);
                    }
                    encodeDone = encoderEos;
                }
            }
            muxStarted = videoTrackForMux >= 0;
            if (!muxStarted) throw new IOException("Encoder produced no output");
            // 4) audio, copied sample by sample (no re-encode)
            if (muxAudio >= 0) {
                ex.unselectTrack(vTrack);
                ex.selectTrack(aTrack);
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                ByteBuffer buf = ByteBuffer.allocate(512 * 1024);
                MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
                while (true) {
                    if (cancelled.get()) throw new CancelledException();
                    int n = ex.readSampleData(buf, 0);
                    if (n < 0) break;
                    int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    ai.set(0, n, ex.getSampleTime(), flags);
                    mux.writeSampleData(muxAudio, buf, ai);
                    ex.advance();
                }
            }
            progress.onProgress(1f, frames, 0);
        } finally {
            RealEsrgan e = esrgan;
            esrgan = null;
            if (e != null) e.close();
            release(dec);
            release(enc);
            if (mux != null) {
                try {
                    if (videoTrackForMux >= 0) mux.stop();
                } catch (RuntimeException ignored) {
                }
                mux.release();
            }
            ex.release();
        }
    }

    // ------------------------------------------------------------------ encoder

    private static final int DRAIN_NONE = 0;
    private static final int DRAIN_FORMAT = 1;
    private int videoTrackForMux = -1;
    private boolean encoderEos;

    private MediaCodec createEncoder(int w, int h, float fps) throws IOException {
        if (!encoderSupports(w, h)) {
            throw new IOException(String.format(Locale.UK,
                    "This phone's video encoder can't make a %d×%d clip. Crop a smaller area or turn off Upscale on export.", w, h));
        }
        MediaFormat f = MediaFormat.createVideoFormat(AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(w, h));
        f.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, Math.round(fps)));
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (Build.VERSION.SDK_INT >= 24) {
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        }
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
                MediaFormat.createVideoFormat(AVC, w, h));
        MediaCodec enc = MediaCodec.createByCodecName(name);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        return enc;
    }

    private void queueEncoderFrame(MediaCodec enc, MediaCodec.BufferInfo encInfo, MediaMuxer mux,
                                   ByteBuffer rgba, int w, int h, long ptsUs) throws IOException, CancelledException {
        int ei;
        while ((ei = enc.dequeueInputBuffer(TIMEOUT_US)) < 0) {
            if (cancelled.get()) throw new CancelledException();
            if (drainEncoder(enc, encInfo, mux, false) == DRAIN_FORMAT) startMuxer(enc, mux);
        }
        Image img = enc.getInputImage(ei);
        if (img != null) {
            Image.Plane[] p = img.getPlanes();
            RealEsrgan.nativeRgbaToYuv(rgba, w * 4, w, h, true,
                    p[0].getBuffer(), 0, p[0].getRowStride(), p[0].getPixelStride(),
                    p[1].getBuffer(), 0, p[2].getBuffer(), 0, p[1].getRowStride(), p[1].getPixelStride());
            img.close();
        } else {
            // No Image API for this encoder: assume the semi-planar layout it reports.
            ByteBuffer buf = enc.getInputBuffer(ei);
            if (buf == null) throw new IOException("Encoder gave no input buffer");
            MediaFormat inf = enc.getInputFormat();
            int stride = inf.containsKey("stride") ? inf.getInteger("stride") : w;
            int slice = inf.containsKey("slice-height") ? inf.getInteger("slice-height") : h;
            int cf = inf.containsKey(MediaFormat.KEY_COLOR_FORMAT) ? inf.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                    : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
            int uvOff = stride * slice;
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                RealEsrgan.nativeRgbaToYuv(rgba, w * 4, w, h, true, buf, 0, stride, 1,
                        buf, uvOff, buf, uvOff + (stride / 2) * (slice / 2), stride / 2, 1);
            } else {
                RealEsrgan.nativeRgbaToYuv(rgba, w * 4, w, h, true, buf, 0, stride, 1,
                        buf, uvOff, buf, uvOff + 1, stride, 2);
            }
        }
        enc.queueInputBuffer(ei, 0, w * h * 3 / 2, ptsUs, 0);
    }

    private void startMuxer(MediaCodec enc, MediaMuxer mux) {
        if (videoTrackForMux >= 0) return;
        videoTrackForMux = mux.addTrack(enc.getOutputFormat());
        mux.start();
    }

    /** Writes whatever the encoder has ready. @return DRAIN_FORMAT once, when the muxer must be started. */
    private int drainEncoder(MediaCodec enc, MediaCodec.BufferInfo info, MediaMuxer mux, boolean untilEos) {
        while (true) {
            int oi = enc.dequeueOutputBuffer(info, untilEos ? TIMEOUT_US : 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return DRAIN_NONE;
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (videoTrackForMux < 0) return DRAIN_FORMAT;
                continue;
            }
            if (oi < 0) continue;
            ByteBuffer data = enc.getOutputBuffer(oi);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (data != null && info.size > 0 && !config && videoTrackForMux >= 0) {
                data.position(info.offset).limit(info.offset + info.size);
                mux.writeSampleData(videoTrackForMux, data, info);
            }
            enc.releaseOutputBuffer(oi, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                encoderEos = true;
                return DRAIN_NONE;
            }
        }
    }

    // ------------------------------------------------------------------ decoder side

    private static void imageToRgba(Image img, Rect crop, int w, int h, boolean bt709, ByteBuffer rgba) {
        Image.Plane[] p = img.getPlanes();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        int yOff = crop.top * yRow + crop.left * yPix;
        int uvOff = (crop.top / 2) * uvRow + (crop.left / 2) * uvPix;
        RealEsrgan.nativeYuvToRgba(p[0].getBuffer(), yOff, yRow, yPix, p[1].getBuffer(), uvOff,
                p[2].getBuffer(), uvOff, uvRow, uvPix, w, h, bt709, rgba, w * 4);
    }

    /** BT.709 when the clip says so, or (unspecified) when it's HD - what players assume too. */
    static boolean colorStandardIs709(MediaFormat f, int height) {
        if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
            return f.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709;
        }
        return height >= 720;
    }

    private static float number(MediaFormat f, String key) {
        try {
            return f.getInteger(key);
        } catch (ClassCastException e) {
            return f.getFloat(key);
        }
    }

    private static void release(@Nullable MediaCodec c) {
        if (c == null) return;
        try {
            c.stop();
        } catch (RuntimeException ignored) {
        }
        c.release();
    }
}
