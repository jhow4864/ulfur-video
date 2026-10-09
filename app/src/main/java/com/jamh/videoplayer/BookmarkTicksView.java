package com.jamh.videoplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * 1.6: bookmark tick marks drawn over the player's seek bar (not touchable; the seek bar under
 * it keeps all its gestures). Ticks line up with the seek bar's track, i.e. inside its padding.
 */
public class BookmarkTicksView extends View {

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private long[] times = new long[0];
    private long durationMs;
    @Nullable private SeekBar seekBar;
    private final float tickW, tickH, radius;

    public BookmarkTicksView(Context c) {
        this(c, null);
    }

    public BookmarkTicksView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        tickW = 3f * d;
        tickH = 12f * d;
        radius = 1.5f * d;
        shadow.setColor(0x99000000);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The seek bar whose track the ticks follow (its padding and size). */
    public void attachTo(@Nullable SeekBar sb) {
        seekBar = sb;
        invalidate();
    }

    public void setBookmarks(long[] timesMs, long durationMs) {
        this.times = timesMs == null ? new long[0] : timesMs.clone();
        this.durationMs = durationMs;
        invalidate();
    }

    public void setDuration(long durationMs) {
        if (durationMs == this.durationMs) return;
        this.durationMs = durationMs;
        invalidate();
    }

    long[] times() {
        return times.clone();
    }

    /** Pure: x of a tick for {@code t} on a track from {@code left} to {@code right}; NaN if off the track. */
    static float tickX(long t, long durationMs, float left, float right) {
        if (durationMs <= 0 || t < 0 || t > durationMs || right <= left) return Float.NaN;
        return left + (right - left) * (t / (float) durationMs);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Blood Moon gradient across the bar, so a tick takes the accent colour of its position.
        tick.setShader(new LinearGradient(0, 0, Math.max(1, w), 0,
                new int[]{ContextCompat.getColor(getContext(), R.color.eye),
                        ContextCompat.getColor(getContext(), R.color.accent_mid),
                        ContextCompat.getColor(getContext(), R.color.accent_end)},
                null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (times.length == 0 || durationMs <= 0) return;
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        if (seekBar != null) {
            left = seekBar.getLeft() - getLeft() + seekBar.getPaddingLeft();
            right = seekBar.getRight() - getLeft() - seekBar.getPaddingRight();
        }
        float cy = getHeight() / 2f;
        for (long t : times) {
            float x = tickX(t, durationMs, left, right);
            if (Float.isNaN(x)) continue;
            r.set(x - tickW / 2f - 1, cy - tickH / 2f - 1, x + tickW / 2f + 1, cy + tickH / 2f + 1);
            c.drawRoundRect(r, radius + 1, radius + 1, shadow);
            r.set(x - tickW / 2f, cy - tickH / 2f, x + tickW / 2f, cy + tickH / 2f);
            c.drawRoundRect(r, radius, radius, tick);
        }
    }
}
