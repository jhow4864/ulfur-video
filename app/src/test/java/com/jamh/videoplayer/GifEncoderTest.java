package com.jamh.videoplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure-JVM checks of the GIF writer: structure, LZW round trip and palette quality. */
public class GifEncoderTest {

    /** Minimal GIF LZW decoder for one image's data sub-blocks starting at {@code pos}. */
    private static byte[] decodeImage(byte[] g, int[] posRef, int pixelCount) {
        int pos = posRef[0];
        int minCode = g[pos++] & 0xFF;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        while (true) {
            int len = g[pos++] & 0xFF;
            if (len == 0) break;
            data.write(g, pos, len);
            pos += len;
        }
        posRef[0] = pos;
        byte[] d = data.toByteArray();
        int clear = 1 << minCode, eoi = clear + 1;
        int codeSize = minCode + 1, next = eoi + 1;
        List<byte[]> dict = new ArrayList<>();
        byte[] out = new byte[pixelCount];
        int outPos = 0;
        long bitBuf = 0;
        int bits = 0, dataPos = 0;
        byte[] prev = null;
        resetDict(dict, clear);
        while (true) {
            while (bits < codeSize && dataPos < d.length) {
                bitBuf |= (long) (d[dataPos++] & 0xFF) << bits;
                bits += 8;
            }
            if (bits < codeSize) break;
            int code = (int) (bitBuf & ((1 << codeSize) - 1));
            bitBuf >>>= codeSize;
            bits -= codeSize;
            if (code == clear) {
                resetDict(dict, clear);
                codeSize = minCode + 1;
                next = eoi + 1;
                prev = null;
                continue;
            }
            if (code == eoi) break;
            byte[] entry;
            if (code < dict.size() && dict.get(code) != null) {
                entry = dict.get(code);
            } else if (code == next && prev != null) {
                entry = concat(prev, prev[0]);
            } else {
                throw new AssertionError("bad code " + code + " next " + next);
            }
            System.arraycopy(entry, 0, out, outPos, entry.length);
            outPos += entry.length;
            if (prev != null && next < 4096) {
                while (dict.size() <= next) dict.add(null);
                dict.set(next, concat(prev, entry[0]));
                next++;
                if (next == (1 << codeSize) && codeSize < 12) codeSize++;
            }
            prev = entry;
        }
        assertEquals("decoded pixel count", pixelCount, outPos);
        return out;
    }

    private static void resetDict(List<byte[]> dict, int clear) {
        dict.clear();
        for (int i = 0; i < clear; i++) dict.add(new byte[]{(byte) i});
        dict.add(null);
        dict.add(null);
    }

    private static byte[] concat(byte[] a, byte b) {
        byte[] r = new byte[a.length + 1];
        System.arraycopy(a, 0, r, 0, a.length);
        r[a.length] = b;
        return r;
    }

    @Test
    public void lzwRoundTripsLargeNoisyAndFlatImages() {
        Random rnd = new Random(42);
        int n = 640 * 360;
        byte[][] cases = new byte[3][n];
        for (int i = 0; i < n; i++) {
            cases[0][i] = (byte) rnd.nextInt(256);        // forces many table resets
            cases[1][i] = 7;                              // long runs
            cases[2][i] = (byte) ((i / 37) % 23);         // stripes
        }
        for (byte[] px : cases) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                new GifEncoder.Lzw(px).encode(bos);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            byte[] enc = bos.toByteArray();
            byte[] dec = decodeImage(enc, new int[]{0}, n);
            assertArrayEquals(px, dec);
        }
    }

    @Test
    public void writesValidAnimatedGif() throws Exception {
        int w = 64, h = 36;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GifEncoder enc = new GifEncoder(bos, w, h);
        int[] px = new int[w * h];
        for (int f = 0; f < 3; f++) {
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    px[y * w + x] = 0xFF000000 | ((x * 4 + f * 40) & 0xFF) << 16 | (y * 7 & 0xFF) << 8 | 0x80;
            enc.addFrame(px, 1000.0 / 15);
        }
        enc.finish();
        byte[] g = bos.toByteArray();
        assertEquals("GIF89a", new String(g, 0, 6, "US-ASCII"));
        assertEquals(w, (g[6] & 0xFF) | (g[7] & 0xFF) << 8);
        assertEquals(h, (g[8] & 0xFF) | (g[9] & 0xFF) << 8);
        assertEquals(0x3B, g[g.length - 1] & 0xFF);
        assertTrue(new String(g, "ISO-8859-1").contains("NETSCAPE2.0"));

        // Walk the blocks: count frames, decode each, sum delays (15 fps → 7+7+6 cs ≈ 0.2 s).
        int pos = 13, frames = 0, delaySum = 0;
        while ((g[pos] & 0xFF) != 0x3B) {
            int b = g[pos] & 0xFF;
            if (b == 0x21) {
                int label = g[pos + 1] & 0xFF;
                if (label == 0xF9) delaySum += (g[pos + 4] & 0xFF) | (g[pos + 5] & 0xFF) << 8;
                pos += 2;
                while (true) {
                    int len = g[pos++] & 0xFF;
                    if (len == 0) break;
                    pos += len;
                }
            } else if (b == 0x2C) {
                assertEquals(0x87, g[pos + 9] & 0xFF);     // local 256-colour table
                pos += 10 + 256 * 3;
                int[] ref = {pos};
                decodeImage(g, ref, w * h);
                pos = ref[0];
                frames++;
            } else {
                throw new AssertionError("unexpected block 0x" + Integer.toHexString(b) + " at " + pos);
            }
        }
        assertEquals(3, frames);
        assertEquals(20, delaySum);
    }

    @Test
    public void medianCutPaletteIsCloseToSource() {
        int w = 160, h = 90;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                px[y * w + x] = 0xFF000000 | (x * 255 / w) << 16 | (y * 255 / h) << 8 | ((x + y) * 255 / (w + h));
        int[] pal = new int[256];
        int size = GifEncoder.buildPalette(px, pal);
        assertTrue(size > 200 && size <= 256);
        GifEncoder enc = new GifEncoder(new ByteArrayOutputStream(), w, h);
        byte[] idx = enc.ditherToPalette(px, pal, size);
        double err = 0;
        for (int i = 0; i < px.length; i++) {
            int a = px[i], b = pal[idx[i] & 0xFF];
            err += Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                    + Math.abs((a & 255) - (b & 255));
        }
        double meanErr = err / (px.length * 3.0);
        assertTrue("mean channel error " + meanErr, meanErr < 12);
    }
}
