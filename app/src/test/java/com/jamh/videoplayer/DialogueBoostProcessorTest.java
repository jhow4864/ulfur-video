package com.jamh.videoplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DialogueBoostProcessorTest {

    private static final int SR = 48_000;

    /** Processes a stereo sine and returns the output samples (left channel). */
    private static float[] runSine(DialogueBoostProcessor p, int encoding, float freq, float amp, int seconds) throws Exception {
        p.configure(new AudioProcessor.AudioFormat(SR, 2, encoding));
        p.flush();
        int frames = SR * seconds;
        boolean isFloat = encoding == C.ENCODING_PCM_FLOAT;
        ByteBuffer in = ByteBuffer.allocateDirect(frames * 2 * (isFloat ? 4 : 2)).order(ByteOrder.nativeOrder());
        for (int i = 0; i < frames; i++) {
            float v = (float) (amp * Math.sin(2 * Math.PI * freq * i / SR));
            for (int c = 0; c < 2; c++) {
                if (isFloat) in.putFloat(v);
                else in.putShort((short) Math.round(v * 32767f));
            }
        }
        in.flip();
        p.queueInput(in);
        ByteBuffer out = p.getOutput().order(ByteOrder.nativeOrder());
        float[] left = new float[frames];
        for (int i = 0; i < frames; i++) {
            left[i] = isFloat ? out.getFloat() : out.getShort() / 32768f;
            if (isFloat) out.getFloat(); else out.getShort();
        }
        return left;
    }

    /** RMS in dBFS of the last half (steady state). */
    private static double rmsDb(float[] s) {
        double sum = 0;
        int from = s.length / 2;
        for (int i = from; i < s.length; i++) sum += s[i] * s[i];
        return 20 * Math.log10(Math.sqrt(sum / (s.length - from)) + 1e-12);
    }

    private static float peak(float[] s) {
        float m = 0;
        for (float v : s) m = Math.max(m, Math.abs(v));
        return m;
    }

    @Test
    public void boostsSpeechBandRelativeToBass() throws Exception {
        float amp = 0.03f; // ≈ -30 dBFS peak
        double speech = rmsDb(runSine(new DialogueBoostProcessor(true), C.ENCODING_PCM_FLOAT, 2000f, amp, 2));
        double bass = rmsDb(runSine(new DialogueBoostProcessor(true), C.ENCODING_PCM_FLOAT, 200f, amp, 2));
        double inDb = 20 * Math.log10(amp / Math.sqrt(2));
        assertTrue("2 kHz should be louder than 200 Hz by > 4 dB, got " + (speech - bass), speech - bass > 4);
        assertTrue("quiet speech should be lifted, got " + (speech - inDb) + " dB", speech - inDb > 3);
    }

    @Test
    public void compressesDynamicRange() throws Exception {
        double quietIn = 20 * Math.log10(0.01 / Math.sqrt(2));   // -43 dBFS RMS
        double loudIn = 20 * Math.log10(0.5 / Math.sqrt(2));     // -9 dBFS RMS
        double quietOut = rmsDb(runSine(new DialogueBoostProcessor(true), C.ENCODING_PCM_FLOAT, 1000f, 0.01f, 2));
        double loudOut = rmsDb(runSine(new DialogueBoostProcessor(true), C.ENCODING_PCM_FLOAT, 1000f, 0.5f, 2));
        double inSpan = loudIn - quietIn, outSpan = loudOut - quietOut;
        assertTrue("dynamic range should shrink by > 8 dB (in " + inSpan + ", out " + outSpan + ")", inSpan - outSpan > 8);
    }

    @Test
    public void neverClipsFloat() throws Exception {
        float[] out = runSine(new DialogueBoostProcessor(true), C.ENCODING_PCM_FLOAT, 2200f, 1.0f, 1);
        assertTrue("peak " + peak(out), peak(out) <= DialogueBoostProcessor.LIMIT + 1e-3f);
    }

    @Test
    public void neverClips16Bit() throws Exception {
        float[] out = runSine(new DialogueBoostProcessor(true), C.ENCODING_PCM_16BIT, 3000f, 0.99f, 1);
        assertTrue("peak " + peak(out), peak(out) <= DialogueBoostProcessor.LIMIT + 1e-3f);
    }

    @Test
    public void disabledIsBitExactPassthrough() throws Exception {
        DialogueBoostProcessor p = new DialogueBoostProcessor(false);
        float[] out = runSine(p, C.ENCODING_PCM_16BIT, 440f, 0.5f, 1);
        for (int i = 0; i < 1000; i++) {
            float expected = Math.round((float) (0.5 * Math.sin(2 * Math.PI * 440 * i / SR)) * 32767f) / 32768f;
            assertEquals(expected, out[i], 1e-6f);
        }
    }
}
