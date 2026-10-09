package com.jamh.videoplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.Crop;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exports a trimmed clip either as MP4 (Media3 Transformer, hardware MediaCodec encode) or as an
 * animated GIF (MediaMetadataRetriever OPTION_CLOSEST frames + {@link GifEncoder}).
 * Results are saved through {@link MediaSaver}. All callbacks arrive on the main thread.
 */
@OptIn(markerClass = UnstableApi.class)
final class ClipExporter {

    static final int TEXT_TOP = 0, TEXT_CENTER = 1, TEXT_BOTTOM = 2;

    /** Everything the editor chose. Times in ms; crop is normalised (0..1, y down) or null. */
    static final class Spec {
        Uri uri;
        String title = "clip";
        long startMs, endMs;
        @Nullable RectF crop;
        String text = "";
        int textPosition = TEXT_BOTTOM;
        /** Text height as a fraction of the output frame height. */
        float textSize = 0.08f;
        int textColor = Color.WHITE;
        boolean gif;
        boolean keepAudio = true;
        int gifWidth = 480;
        int gifFps = 15;
        /** Display-oriented source size if the editor already knows it (0 = probe on a worker). */
        int srcW, srcH;

        long durationMs() {
            return Math.max(0, endMs - startMs);
        }

        boolean hasText() {
            return text != null && !text.trim().isEmpty();
        }
    }

    interface Callback {
        void onProgress(int percent);

        void onDone(Uri uri, String mime, long bytes);

        void onError(String message);

        void onCancelled();
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    @Nullable private Transformer transformer;
    @Nullable private Thread worker;
    private final Runnable progressPoll = new Runnable() {
        @Override
        public void run() {
            if (transformer == null || cancelled.get()) return;
            ProgressHolder h = new ProgressHolder();
            if (transformer.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE && callback != null) {
                callback.onProgress(Math.min(95, h.progress)); // last 5 % = copying into MediaStore
            }
            main.postDelayed(this, 200);
        }
    };
    @Nullable private Callback callback;

    ClipExporter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    void cancel() {
        cancelled.set(true);
        main.removeCallbacks(progressPoll);
        if (transformer != null) {
            transformer.cancel();
            transformer = null;
        }
        if (worker != null) worker.interrupt();
        Callback cb = callback;
        callback = null;
        if (cb != null) cb.onCancelled();
    }

    void start(Spec spec, Callback cb) {
        callback = cb;
        cancelled.set(false);
        if (spec.gif) startGif(spec);
        else startMp4(spec);
    }

    // ----------------------------------------------------------------------------- MP4

    private void startMp4(Spec spec) {
        if (spec.srcW > 0 && spec.srcH > 0) {
            startMp4(spec, new int[]{spec.srcW, spec.srcH});
            return;
        }
        // MediaMetadataRetriever does provider I/O (possibly a cloud download): never on main.
        worker = new Thread(() -> {
            int[] size = displaySize(ctx, spec.uri);
            main.post(() -> {
                if (!cancelled.get() && callback != null) startMp4(spec, size);
            });
        }, "clip-probe");
        worker.start();
    }

    private void startMp4(Spec spec, int[] size) {
        final File out = exportFile("mp4");
        List<Effect> effects = new ArrayList<>();
        int outW = size[0], outH = size[1];
        if (spec.crop != null) {
            RectF c = spec.crop;
            // NDC: x in [-1, 1] left→right, y in [-1, 1] bottom→top.
            effects.add(new Crop(-1f + 2f * c.left, -1f + 2f * c.right, 1f - 2f * c.bottom, 1f - 2f * c.top));
            if (outW > 0 && outH > 0) {
                outW = Math.round(outW * c.width());
                outH = Math.round(outH * c.height());
            }
        }
        if (outW > 0 && outH > 0) {
            // Many hardware encoders reject odd dimensions.
            outW = Math.max(2, outW & ~1);
            outH = Math.max(2, outH & ~1);
            if (spec.crop != null) {
                effects.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            }
            if (spec.hasText()) {
                Bitmap textLayer = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                drawText(new Canvas(textLayer), outW, outH, spec);
                StaticOverlaySettings settings = new StaticOverlaySettings.Builder().build(); // centred, 1:1
                TextureOverlay overlay = BitmapOverlay.createStaticBitmapOverlay(textLayer, settings);
                effects.add(new OverlayEffect(ImmutableList.of(overlay)));
            }
        }

        MediaItem item = new MediaItem.Builder()
                .setUri(spec.uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(spec.startMs)
                        .setEndPositionMs(spec.endMs)
                        .build())
                .build();
        EditedMediaItem edited = new EditedMediaItem.Builder(item)
                .setRemoveAudio(!spec.keepAudio)
                .setEffects(new Effects(Collections.emptyList(), effects))
                .build();
        // H.264 HDR encoders are rare: tone-map HDR (e.g. Pixel 10-bit HLG) to SDR explicitly so
        // the clip is shareable everywhere instead of depending on encoder fallback.
        Composition composition = new Composition.Builder(new EditedMediaItemSequence.Builder(edited).build())
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build();

        transformer = new Transformer.Builder(ctx)
                // 1.6.0: reads Private (encrypted) videos through VaultDataSource
                .setAssetLoaderFactory(new androidx.media3.transformer.DefaultAssetLoaderFactory(ctx,
                        new androidx.media3.transformer.DefaultDecoderFactory.Builder(ctx).build(),
                        androidx.media3.common.util.Clock.DEFAULT,
                        VaultDataSource.mediaSourceFactory(ctx),
                        new androidx.media3.datasource.DataSourceBitmapLoader(ctx)))
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        main.removeCallbacks(progressPoll);
                        transformer = null;
                        if (cancelled.get()) return;
                        saveInBackground(out, fileName(spec, "mp4"), MimeTypes.VIDEO_MP4);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException e) {
                        main.removeCallbacks(progressPoll);
                        transformer = null;
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        if (cancelled.get()) return;
                        fail("Export failed: " + e.getErrorCodeName());
                    }
                })
                .build();
        transformer.start(composition, out.getAbsolutePath());
        main.post(progressPoll);
    }

    // ----------------------------------------------------------------------------- GIF

    private void startGif(Spec spec) {
        worker = new Thread(() -> {
            File out = exportFile("gif");
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            java.io.Closeable mmrSource = null;
            try {
                mmrSource = VaultMediaDataSource.setDataSource(ctx, mmr, spec.uri);
                int[] size = displaySize(mmr);
                if (size[0] <= 0 || size[1] <= 0) throw new IOException("Unknown video size");
                RectF crop = spec.crop != null ? spec.crop : new RectF(0, 0, 1, 1);
                float srcCropW = size[0] * crop.width(), srcCropH = size[1] * crop.height();
                int outW = Math.min(spec.gifWidth, Math.max(2, Math.round(srcCropW)));
                int outH = Math.max(2, Math.round(outW * srcCropH / srcCropW));
                // Ask the retriever for a frame just big enough for the cropped region.
                float scale = Math.min(1f, outW / srcCropW);
                int reqW = Math.max(2, Math.round(size[0] * scale));
                int reqH = Math.max(2, Math.round(size[1] * scale));

                Bitmap textLayer = null;
                if (spec.hasText()) {
                    textLayer = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                    drawText(new Canvas(textLayer), outW, outH, spec);
                }
                Bitmap frame = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(frame);
                Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
                int[] pixels = new int[outW * outH];
                double frameMs = 1000.0 / spec.gifFps;
                int count = Math.max(1, (int) Math.round(spec.durationMs() / frameMs));

                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024)) {
                    GifEncoder enc = new GifEncoder(os, outW, outH);
                    Bitmap last = null;
                    for (int i = 0; i < count; i++) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException();
                        }
                        long tUs = Math.round((spec.startMs + i * frameMs) * 1000.0);
                        Bitmap src = Build.VERSION.SDK_INT >= 27
                                ? mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, reqW, reqH)
                                : mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST);
                        if (src == null) src = last;   // rare decoder hiccup: repeat previous frame
                        if (src == null) continue;
                        Rect from = new Rect(
                                Math.round(crop.left * src.getWidth()), Math.round(crop.top * src.getHeight()),
                                Math.round(crop.right * src.getWidth()), Math.round(crop.bottom * src.getHeight()));
                        canvas.drawColor(Color.BLACK);
                        canvas.drawBitmap(src, from, new Rect(0, 0, outW, outH), filter);
                        if (textLayer != null) canvas.drawBitmap(textLayer, 0, 0, null);
                        frame.getPixels(pixels, 0, outW, 0, 0, outW, outH);
                        enc.addFrame(pixels, frameMs);
                        if (last != null && last != src) last.recycle();
                        last = src;
                        final int pct = (int) (95L * (i + 1) / count);
                        main.post(() -> { if (callback != null) callback.onProgress(pct); });
                    }
                    enc.finish();
                }
                saveBlocking(out, fileName(spec, "gif"), "image/gif");
            } catch (InterruptedException e) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            } catch (Exception | OutOfMemoryError e) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                if (!cancelled.get()) fail("GIF export failed: " + e.getMessage());
            } finally {
                try {
                    mmr.release();
                } catch (Exception ignored) {
                }
                VaultMediaDataSource.closeQuietly(mmrSource);
            }
        }, "gif-export");
        worker.start();
    }

    // ----------------------------------------------------------------------------- shared

    private void saveInBackground(File file, String name, String mime) {
        worker = new Thread(() -> saveBlocking(file, name, mime), "clip-save");
        worker.start();
    }

    private void saveBlocking(File file, String name, String mime) {
        try {
            if (cancelled.get()) return;
            long bytes = file.length();
            Uri uri = MediaSaver.save(ctx, file, name, mime);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            main.post(() -> {
                Callback cb = callback;
                callback = null;
                if (cb != null && !cancelled.get()) cb.onDone(uri, mime, bytes);
            });
        } catch (IOException | RuntimeException e) {
            fail("Could not save: " + e.getMessage());
        }
    }

    private void fail(String msg) {
        main.post(() -> {
            Callback cb = callback;
            callback = null;
            if (cb != null) cb.onError(msg);
        });
    }

    private File exportFile(String ext) {
        File dir = new File(ctx.getCacheDir(), "exports");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "export_" + System.currentTimeMillis() + "." + ext);
    }

    static String fileName(Spec spec, String ext) {
        String base = spec.title == null ? "clip" : spec.title.replaceAll("\\.[A-Za-z0-9]{2,4}$", "");
        base = base.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (base.isEmpty()) base = "clip";
        if (base.length() > 40) base = base.substring(0, 40).trim();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return base + "_" + stamp + "." + ext;
    }

    /** Display-oriented width/height (rotation applied), or {0, 0} if unknown. */
    static int[] displaySize(Context ctx, Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        java.io.Closeable src = null;
        try {
            src = VaultMediaDataSource.setDataSource(ctx, mmr, uri);
            return displaySize(mmr);
        } catch (RuntimeException | IOException e) {
            return new int[]{0, 0};
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
            VaultMediaDataSource.closeQuietly(src);
        }
    }

    private static int[] displaySize(MediaMetadataRetriever mmr) {
        int w = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int h = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int rot = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        return (rot == 90 || rot == 270) ? new int[]{h, w} : new int[]{w, h};
    }

    private static int parse(@Nullable String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Draws the caption (bold, outlined for legibility) exactly where the editor preview shows it. */
    static void drawText(Canvas c, int w, int h, Spec spec) {
        if (!spec.hasText()) return;
        float px = Math.max(8f, spec.textSize * h);
        TextPaint fill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        fill.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        fill.setTextSize(px);
        fill.setColor(spec.textColor);
        TextPaint stroke = new TextPaint(fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, px * 0.12f));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.luminance(spec.textColor) > 0.4f ? 0xCC000000 : 0xCCFFFFFF);
        int margin = Math.round(w * 0.05f);
        int layoutW = Math.max(1, w - 2 * margin);
        CharSequence text = TextUtils.ellipsize(spec.text.trim(), fill, layoutW * 3f, TextUtils.TruncateAt.END);
        StaticLayout lf = layout(text, fill, layoutW);
        StaticLayout ls = layout(text, stroke, layoutW);
        float top;
        switch (spec.textPosition) {
            case TEXT_TOP: top = h * 0.06f; break;
            case TEXT_CENTER: top = (h - lf.getHeight()) / 2f; break;
            default: top = h - h * 0.06f - lf.getHeight(); break;
        }
        c.save();
        c.translate(margin, top);
        ls.draw(c);
        lf.draw(c);
        c.restore();
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout layout(CharSequence t, TextPaint p, int width) {
        if (Build.VERSION.SDK_INT >= 23) {
            return StaticLayout.Builder.obtain(t, 0, t.length(), p, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build();
        }
        return new StaticLayout(t, p, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
    }

    /** Rough output size estimate in bytes (for the editor's warning). */
    static long estimateBytes(Spec s, int srcW, int srcH) {
        double secs = s.durationMs() / 1000.0;
        if (srcW <= 0 || srcH <= 0) { srcW = 1920; srcH = 1080; }  // unknown: assume 1080p 16:9
        if (s.gif) {
            float cw = srcW * (s.crop != null ? s.crop.width() : 1f);
            float ch = srcH * (s.crop != null ? s.crop.height() : 1f);
            int w = Math.min(s.gifWidth, Math.max(2, Math.round(cw)));
            double h = w * ch / cw;
            // ~0.5 bytes/pixel/frame for dithered video content after LZW, + 768-byte palette per frame.
            return (long) (secs * s.gifFps * (w * h * 0.5 + 800));
        }
        long px = (long) Math.max(1, srcW) * Math.max(1, srcH);
        double mbps = px >= 1920L * 1080 ? 12 : px >= 1280L * 720 ? 6 : 2.5;
        if (s.crop != null) mbps *= Math.max(0.25, s.crop.width() * s.crop.height());
        return (long) (secs * (mbps * 1_000_000 / 8 + (s.keepAudio ? 16_000 : 0)));
    }
}
