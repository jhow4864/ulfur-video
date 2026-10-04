package com.jamh.videoplayer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Small animated-GIF89a encoder.
 * <ul>
 *   <li>Per-frame 256-colour palette by median cut over a 15-bit (5:5:5) colour histogram.</li>
 *   <li>Floyd–Steinberg error-diffusion dithering (lazy nearest-colour lookup table).</li>
 *   <li>Variable-width LZW compression (classic GIF semantics, 12-bit max, clear on table full).</li>
 *   <li>NETSCAPE2.0 loop extension (loop forever).</li>
 * </ul>
 * Pixels are supplied as ARGB ints (alpha ignored), e.g. from {@code Bitmap.getPixels}.
 */
public final class GifEncoder {

    private final OutputStream out;
    private final int width, height;
    private boolean started;
    private double delayRemainderCs;

    public GifEncoder(OutputStream out, int width, int height) {
        this.out = out;
        this.width = width;
        this.height = height;
    }

    /** Adds a frame shown for {@code durationMs}. */
    public void addFrame(int[] argb, double durationMs) throws IOException {
        if (argb.length < width * height) throw new IllegalArgumentException("pixel array too small");
        if (!started) {
            writeHeader();
            started = true;
        }
        // Delays are in 1/100 s: carry the rounding remainder so 15 fps averages 6.67 cs.
        double exact = durationMs / 10.0 + delayRemainderCs;
        int delayCs = Math.max(2, (int) Math.round(exact));
        delayRemainderCs = exact - delayCs;

        int[] palette = new int[256];
        int paletteSize = buildPalette(argb, palette);
        byte[] indices = ditherToPalette(argb, palette, paletteSize);

        // Graphic Control Extension
        out.write(0x21); out.write(0xF9); out.write(4);
        out.write(0x04);             // disposal: do not dispose, no transparency
        writeShort(delayCs);
        out.write(0); out.write(0);
        // Image Descriptor with 256-entry local colour table
        out.write(0x2C);
        writeShort(0); writeShort(0); writeShort(width); writeShort(height);
        out.write(0x80 | 0x07);
        for (int i = 0; i < 256; i++) {
            int c = i < paletteSize ? palette[i] : 0;
            out.write((c >> 16) & 0xFF); out.write((c >> 8) & 0xFF); out.write(c & 0xFF);
        }
        new Lzw(indices).encode(out);
    }

    public void finish() throws IOException {
        if (!started) {
            writeHeader();
            started = true;
        }
        out.write(0x3B);
        out.flush();
    }

    private void writeHeader() throws IOException {
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        writeShort(width);
        writeShort(height);
        out.write(0x70);  // no global colour table, 8-bit colour resolution
        out.write(0);     // background colour index
        out.write(0);     // pixel aspect ratio
        // NETSCAPE2.0: loop forever
        out.write(0x21); out.write(0xFF); out.write(11);
        out.write(new byte[]{'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0'});
        out.write(3); out.write(1); writeShort(0); out.write(0);
    }

    private void writeShort(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    // ------------------------------------------------------------------ palette (median cut)

    private static int key15(int rgb) {
        return ((rgb >> 9) & 0x7C00) | ((rgb >> 6) & 0x03E0) | ((rgb >> 3) & 0x001F);
    }

    /** Builds up to 256 colours; returns the palette size. */
    static int buildPalette(int[] argb, int[] paletteOut) {
        int[] hist = new int[32768];
        for (int p : argb) hist[key15(p)]++;
        int used = 0;
        for (int h : hist) if (h > 0) used++;
        int[] colors = new int[used];
        int n = 0;
        for (int i = 0; i < 32768; i++) if (hist[i] > 0) colors[n++] = i;

        if (used <= 256) {
            for (int i = 0; i < used; i++) paletteOut[i] = expand(colors[i]);
            return used;
        }
        int[] boxStart = new int[256], boxEnd = new int[256];
        boxStart[0] = 0; boxEnd[0] = used;
        int boxes = 1;
        int[] tmp = new int[used];
        while (boxes < 256) {
            // Pick the box with the largest (population × widest channel range) that can be split.
            int best = -1; long bestScore = -1; int bestAxis = 0;
            for (int b = 0; b < boxes; b++) {
                if (boxEnd[b] - boxStart[b] < 2) continue;
                int[] range = channelRanges(colors, boxStart[b], boxEnd[b]);
                int axis = range[0] >= range[1] && range[0] >= range[2] ? 0 : range[1] >= range[2] ? 1 : 2;
                long pop = 0;
                for (int i = boxStart[b]; i < boxEnd[b]; i++) pop += hist[colors[i]];
                long score = pop * (long) (range[axis] + 1);
                if (score > bestScore) { bestScore = score; best = b; bestAxis = axis; }
            }
            if (best < 0) break;
            int s = boxStart[best], e = boxEnd[best];
            // Sort the box along the chosen axis (pack channel value into the high bits).
            int shift = bestAxis == 0 ? 10 : bestAxis == 1 ? 5 : 0;
            for (int i = s; i < e; i++) tmp[i] = (((colors[i] >> shift) & 31) << 15) | colors[i];
            Arrays.sort(tmp, s, e);
            for (int i = s; i < e; i++) colors[i] = tmp[i] & 0x7FFF;
            // Split at the population median.
            long total = 0;
            for (int i = s; i < e; i++) total += hist[colors[i]];
            long acc = 0; int mid = s + 1;
            for (int i = s; i < e - 1; i++) {
                acc += hist[colors[i]];
                if (acc * 2 >= total) { mid = i + 1; break; }
                mid = i + 2;
            }
            mid = Math.max(s + 1, Math.min(e - 1, mid));
            boxEnd[best] = mid;
            boxStart[boxes] = mid; boxEnd[boxes] = e;
            boxes++;
        }
        for (int b = 0; b < boxes; b++) {
            long r = 0, g = 0, bl = 0, pop = 0;
            for (int i = boxStart[b]; i < boxEnd[b]; i++) {
                int c = colors[i]; long w = hist[c];
                r += w * ((c >> 10) & 31); g += w * ((c >> 5) & 31); bl += w * (c & 31); pop += w;
            }
            if (pop == 0) pop = 1;
            int rr = (int) (r * 255 / (31 * pop)), gg = (int) (g * 255 / (31 * pop)), bb = (int) (bl * 255 / (31 * pop));
            paletteOut[b] = (rr << 16) | (gg << 8) | bb;
        }
        return boxes;
    }

    private static int[] channelRanges(int[] colors, int s, int e) {
        int rMin = 31, rMax = 0, gMin = 31, gMax = 0, bMin = 31, bMax = 0;
        for (int i = s; i < e; i++) {
            int c = colors[i];
            int r = (c >> 10) & 31, g = (c >> 5) & 31, b = c & 31;
            if (r < rMin) rMin = r; if (r > rMax) rMax = r;
            if (g < gMin) gMin = g; if (g > gMax) gMax = g;
            if (b < bMin) bMin = b; if (b > bMax) bMax = b;
        }
        return new int[]{rMax - rMin, gMax - gMin, bMax - bMin};
    }

    private static int expand(int c15) {
        int r = (c15 >> 10) & 31, g = (c15 >> 5) & 31, b = c15 & 31;
        r = (r << 3) | (r >> 2); g = (g << 3) | (g >> 2); b = (b << 3) | (b >> 2);
        return (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ dithering

    byte[] ditherToPalette(int[] argb, int[] palette, int size) {
        int w = width, h = height;
        byte[] out = new byte[w * h];
        short[] lut = new short[32768];
        Arrays.fill(lut, (short) -1);
        int[] errCur = new int[(w + 2) * 3], errNext = new int[(w + 2) * 3];
        for (int y = 0; y < h; y++) {
            Arrays.fill(errNext, 0);
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                int e = (x + 1) * 3;
                int r = clamp(((p >> 16) & 0xFF) + (errCur[e] >> 4));
                int g = clamp(((p >> 8) & 0xFF) + (errCur[e + 1] >> 4));
                int b = clamp((p & 0xFF) + (errCur[e + 2] >> 4));
                int k = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
                int idx = lut[k];
                if (idx < 0) {
                    idx = nearest(palette, size, r, g, b);
                    lut[k] = (short) idx;
                }
                out[y * w + x] = (byte) idx;
                int c = palette[idx];
                int er = r - ((c >> 16) & 0xFF), eg = g - ((c >> 8) & 0xFF), eb = b - (c & 0xFF);
                // 7/16 right, 3/16 down-left, 5/16 down, 1/16 down-right (errors kept ×16)
                errCur[e + 3] += er * 7; errCur[e + 4] += eg * 7; errCur[e + 5] += eb * 7;
                errNext[e - 3] += er * 3; errNext[e - 2] += eg * 3; errNext[e - 1] += eb * 3;
                errNext[e] += er * 5; errNext[e + 1] += eg * 5; errNext[e + 2] += eb * 5;
                errNext[e + 3] += er; errNext[e + 4] += eg; errNext[e + 5] += eb;
            }
            int[] t = errCur; errCur = errNext; errNext = t;
        }
        return out;
    }

    private static int nearest(int[] pal, int size, int r, int g, int b) {
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            int c = pal[i];
            int dr = r - ((c >> 16) & 0xFF), dg = g - ((c >> 8) & 0xFF), db = b - (c & 0xFF);
            int d = 2 * dr * dr + 4 * dg * dg + 3 * db * db; // perceptual-ish weights
            if (d < bestD) { bestD = d; best = i; if (d == 0) break; }
        }
        return best;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    // ------------------------------------------------------------------ LZW

    /** GIF LZW encoder (min code size 8). */
    static final class Lzw {
        private static final int BITS = 12, MAX_CODE = 1 << BITS, HSIZE = 8192;
        private final byte[] pixels;
        private final int[] htab = new int[HSIZE];
        private final int[] codetab = new int[HSIZE];
        private int nBits, maxCode, freeEnt;
        private boolean clearFlag;
        private final int initBits = 9, clearCode = 256, eofCode = 257;
        private int curAccum, curBits;
        private final byte[] packet = new byte[256];
        private int packetLen;
        private OutputStream os;

        Lzw(byte[] pixels) {
            this.pixels = pixels;
        }

        void encode(OutputStream os) throws IOException {
            this.os = os;
            os.write(8); // LZW minimum code size
            nBits = initBits;
            maxCode = (1 << nBits) - 1;
            freeEnt = clearCode + 2;
            clearFlag = false;
            Arrays.fill(htab, -1);
            output(clearCode);
            int ent = pixels.length > 0 ? pixels[0] & 0xFF : 0;
            for (int i = 1; i < pixels.length; i++) {
                int c = pixels[i] & 0xFF;
                int fcode = (c << BITS) + ent;
                int h = ((c << 5) ^ ent ^ (ent >> 7)) & (HSIZE - 1);
                boolean found = false;
                while (htab[h] >= 0) {
                    if (htab[h] == fcode) { ent = codetab[h]; found = true; break; }
                    h = (h + 1) & (HSIZE - 1);
                }
                if (found) continue;
                output(ent);
                ent = c;
                if (freeEnt < MAX_CODE) {
                    codetab[h] = freeEnt++;
                    htab[h] = fcode;
                } else {
                    // Table full: emit clear code and start over.
                    Arrays.fill(htab, -1);
                    freeEnt = clearCode + 2;
                    clearFlag = true;
                    output(clearCode);
                }
            }
            output(ent);
            output(eofCode);
            os.write(0); // block terminator
        }

        private void output(int code) throws IOException {
            curAccum |= code << curBits;
            curBits += nBits;
            while (curBits >= 8) {
                addByte(curAccum & 0xFF);
                curAccum >>>= 8;
                curBits -= 8;
            }
            if (freeEnt > maxCode || clearFlag) {
                if (clearFlag) {
                    nBits = initBits;
                    maxCode = (1 << nBits) - 1;
                    clearFlag = false;
                } else {
                    nBits++;
                    maxCode = nBits == BITS ? MAX_CODE : (1 << nBits) - 1;
                }
            }
            if (code == eofCode) {
                while (curBits > 0) {
                    addByte(curAccum & 0xFF);
                    curAccum >>>= 8;
                    curBits -= 8;
                }
                curBits = 0;
                flushPacket();
            }
        }

        private void addByte(int b) throws IOException {
            packet[packetLen++] = (byte) b;
            if (packetLen >= 255) flushPacket();
        }

        private void flushPacket() throws IOException {
            if (packetLen > 0) {
                os.write(packetLen);
                os.write(packet, 0, packetLen);
                packetLen = 0;
            }
        }
    }
}
