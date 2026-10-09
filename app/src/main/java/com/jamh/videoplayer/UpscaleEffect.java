package com.jamh.videoplayer;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/**
 * 1.5.0: Media3 video effect that upscales (FSR 1.0 EASU, or Catmull-Rom in Lite quality) to the
 * size of the surface the video is drawn into, then sharpens with RCAS. Applied live through
 * {@code ExoPlayer.setVideoEffects}; nothing is written to disk.
 *
 * <p>Instances are immutable: when the surface size or mode changes the player gets a new effect.
 */
@OptIn(markerClass = UnstableApi.class)
final class UpscaleEffect implements GlEffect {

    final int mode;
    final int quality;
    final int boxWidth;
    final int boxHeight;

    UpscaleEffect(int mode, int quality, int boxWidth, int boxHeight) {
        this.mode = Upscaler.sanitizeMode(mode);
        this.quality = quality;
        this.boxWidth = boxWidth;
        this.boxHeight = boxHeight;
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
            throws VideoFrameProcessingException {
        return new UpscaleShaderProgram(context, useHdr, this);
    }
}
