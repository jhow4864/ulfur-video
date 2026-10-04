package com.jamh.videoplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Drawn on top of the editor's PlayerView (RESIZE_MODE_FIT). Shows the crop rectangle (dimmed
 * outside, rule-of-thirds grid, draggable corners and body) and a WYSIWYG preview of the caption,
 * rendered with the same code the exporter uses.
 */
public class ClipOverlayView extends View {

    public interface Listener {
        void onCropChanged(RectF normalizedCrop);
    }

    private float videoAspect = 16f / 9f;     // display width / height of the video
    private float cropAspect;                 // 0 = free; otherwise width/height in video pixels
    private boolean cropEnabled;
    private final RectF crop = new RectF(0, 0, 1, 1);  // normalised to the video content
    @Nullable private ClipExporter.Spec textSpec;
    @Nullable private Listener listener;

    private final RectF content = new RectF();
    private final RectF cropPx = new RectF();
    private final Paint dim = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dimPath = new Path();
    private final float density;

    private int dragMode = DRAG_NONE;
    private static final int DRAG_NONE = 0, DRAG_MOVE = 1, DRAG_TL = 2, DRAG_TR = 3, DRAG_BL = 4, DRAG_BR = 5;
    private float lastX, lastY;

    public ClipOverlayView(Context c) {
        this(c, null);
    }

    public ClipOverlayView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        dim.setColor(0x99000000);
        dimPath.setFillType(Path.FillType.EVEN_ODD);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * density);
        border.setColor(Color.WHITE);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(density);
        grid.setColor(0x80FFFFFF);
        handle.setColor(Color.WHITE);
    }

    public void setListener(@Nullable Listener l) {
        listener = l;
    }

    public void setVideoAspect(float aspect) {
        if (aspect > 0 && Math.abs(aspect - videoAspect) > 1e-3) {
            videoAspect = aspect;
            if (cropEnabled) applyAspect(true);
            invalidate();
        }
    }

    /** @param aspect -1 = crop off, 0 = free, otherwise output width/height. */
    public void setCropMode(float aspect) {
        cropEnabled = aspect >= 0;
        cropAspect = Math.max(0, aspect);
        if (cropEnabled) applyAspect(true);
        else crop.set(0, 0, 1, 1);
        invalidate();
        notifyCrop();
    }

    @Nullable
    public RectF getCrop() {
        return cropEnabled ? new RectF(crop) : null;
    }

    public void setTextSpec(@Nullable ClipExporter.Spec spec) {
        textSpec = spec;
        invalidate();
    }

    /** Largest centred rect with the requested aspect (or keeps size when free). */
    private void applyAspect(boolean recentre) {
        if (cropAspect <= 0) {
            if (recentre && crop.width() >= 0.999f && crop.height() >= 0.999f) crop.set(0.1f, 0.1f, 0.9f, 0.9f);
            return;
        }
        // Normalised width/height ratio that yields cropAspect in video pixels.
        float r = cropAspect / videoAspect;
        float w, h;
        if (r >= 1) { w = 1f; h = 1f / r; } else { h = 1f; w = r; }
        w *= 0.9f; h *= 0.9f;
        float cx = recentre ? 0.5f : crop.centerX(), cy = recentre ? 0.5f : crop.centerY();
        crop.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        clampInside();
    }

    private void clampInside() {
        float dx = 0, dy = 0;
        if (crop.left < 0) dx = -crop.left;
        if (crop.right > 1) dx = 1 - crop.right;
        if (crop.top < 0) dy = -crop.top;
        if (crop.bottom > 1) dy = 1 - crop.bottom;
        crop.offset(dx, dy);
    }

    private void computeContent() {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cw = w, ch = w / videoAspect;
        if (ch > h) { ch = h; cw = h * videoAspect; }
        content.set((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
        cropPx.set(content.left + crop.left * cw, content.top + crop.top * ch,
                content.left + crop.right * cw, content.top + crop.bottom * ch);
    }

    @Override
    protected void onDraw(Canvas c) {
        computeContent();
        RectF area = cropEnabled ? cropPx : content;
        if (textSpec != null && textSpec.hasText() && area.width() > 2 && area.height() > 2) {
            c.save();
            c.clipRect(area);
            c.translate(area.left, area.top);
            ClipExporter.drawText(c, Math.round(area.width()), Math.round(area.height()), textSpec);
            c.restore();
        }
        if (!cropEnabled) return;
        dimPath.reset();
        dimPath.addRect(content, Path.Direction.CW);
        dimPath.addRect(cropPx, Path.Direction.CW);
        c.drawPath(dimPath, dim);
        float tw = cropPx.width() / 3, th = cropPx.height() / 3;
        for (int i = 1; i <= 2; i++) {
            c.drawLine(cropPx.left + tw * i, cropPx.top, cropPx.left + tw * i, cropPx.bottom, grid);
            c.drawLine(cropPx.left, cropPx.top + th * i, cropPx.right, cropPx.top + th * i, grid);
        }
        c.drawRect(cropPx, border);
        float r = 6 * density;
        c.drawCircle(cropPx.left, cropPx.top, r, handle);
        c.drawCircle(cropPx.right, cropPx.top, r, handle);
        c.drawCircle(cropPx.left, cropPx.bottom, r, handle);
        c.drawCircle(cropPx.right, cropPx.bottom, r, handle);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!cropEnabled) return false;
        computeContent();
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float slop = 28 * density;
                if (near(x, y, cropPx.left, cropPx.top, slop)) dragMode = DRAG_TL;
                else if (near(x, y, cropPx.right, cropPx.top, slop)) dragMode = DRAG_TR;
                else if (near(x, y, cropPx.left, cropPx.bottom, slop)) dragMode = DRAG_BL;
                else if (near(x, y, cropPx.right, cropPx.bottom, slop)) dragMode = DRAG_BR;
                else if (cropPx.contains(x, y)) dragMode = DRAG_MOVE;
                else return false;
                lastX = x;
                lastY = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = (x - lastX) / content.width(), dy = (y - lastY) / content.height();
                lastX = x;
                lastY = y;
                drag(dx, dy);
                invalidate();
                notifyCrop();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = DRAG_NONE;
                return true;
        }
        return false;
    }

    private void drag(float dx, float dy) {
        final float min = 0.08f;
        if (dragMode == DRAG_MOVE) {
            crop.offset(dx, dy);
            clampInside();
            return;
        }
        boolean left = dragMode == DRAG_TL || dragMode == DRAG_BL;
        boolean top = dragMode == DRAG_TL || dragMode == DRAG_TR;
        float l = crop.left, t = crop.top, r = crop.right, b = crop.bottom;
        if (left) l = clamp(l + dx, 0, r - min); else r = clamp(r + dx, l + min, 1);
        if (cropAspect > 0) {
            // Height follows width to keep the ratio; anchor at the opposite edge.
            float ratio = cropAspect / videoAspect;
            float h = (r - l) / ratio;
            if (top) {
                t = b - h;
                if (t < 0) { t = 0; h = b; float w = h * ratio; if (left) l = r - w; else r = l + w; }
            } else {
                b = t + h;
                if (b > 1) { b = 1; h = 1 - t; float w = h * ratio; if (left) l = r - w; else r = l + w; }
            }
        } else {
            if (top) t = clamp(t + dy, 0, b - min); else b = clamp(b + dy, t + min, 1);
        }
        crop.set(l, t, r, b);
    }

    private static boolean near(float x, float y, float px, float py, float slop) {
        return Math.abs(x - px) <= slop && Math.abs(y - py) <= slop;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void notifyCrop() {
        if (listener != null) listener.onCropChanged(new RectF(crop));
    }
}
