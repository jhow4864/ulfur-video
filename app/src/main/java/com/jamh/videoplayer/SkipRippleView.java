package com.jamh.videoplayer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * 1.7.0 double-tap skip feedback: the tapped third of the screen lights up behind a curved edge
 * (rounded towards the centre) and an Ulfur-accent ripple grows from the finger. Every further
 * tap restarts the ripple while the panel stays lit; the "+20s" counter sits on top of it.
 */
public class SkipRippleView extends View {

    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripple = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF oval = new RectF();
    private ValueAnimator rippleAnim;
    private ValueAnimator fadeAnim;
    private int side;          // -1 left, 1 right, 0 none
    private float cx, cy;
    private float rippleProgress = 1f;
    private float alpha;       // 0..1 for the whole panel

    public SkipRippleView(Context context) { this(context, null); }

    public SkipRippleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        shade.setColor(0x33FFFFFF);
        ripple.setColor(ContextCompat.getColor(context, R.color.accent));
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Side -1 = rewind (left), 1 = forward (right); x/y are in this view's coordinates. */
    public void ripple(int side, float x, float y) {
        if (side == 0) return;
        if (this.side != side) rippleProgress = 1f;
        this.side = side;
        cx = x;
        cy = y;
        if (fadeAnim != null) fadeAnim.cancel();
        alpha = 1f;
        if (rippleAnim != null) rippleAnim.cancel();
        rippleAnim = ValueAnimator.ofFloat(0f, 1f);
        rippleAnim.setDuration(450);
        rippleAnim.setInterpolator(new DecelerateInterpolator());
        rippleAnim.addUpdateListener(a -> {
            rippleProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        rippleAnim.start();
        setVisibility(VISIBLE);
        invalidate();
    }

    /** Fades the lit panel out (called when the skip counter disappears). */
    public void fadeOut() {
        if (side == 0) return;
        if (fadeAnim != null) fadeAnim.cancel();
        fadeAnim = ValueAnimator.ofFloat(alpha, 0f);
        fadeAnim.setDuration(250);
        fadeAnim.addUpdateListener(a -> {
            alpha = (float) a.getAnimatedValue();
            if (alpha <= 0f) {
                side = 0;
                setVisibility(GONE);
            }
            invalidate();
        });
        fadeAnim.start();
    }

    public int activeSide() {
        return side;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (side == 0 || alpha <= 0f) return;
        int w = getWidth(), h = getHeight();
        float panelW = w * 0.38f;
        clip.reset();
        // Oval whose inner edge bulges towards the centre, like the panel in big video apps.
        if (side < 0) {
            oval.set(-panelW, -h * 0.25f, panelW, h * 1.25f);
        } else {
            oval.set(w - panelW, -h * 0.25f, w + panelW, h * 1.25f);
        }
        clip.addOval(oval, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);
        shade.setAlpha(Math.round(0x33 * alpha));
        canvas.drawRect(0, 0, w, h, shade);
        if (rippleProgress < 1f) {
            float r = panelW * 0.25f + panelW * 1.1f * rippleProgress;
            ripple.setAlpha(Math.round(110 * (1f - rippleProgress) * alpha));
            canvas.drawCircle(cx, cy, r, ripple);
        }
        canvas.restoreToCount(save);
    }
}
