package com.jamh.videoplayer;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.ScrubbingModeParameters;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.extractor.DefaultExtractorsFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the ExoPlayer instance with anti-stutter settings:
 * large buffers, a 30 s back buffer, extension (FFmpeg) renderers preferred and decoder fallback.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerFactory {

    // Requested buffer profile (network streams; local files when it fits the memory budget).
    static final int MIN_BUFFER_MS = 30_000;
    static final int MAX_BUFFER_MS = 120_000;
    static final int BUFFER_FOR_PLAYBACK_MS = 1_500;
    static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000;
    static final int BACK_BUFFER_MS = 30_000;
    /**
     * 1.4.2: local files (gallery and private videos) are read from flash at hundreds of MB/s, so there is no need
     * to buffer 1.5 s / 3 s of media before playing after a seek. Playback restarts as soon as 0.25 s / 0.5 s is ready.
     */
    static final int LOCAL_BUFFER_FOR_PLAYBACK_MS = 250;
    static final int LOCAL_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500;

    /**
     * Local-playback buffer tiers, chosen from the file's bitrate so that forward + back buffer
     * fit the heap budget: {min, max, back} in ms. Tier 0 is the full anti-stutter profile.
     * The allocator lives on the Java heap, so 30 s back + 120 s forward of 80 Mbit/s 4K60 footage
     * (~1.5 GB) is impossible; local reads are near-instant, so less buffer costs nothing.
     */
    static final int[][] LOCAL_TIERS = {
            {30_000, 120_000, 30_000},   // 0: needs 150 s of budget (e.g. ≤ 6.8 Mbit/s with 128 MB)
            {20_000, 60_000, 15_000},    // 1: needs 75 s
            {10_000, 30_000, 10_000},    // 2: needs 40 s  (typical 1080p/4K30 phone video)
            {5_000, 15_000, 3_000},      // 3: very high or unknown bitrate; the byte cap does the rest
    };
    static final int TIER_NETWORK = 0;
    static final int TIER_UNKNOWN = 3;

    private PlayerFactory() {}

    /** Heap budget for the whole media buffer (forward + back): maxMemory / 4, 48..160 MB. */
    static int bufferBudgetBytes() {
        long q = Runtime.getRuntime().maxMemory() / 4;
        return (int) Math.max(48L << 20, Math.min(160L << 20, q));
    }

    /** Picks the largest local tier whose forward + back duration fits the budget at this bitrate. */
    static int tierForBitrate(long bitrateBps, long budgetBytes) {
        if (bitrateBps <= 0) return TIER_UNKNOWN;
        double seconds = budgetBytes * 8.0 / bitrateBps;
        for (int t = 0; t < LOCAL_TIERS.length - 1; t++) {
            if (seconds >= (LOCAL_TIERS[t][1] + LOCAL_TIERS[t][2]) / 1000.0) return t;
        }
        return LOCAL_TIERS.length - 1;
    }

    public static ExoPlayer create(Context context, boolean preferSoftwareVideo, int localTier) {
        return create(context, preferSoftwareVideo, localTier, null);
    }

    public static ExoPlayer create(Context context, boolean preferSoftwareVideo, int localTier,
                                   DialogueBoostProcessor dialogueBoost) {
        int[] t = LOCAL_TIERS[Math.max(0, Math.min(LOCAL_TIERS.length - 1, localTier))];

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(MIN_BUFFER_MS, MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setBufferDurationsMsForLocalPlayback(t[0], t[1],
                        LOCAL_BUFFER_FOR_PLAYBACK_MS, LOCAL_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
                // Local: the byte cap always wins, so a mis-probed or unknown bitrate can never
                // fill the heap (the back buffer counts toward the cap as well).
                .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false)
                .setTargetBufferBytes(bufferBudgetBytes())
                // Keep some seconds behind the playhead (from the previous keyframe) so short
                // rewinds and backwards scrubbing are served from memory instead of reloading.
                .setBackBuffer(localTier == TIER_NETWORK ? BACK_BUFFER_MS : t[2],
                        /* retainBackBufferFromKeyframe= */ true)
                .build();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
            @Override
            protected void buildVideoRenderers(Context ctx, int extensionRendererMode,
                    MediaCodecSelector selector, boolean enableDecoderFallback, Handler handler,
                    VideoRendererEventListener listener, long allowedVideoJoiningTimeMs,
                    ArrayList<Renderer> out) {
                // PREFER applies to audio (FFmpeg audio first). For video the hardware MediaCodec
                // decoders stay first; the experimental FFmpeg video renderer bundled in the
                // extension is only appended as a last-resort software fallback.
                int mode = extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER
                        ? EXTENSION_RENDERER_MODE_ON : extensionRendererMode;
                super.buildVideoRenderers(ctx, mode, selector, enableDecoderFallback, handler,
                        listener, allowedVideoJoiningTimeMs, out);
            }

            @Override
            protected AudioSink buildAudioSink(Context ctx, boolean enableFloatOutput,
                                               boolean enableAudioTrackPlaybackParams) {
                DefaultAudioSink.Builder b = new DefaultAudioSink.Builder(ctx)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams);
                if (dialogueBoost != null) b.setAudioProcessors(new AudioProcessor[]{dialogueBoost});
                return b.build();
            }
        }
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecSelector(preferSoftwareVideo));

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("UlfurVideo/1.5.0 (Android) Media3")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, http);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true);
        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(dataSourceFactory, extractors);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();

        ExoPlayer player = new ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        player.setSeekParameters(SeekParameters.EXACT);
        // Scrubbing mode: keep audio selected (only metadata disabled) so that the buffered audio
        // is not thrown away and re-loaded when the drag ends -> release is instant.
        player.setScrubbingModeParameters(new ScrubbingModeParameters.Builder()
                .setDisabledTrackTypes(Collections.singleton(C.TRACK_TYPE_METADATA))
                .build());
        return player;
    }

    /**
     * Lightweight player for the vertical clips feed: default (small) local buffers so two
     * instances can coexist, looping, hardware decoders first.
     */
    public static ExoPlayer createLight(Context context) {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMsForLocalPlayback(5_000, 20_000, 500, 1_000)
                .setBackBuffer(5_000, true)
                .build();
        DefaultRenderersFactory rf = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true);
        ExoPlayer player = new ExoPlayer.Builder(context, rf)
                .setLoadControl(loadControl)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
        return player;
    }

    /** Optionally moves software (c2.android / OMX.google) video decoders to the front. */
    private static MediaCodecSelector codecSelector(boolean preferSoftwareVideo) {
        return (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            List<MediaCodecInfo> infos = MediaCodecUtil.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (!preferSoftwareVideo || !MimeTypes.isVideo(mimeType) || requiresSecureDecoder) {
                return infos;
            }
            List<MediaCodecInfo> sw = new ArrayList<>();
            List<MediaCodecInfo> hw = new ArrayList<>();
            for (MediaCodecInfo info : infos) (info.softwareOnly ? sw : hw).add(info);
            sw.addAll(hw);
            return sw;
        };
    }
}
