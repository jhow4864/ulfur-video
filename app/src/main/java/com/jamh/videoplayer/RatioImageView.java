package com.jamh.videoplayer;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/** ImageView that keeps an aspect ratio (default 16:9) based on its width. */
public class RatioImageView extends AppCompatImageView {
    private float heightOverWidth = 9f / 16f;

    public void setRatio(float heightOverWidth) {
        this.heightOverWidth = heightOverWidth;
        requestLayout();
    }

    public RatioImageView(Context context) { super(context); }
    public RatioImageView(Context context, AttributeSet attrs) { super(context, attrs); }
    public RatioImageView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = Math.round(w * heightOverWidth);
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
