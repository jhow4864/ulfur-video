package com.jamh.videoplayer;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * "Night mode / Dialogue" audio processor (runs inside ExoPlayer's DefaultAudioSink):
 * <ol>
 *   <li>Low-shelf cut (-4 dB below ~180 Hz) to tame explosions/rumble.</li>
 *   <li>Peaking EQ (+6 dB centred on 2.2 kHz, Q 0.7 ≈ 1–4 kHz) for speech clarity; 5.1 centre channel +3 dB.</li>
 *   <li>Linked feed-forward compressor (threshold -26 dBFS, 3:1, 8 ms attack, 250 ms release, +8 dB make-up)
 *       so quiet dialogue comes up and loud action comes down.</li>
 *   <li>Peak limiter at -1 dBFS (instant attack, 120 ms release) plus a hard clamp → no clipping.</li>
 * </ol>
 * Toggling is click-free: a 60 ms wet/dry crossfade. Works on 16-bit PCM and float PCM.
 */
@OptIn(markerClass = UnstableApi.class)
public final class DialogueBoostProcessor extends BaseAudioProcessor {

    static final float SPEECH_GAIN_DB = 6f;
    static final float SPEECH_FREQ_HZ = 2200f;
    static final float SPEECH_Q = 0.7f;
    static final float LOW_SHELF_DB = -4f;
    static final float LOW_SHELF_HZ = 180f;
    static final float THRESHOLD_DB = -26f;
    static final float RATIO = 3f;
    static final float MAKEUP_DB = 8f;
    static final float LIMIT = 0.891f; // -1 dBFS

    private volatile boolean enabled;
    private float wet;           // 0 = bypass, 1 = fully processed (smoothed)
    private float wetStep;

    private Biquad[] peak = new Biquad[0];
    private Biquad[] shelf = new Biquad[0];
    private float envelope;      // compressor detector (linear)
    private float limiterGain = 1f;
    private float attackCoef, releaseCoef, limReleaseCoef;
    private float[] frame = new float[0];
    private float[] dry = new float[0];
    private int lastSampleRate = -1;

    public DialogueBoostProcessor(boolean enabled) {
        this.enabled = enabled;
        this.wet = enabled ? 1f : 0f;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT && in.encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(in);
        }
        return in; // same format out; state is (re)built in onFlush when this format becomes active
    }

    /** Builds filters/buffers for the now-active {@link #inputAudioFormat}. */
    private void setUpForActiveFormat() {
        int ch = inputAudioFormat.channelCount;
        float sr = inputAudioFormat.sampleRate;
        if (ch <= 0 || sr <= 0) return;
        peak = new Biquad[ch];
        shelf = new Biquad[ch];
        for (int c = 0; c < ch; c++) {
            float gain = SPEECH_GAIN_DB + (ch == 6 && c == 2 ? 3f : 0f); // FL FR FC LFE BL BR
            peak[c] = Biquad.peaking(sr, SPEECH_FREQ_HZ, SPEECH_Q, gain);
            shelf[c] = Biquad.lowShelf(sr, LOW_SHELF_HZ, LOW_SHELF_DB);
        }
        attackCoef = coef(sr, 0.008f);
        releaseCoef = coef(sr, 0.250f);
        limReleaseCoef = coef(sr, 0.120f);
        wetStep = 1f / (0.060f * sr);
        frame = new float[ch];
        dry = new float[ch];
    }

    private static float coef(float sampleRate, float seconds) {
        return (float) Math.exp(-1.0 / (seconds * sampleRate));
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) return;
        ByteBuffer out = replaceOutputBuffer(remaining);
        if (!enabled && wet == 0f) {
            // Fully off: bit-exact passthrough.
            out.put(input);
            out.flip();
            return;
        }
        boolean isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
        int ch = inputAudioFormat.channelCount;
        int bytesPerSample = isFloat ? 4 : 2;
        int frames = remaining / (bytesPerSample * ch);
        input.order(ByteOrder.nativeOrder());
        out.order(ByteOrder.nativeOrder());

        float target = enabled ? 1f : 0f;
        float threshold = dbToLin(THRESHOLD_DB);
        float makeup = dbToLin(MAKEUP_DB);
        float slope = 1f - 1f / RATIO;

        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                float s = isFloat ? input.getFloat() : input.getShort() / 32768f;
                dry[c] = s;
                frame[c] = s;
            }
            if (wet != target) {
                wet = target > wet ? Math.min(target, wet + wetStep) : Math.max(target, wet - wetStep);
            }
            if (wet > 0f) {
                // EQ + detector (linked across channels)
                float peakAbs = 0f;
                for (int c = 0; c < ch; c++) {
                    float v = peak[c].process(shelf[c].process(frame[c]));
                    frame[c] = v;
                    peakAbs = Math.max(peakAbs, Math.abs(v));
                }
                float coefE = peakAbs > envelope ? attackCoef : releaseCoef;
                envelope = coefE * envelope + (1f - coefE) * peakAbs;
                float gain = makeup;
                if (envelope > threshold) {
                    // gain reduction in dB: (level - threshold) * (1 - 1/ratio)
                    float overDb = linToDb(envelope) - THRESHOLD_DB;
                    gain *= dbToLin(-overDb * slope);
                }
                float maxOut = 0f;
                for (int c = 0; c < ch; c++) {
                    frame[c] *= gain;
                    maxOut = Math.max(maxOut, Math.abs(frame[c]));
                }
                // Limiter: instant attack, smooth release.
                float needed = maxOut * limiterGain > LIMIT ? LIMIT / maxOut : 1f;
                if (needed < limiterGain) limiterGain = needed;
                else limiterGain = limReleaseCoef * limiterGain + (1f - limReleaseCoef) * Math.min(1f, needed);
                for (int c = 0; c < ch; c++) {
                    float processed = frame[c] * limiterGain;
                    frame[c] = wet * processed + (1f - wet) * dry[c];
                }
            } else {
                for (int c = 0; c < ch; c++) frame[c] = dry[c];
            }
            for (int c = 0; c < ch; c++) {
                float v = Math.max(-1f, Math.min(1f, frame[c])); // final safety clamp
                if (isFloat) out.putFloat(v);
                else out.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f))));
            }
        }
        // Consume any trailing partial frame bytes unchanged (shouldn't happen with PCM).
        while (input.hasRemaining()) out.put(input.get());
        out.flip();
    }

    @Override
    protected void onFlush() {
        // BaseAudioProcessor only switches inputAudioFormat to the configured format in flush();
        // until then queueInput() still receives old-format data, so per-channel state must not
        // be resized in onConfigure() (stereo -> 5.1 change = ArrayIndexOutOfBounds on the
        // playback thread).
        if (peak.length != inputAudioFormat.channelCount || frame.length != inputAudioFormat.channelCount
                || lastSampleRate != inputAudioFormat.sampleRate) {
            setUpForActiveFormat();
            lastSampleRate = inputAudioFormat.sampleRate;
        }
        for (Biquad b : peak) b.reset();
        for (Biquad b : shelf) b.reset();
        envelope = 0f;
        limiterGain = 1f;
        wet = enabled ? 1f : 0f;
    }

    @Override
    protected void onReset() {
        peak = new Biquad[0];
        shelf = new Biquad[0];
        frame = new float[0];
        dry = new float[0];
        lastSampleRate = -1;
    }

    static float dbToLin(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    static float linToDb(float lin) {
        return (float) (20.0 * Math.log10(Math.max(lin, 1e-9)));
    }

    /** RBJ audio-EQ-cookbook biquad (direct form I). */
    static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0);
            this.b1 = (float) (b1 / a0);
            this.b2 = (float) (b2 / a0);
            this.a1 = (float) (a1 / a0);
            this.a2 = (float) (a2 / a0);
        }

        static Biquad peaking(double fs, double f0, double q, double gainDb) {
            double a = Math.pow(10, gainDb / 40);
            double w0 = 2 * Math.PI * f0 / fs;
            double alpha = Math.sin(w0) / (2 * q);
            double cos = Math.cos(w0);
            return new Biquad(1 + alpha * a, -2 * cos, 1 - alpha * a,
                    1 + alpha / a, -2 * cos, 1 - alpha / a);
        }

        static Biquad lowShelf(double fs, double f0, double gainDb) {
            double a = Math.pow(10, gainDb / 40);
            double w0 = 2 * Math.PI * f0 / fs;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / 2 * Math.sqrt(2); // shelf slope S = 1
            double sq = 2 * Math.sqrt(a) * alpha;
            return new Biquad(
                    a * ((a + 1) - (a - 1) * cos + sq),
                    2 * a * ((a - 1) - (a + 1) * cos),
                    a * ((a + 1) - (a - 1) * cos - sq),
                    (a + 1) + (a - 1) * cos + sq,
                    -2 * ((a - 1) + (a + 1) * cos),
                    (a + 1) + (a - 1) * cos - sq);
        }

        float process(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            if (Math.abs(y) < 1e-20f) y = 0f; // denormal guard
            return y;
        }

        void reset() {
            x1 = x2 = y1 = y2 = 0f;
        }
    }
}
