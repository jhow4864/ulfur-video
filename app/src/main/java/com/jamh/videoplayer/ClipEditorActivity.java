package com.jamh.videoplayer;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.slider.Slider;

import java.util.List;

/**
 * "Make clip / GIF": trim range + frame nudges with a live looping preview, optional crop and
 * caption, MP4 (Transformer) or GIF output, progress with cancel, then Share / Open.
 */
@OptIn(markerClass = UnstableApi.class)
public class ClipEditorActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_POSITION_MS = "position_ms";
    public static final String EXTRA_DURATION_MS = "duration_ms";

    static final long MIN_CLIP_MS = 100;
    static final long GIF_WARN_MS = 15_000;
    private static final long DEFAULT_CLIP_MS = 5_000;

    private static final int[] TEXT_COLORS = {Color.WHITE, 0xFFFFD60A, 0xFF111111, 0xFFFF453A, 0xFF64D2FF, 0xFF30D158};
    private static final String[] TEXT_COLOR_NAMES = {"White", "Yellow", "Black", "Red", "Cyan", "Green"};

    private final ClipExporter.Spec spec = new ClipExporter.Spec();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable private ExoPlayer player;
    private PlayerView playerView;
    private ClipOverlayView overlay;
    private RangeSlider range;
    private TextView rangeText, positionText, estimateText, warningText;
    private ImageButton playButton;
    private MaterialButton exportButton;
    private View mp4Options, gifOptions;
    /** 1.8.0 "Upscale on export (2x)". */
    private View upscaleOptions;
    private MaterialSwitch upscaleSwitch;
    private TextView upscaleHint;
    @Nullable private RealEsrgan.Availability upscaleAvailability;

    private long durationMs;
    private boolean durationKnown;
    private boolean metadataLoaded;
    private final java.util.concurrent.ExecutorService metaExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** The trim slider shows a zoomed window around the selection so long videos stay precise. */
    private long winStart, winEnd;
    private TextView windowText;
    private int videoW, videoH;
    private boolean loopPlaying = true;
    private boolean sliderDragging;
    private float lastStartValue = -1;

    @Nullable private ClipExporter exporter;
    @Nullable private AlertDialog progressDialog;
    @Nullable private Runnable pendingExport;
    private final ActivityResultLauncher<String> storagePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Runnable r = pendingExport;
                pendingExport = null;
                if (r != null) r.run();   // MediaSaver falls back to app storage if refused
            });

    private final Runnable loopTick = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                long pos = player.getCurrentPosition();
                if (!sliderDragging && loopPlaying
                        && (pos >= spec.endMs || pos < spec.startMs - 250)) {
                    player.setSeekParameters(SeekParameters.EXACT);
                    player.seekTo(spec.startMs);
                }
                positionText.setText(Fmt.timePrecise(Math.max(0, pos)));
            }
            handler.postDelayed(this, 30);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clip_editor);
        View root = findViewById(R.id.editor_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        spec.uri = uri;
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        spec.title = title != null ? title : "clip";

        playerView = findViewById(R.id.editor_player);
        overlay = findViewById(R.id.editor_overlay);
        range = findViewById(R.id.editor_range);
        rangeText = findViewById(R.id.editor_range_text);
        windowText = findViewById(R.id.editor_window_text);
        positionText = findViewById(R.id.editor_position);
        estimateText = findViewById(R.id.editor_estimate);
        warningText = findViewById(R.id.editor_warning);
        playButton = findViewById(R.id.editor_play);
        exportButton = findViewById(R.id.editor_export);
        mp4Options = findViewById(R.id.mp4_options);
        gifOptions = findViewById(R.id.gif_options);
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.editor_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setSubtitle(spec.title);

        durationMs = getIntent().getLongExtra(EXTRA_DURATION_MS, 0);
        durationKnown = durationMs > 0;
        if (durationMs <= 0) durationMs = 10_000;   // placeholder until metadata / player READY
        long pos = Math.max(0, getIntent().getLongExtra(EXTRA_POSITION_MS, 0));
        spec.startMs = Math.min(pos, Math.max(0, durationMs - MIN_CLIP_MS));
        spec.endMs = Math.min(durationMs, spec.startMs + DEFAULT_CLIP_MS);
        if (savedInstanceState != null) {
            spec.startMs = savedInstanceState.getLong("start", spec.startMs);
            spec.endMs = savedInstanceState.getLong("end", spec.endMs);
        }

        setupRange();
        setupNudges();
        setupCrop();
        setupText();
        setupOutput();
        exportButton.setOnClickListener(v -> onExportClicked());
        playButton.setOnClickListener(v -> setLoopPlaying(!loopPlaying));
        playerView.setOnClickListener(v -> setLoopPlaying(!loopPlaying));
        overlay.setTextSpec(spec);
        updateTexts();
        setLoading(true);
        initPlayer();
        loadMetadataAsync(uri);
    }

    /**
     * MediaMetadataRetriever does provider I/O, which for a cloud-backed content:// URI can mean
     * a multi-second download: run it on a worker and show a loading state meanwhile (P1-4).
     */
    private void loadMetadataAsync(Uri uri) {
        final android.content.Context app = getApplicationContext();
        metaExecutor.execute(() -> {
            final long[] meta = readMetadata(app, uri); // {durationMs, width, height} display-oriented
            handler.post(() -> {
                if (isDestroyed()) return;
                if (meta[1] > 0 && meta[2] > 0) {
                    videoW = (int) meta[1];
                    videoH = (int) meta[2];
                    overlay.setVideoAspect(videoW / (float) videoH);
                }
                if (!durationKnown && meta[0] > 0) applyDuration(meta[0]);
                metadataLoaded = true;
                setLoading(false);
                updateTexts();
            });
        });
    }

    private void applyDuration(long d) {
        durationKnown = true;
        durationMs = d;
        spec.endMs = Math.min(spec.endMs, durationMs);
        spec.startMs = Math.min(spec.startMs, Math.max(0, spec.endMs - MIN_CLIP_MS));
        setupRange();
        updateTexts();
    }

    private void setLoading(boolean loading) {
        View bar = findViewById(R.id.editor_loading);
        if (bar != null) bar.setVisibility(loading ? View.VISIBLE : View.GONE);
        exportButton.setEnabled(!loading);
        if (loading) exportButton.setText("Reading video…");
        else exportButton.setText(spec.gif ? "Export GIF" : "Export MP4");
    }

    @VisibleForTesting
    boolean isMetadataLoaded() {
        return metadataLoaded;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("start", spec.startMs);
        out.putLong("end", spec.endMs);
    }

    @androidx.annotation.WorkerThread
    static long[] readMetadata(android.content.Context ctx, Uri uri) {
        long[] out = {0, 0, 0};
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        java.io.Closeable src = null;
        try {
            src = VaultMediaDataSource.setDataSource(ctx, mmr, uri);
            out[0] = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            long w = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            long h = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long rot = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rot == 90 || rot == 270) { out[1] = h; out[2] = w; } else { out[1] = w; out[2] = h; }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Unknown container or a provider without seekable access: rely on the player instead.
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
            VaultMediaDataSource.closeQuietly(src);
        }
        return out;
    }

    private static long parse(@Nullable String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ player

    private void initPlayer() {
        player = PlayerFactory.createLight(this);
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setSeekParameters(SeekParameters.EXACT);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) {
                    float aspect = vs.width * vs.pixelWidthHeightRatio / vs.height;
                    overlay.setVideoAspect(aspect);
                    if (videoW <= 0) {
                        videoW = Math.round(vs.width * vs.pixelWidthHeightRatio);
                        videoH = vs.height;
                        updateTexts();
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && player != null) {
                    long d = player.getDuration();
                    if (d > 0 && (!durationKnown || Math.abs(d - durationMs) > 500)) applyDuration(d);
                    if (!metadataLoaded) {
                        // The player got there first (metadata probe still running): editing is
                        // fine now; the exporter probes the size itself if still unknown.
                        metadataLoaded = true;
                        setLoading(false);
                    }
                } else if (state == Player.STATE_ENDED && loopPlaying && player != null) {
                    player.seekTo(spec.startMs);
                    player.play();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(ClipEditorActivity.this, "Preview unavailable: " + error.getErrorCodeName(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(spec.uri), spec.startMs);
        player.prepare();
        player.setPlayWhenReady(loopPlaying);
        handler.post(loopTick);
    }

    private void setLoopPlaying(boolean on) {
        loopPlaying = on;
        playButton.setImageResource(on ? R.drawable.ic_pause : R.drawable.ic_play);
        if (player == null) return;
        if (on) {
            long pos = player.getCurrentPosition();
            if (pos < spec.startMs || pos >= spec.endMs) seekPreview(spec.startMs, true);
            player.play();
        } else {
            player.pause();
        }
    }

    private void seekPreview(long ms, boolean exact) {
        if (player == null) return;
        player.setSeekParameters(exact ? SeekParameters.EXACT : SeekParameters.CLOSEST_SYNC);
        player.seekTo(ms);
    }

    /** Duration of one frame from the decoded format (fallback 30 fps). */
    @VisibleForTesting
    long frameMs() {
        float fps = 0;
        if (player != null) {
            Format f = player.getVideoFormat();
            if (f != null) fps = f.frameRate;
        }
        if (fps <= 1 || fps > 240) fps = 30f;
        return Math.max(1, Math.round(1000.0 / fps));
    }

    // ------------------------------------------------------------------ trim

    private void setupRange() {
        range.clearOnChangeListeners();
        range.clearOnSliderTouchListeners();
        computeWindow();
        applyWindow();
        range.setLabelFormatter(v -> Fmt.timePrecise((long) v));
        range.addOnSliderTouchListener(new RangeSlider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(RangeSlider slider) {
                sliderDragging = true;
                if (player != null) player.pause();
                lastStartValue = slider.getValues().get(0);
            }

            @Override
            public void onStopTrackingTouch(RangeSlider slider) {
                sliderDragging = false;
                List<Float> v = slider.getValues();
                // Frame-accurate final position of whichever thumb moved, then resume the loop.
                boolean startMoved = Math.abs(v.get(0) - lastStartValue) > 0.5f;
                maybeRewindow(false);
                seekPreview(startMoved ? spec.startMs : Math.max(spec.startMs, spec.endMs - 1000), true);
                if (loopPlaying && player != null) player.play();
            }
        });
        range.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) return;
            List<Float> v = slider.getValues();
            long s = (long) (float) v.get(0), e = (long) (float) v.get(1);
            if (e - s < MIN_CLIP_MS) return;
            boolean startMoved = s != spec.startMs;
            spec.startMs = s;
            spec.endMs = e;
            updateTexts();
            // Fast keyframe previews while dragging (same approach as the player's scrubbing).
            seekPreview(startMoved ? s : e, false);
        });
    }

    /** Window = max(60 s, 3 × clip) centred on the selection, clamped to the video. */
    private void computeWindow() {
        long len = Math.max(MIN_CLIP_MS, spec.durationMs());
        long span = Math.min(Math.max(MIN_CLIP_MS, durationMs), Math.max(60_000L, len * 3));
        long centre = (spec.startMs + spec.endMs) / 2;
        winStart = clamp(centre - span / 2, 0, Math.max(0, durationMs - span));
        winEnd = Math.min(Math.max(MIN_CLIP_MS, durationMs), winStart + span);
    }

    private void applyWindow() {
        // Widen first so the values are always inside the bounds while they change.
        range.setValueFrom(0f);
        range.setValueTo(Math.max(MIN_CLIP_MS, durationMs));
        range.setValues((float) spec.startMs, (float) spec.endMs);
        range.setValueFrom(winStart);
        range.setValueTo(winEnd);
        boolean zoomed = winStart > 0 || winEnd < durationMs;
        windowText.setVisibility(zoomed ? View.VISIBLE : View.GONE);
        windowText.setText("Zoomed to " + Fmt.time(winStart) + " – " + Fmt.time(winEnd)
                + " of " + Fmt.time(durationMs) + " · drag a handle to an edge to move the window");
    }

    /** Re-centres the window when a handle reaches an edge (or a nudge leaves it). */
    private void maybeRewindow(boolean force) {
        long span = winEnd - winStart;
        long edge = span / 20;
        boolean nearEdge = (spec.startMs - winStart < edge && winStart > 0)
                || (winEnd - spec.endMs < edge && winEnd < durationMs);
        boolean outside = spec.startMs < winStart || spec.endMs > winEnd;
        boolean tooWide = spec.durationMs() * 3 > span && span < durationMs;
        if (outside || nearEdge || tooWide) computeWindow();
        if (force || outside || nearEdge || tooWide) applyWindow();
    }

    private void setupNudges() {
        bindNudge(R.id.start_minus_s, true, -100, false);
        bindNudge(R.id.start_minus_f, true, -1, true);
        bindNudge(R.id.start_plus_f, true, 1, true);
        bindNudge(R.id.start_plus_s, true, 100, false);
        bindNudge(R.id.end_minus_s, false, -100, false);
        bindNudge(R.id.end_minus_f, false, -1, true);
        bindNudge(R.id.end_plus_f, false, 1, true);
        bindNudge(R.id.end_plus_s, false, 100, false);
    }

    private void bindNudge(int id, boolean start, int amount, boolean frames) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> nudge(start, frames ? amount * frameMs() : amount));
    }

    @VisibleForTesting
    void nudge(boolean start, long deltaMs) {
        if (start) {
            spec.startMs = clamp(spec.startMs + deltaMs, 0, spec.endMs - MIN_CLIP_MS);
        } else {
            spec.endMs = clamp(spec.endMs + deltaMs, spec.startMs + MIN_CLIP_MS, durationMs);
        }
        maybeRewindow(true);
        updateTexts();
        // Show the exact frame at the nudged edge; the loop pauses so the frame stays visible.
        setLoopPlaying(false);
        seekPreview(start ? spec.startMs : Math.max(spec.startMs, spec.endMs - frameMs()), true);
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ crop / text / output

    private void setupCrop() {
        ChipGroup g = findViewById(R.id.crop_group);
        overlay.setListener(c -> {
            spec.crop = overlay.getCrop();
            updateTexts();
        });
        g.setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? R.id.crop_none : ids.get(0);
            float aspect;
            if (id == R.id.crop_free) aspect = 0f;
            else if (id == R.id.crop_1_1) aspect = 1f;
            else if (id == R.id.crop_9_16) aspect = 9f / 16f;
            else if (id == R.id.crop_16_9) aspect = 16f / 9f;
            else aspect = -1f;
            overlay.setCropMode(aspect);
            spec.crop = overlay.getCrop();
            updateTexts();
        });
    }

    private void setupText() {
        TextView input = findViewById(R.id.text_input);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                spec.text = s.toString();
                overlay.invalidate();
            }
        });
        ChipGroup pos = findViewById(R.id.text_pos_group);
        pos.setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? R.id.text_pos_bottom : ids.get(0);
            spec.textPosition = id == R.id.text_pos_top ? ClipExporter.TEXT_TOP
                    : id == R.id.text_pos_center ? ClipExporter.TEXT_CENTER : ClipExporter.TEXT_BOTTOM;
            overlay.invalidate();
        });
        Slider size = findViewById(R.id.text_size);
        size.addOnChangeListener((s, value, fromUser) -> {
            spec.textSize = value / 100f;
            overlay.invalidate();
        });
        spec.textSize = size.getValue() / 100f;

        ChipGroup colors = findViewById(R.id.text_color_group);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < TEXT_COLORS.length; i++) {
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setId(View.generateViewId());
            chip.setText(TEXT_COLOR_NAMES[i]);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(TEXT_COLORS[i]);
            dot.setStroke(Math.round(d), 0x80FFFFFF);
            dot.setSize(Math.round(16 * d), Math.round(16 * d));
            chip.setChipIcon(dot);
            chip.setChipIconVisible(true);
            final int color = TEXT_COLORS[i];
            chip.setOnCheckedChangeListener((b, checked) -> {
                if (checked) {
                    spec.textColor = color;
                    overlay.invalidate();
                }
            });
            colors.addView(chip);
            if (i == 0) chip.setChecked(true);
        }
    }

    private void setupOutput() {
        MaterialButtonToggleGroup format = findViewById(R.id.format_group);
        format.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            spec.gif = id == R.id.format_gif;
            mp4Options.setVisibility(spec.gif ? View.GONE : View.VISIBLE);
            gifOptions.setVisibility(spec.gif ? View.VISIBLE : View.GONE);
            upscaleOptions.setVisibility(BuildConfig.UPSCALE_EXPORT && !spec.gif ? View.VISIBLE : View.GONE);
            updateTexts();
        });
        setupUpscaleExport();
        MaterialSwitch keepAudio = findViewById(R.id.keep_audio);
        keepAudio.setOnCheckedChangeListener((b, on) -> {
            spec.keepAudio = on;
            updateTexts();
        });
        ChipGroup widths = findViewById(R.id.gif_width_group);
        widths.setOnCheckedStateChangeListener((g, ids) -> {
            int id = ids.isEmpty() ? R.id.gif_w480 : ids.get(0);
            spec.gifWidth = id == R.id.gif_w320 ? 320 : id == R.id.gif_w640 ? 640 : 480;
            updateTexts();
        });
        ChipGroup fps = findViewById(R.id.gif_fps_group);
        fps.setOnCheckedStateChangeListener((g, ids) -> {
            int id = ids.isEmpty() ? R.id.gif_fps15 : ids.get(0);
            spec.gifFps = id == R.id.gif_fps10 ? 10 : id == R.id.gif_fps20 ? 20 : 15;
            updateTexts();
        });
    }

    /**
     * 1.8.0: the "Upscale on export (2×)" row. Behind the {@code BuildConfig.UPSCALE_EXPORT} feature
     * flag (false when the build has no native upscaler). The switch stays disabled, with the reason
     * underneath, until {@link RealEsrgan#probe} confirms arm64 + the library + a Vulkan GPU.
     */
    private void setupUpscaleExport() {
        upscaleOptions = findViewById(R.id.upscale_export_options);
        upscaleSwitch = findViewById(R.id.upscale_export);
        upscaleHint = findViewById(R.id.upscale_export_hint);
        upscaleSwitch.setOnCheckedChangeListener((b, on) -> {
            spec.upscale2x = on;
            updateTexts();
        });
        if (!BuildConfig.UPSCALE_EXPORT) {
            upscaleOptions.setVisibility(View.GONE);
            return;
        }
        upscaleOptions.setVisibility(spec.gif ? View.GONE : View.VISIBLE);
        RealEsrgan.Availability known = RealEsrgan.cachedOrNull();
        if (known != null) {
            applyUpscaleAvailability(known);
            return;
        }
        new Thread(() -> {
            RealEsrgan.Availability a = RealEsrgan.probe();
            runOnUiThread(() -> { if (!isDestroyed()) applyUpscaleAvailability(a); });
        }, "esrgan-probe").start();
    }

    @VisibleForTesting
    void applyUpscaleAvailability(RealEsrgan.Availability a) {
        upscaleAvailability = a;
        upscaleSwitch.setEnabled(a.available);
        if (!a.available) {
            upscaleSwitch.setChecked(false);
            upscaleHint.setText("Unavailable: " + a.reason);
        } else {
            upscaleHint.setText("Real-ESRGAN AnimeVideo v3 on " + a.device
                    + " · best for anime & cartoons · slow: roughly 0.5–2 s per frame on a mid-range phone");
        }
    }

    /** Selects the output format (used by the format toggle and tests). */
    @VisibleForTesting
    void selectGif(boolean gif) {
        MaterialButtonToggleGroup format = findViewById(R.id.format_group);
        format.check(gif ? R.id.format_gif : R.id.format_mp4);
    }

    @VisibleForTesting
    ClipExporter.Spec spec() {
        return spec;
    }

    private void updateTexts() {
        long len = spec.durationMs();
        rangeText.setText(Fmt.timePrecise(spec.startMs) + " – " + Fmt.timePrecise(spec.endMs)
                + " · " + String.format(java.util.Locale.US, "%.2f s", len / 1000.0));
        long bytes = ClipExporter.estimateBytes(spec, videoW, videoH);
        String what = spec.gif ? "GIF " + spec.gifWidth + " px · " + spec.gifFps + " fps"
                : "MP4 (H.264" + (spec.keepAudio ? " + AAC" : ", no audio") + ")"
                + (spec.upscale2x ? " · upscaled 2×" : "");
        estimateText.setText(what + " · estimated ≈ " + Fmt.size(bytes));
        boolean warn = spec.gif && len > GIF_WARN_MS;
        warningText.setVisibility(warn ? View.VISIBLE : View.GONE);
        if (warn) {
            warningText.setText("GIFs longer than 15 s get large (≈ " + Fmt.size(bytes)
                    + "). Consider a shorter range, a smaller size, or an MP4 clip.");
        }
        if (metadataLoaded) exportButton.setText(spec.gif ? "Export GIF" : "Export MP4");
    }

    // ------------------------------------------------------------------ export

    private void onExportClicked() {
        if (spec.gif && spec.durationMs() > GIF_WARN_MS) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Large GIF")
                    .setMessage("This GIF is " + Math.round(spec.durationMs() / 1000.0)
                            + " s long and will be about " + Fmt.size(ClipExporter.estimateBytes(spec, videoW, videoH))
                            + ". Long GIFs are slow to make and share. Export anyway?")
                    .setPositiveButton("Export GIF", (d, w) -> exportWithPermission())
                    .setNeutralButton("Make MP4 instead", (d, w) -> {
                        selectGif(false);
                        exportWithPermission();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        exportWithPermission();
    }

    private void exportWithPermission() {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExport = this::startExport;
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        startExport();
    }

    private void startExport() {
        setLoopPlaying(false);
        View content = getLayoutInflater().inflate(R.layout.dialog_export_progress, null);
        LinearProgressIndicator bar = content.findViewById(R.id.export_progress);
        TextView status = content.findViewById(R.id.export_status);
        status.setText(spec.gif ? "Extracting frames and encoding GIF…"
                : spec.upscale2x ? "Trimming, then upscaling 2× on the GPU…" : "Encoding MP4 with the hardware encoder…");
        final String[] lastStatus = {null};
        exporter = new ClipExporter(this);
        progressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(spec.gif ? "Making GIF" : "Making clip")
                .setView(content)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (exporter != null) exporter.cancel();
                })
                .show();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ClipExporter.Spec copy = copySpec();
        exporter.start(copy, new ClipExporter.Callback() {
            @Override
            public void onProgress(int percent) {
                if (bar.isIndeterminate()) {
                    bar.setIndeterminate(false);
                    bar.setMax(100);
                }
                bar.setProgressCompat(percent, true);
                status.setText(lastStatus[0] == null ? percent + "%" : percent + "% · " + lastStatus[0]);
            }

            @Override
            public void onStatus(String s) {
                lastStatus[0] = s;
            }

            @Override
            public void onDone(Uri uri, String mime, long bytes) {
                finishExport();
                showDone(uri, mime, bytes);
            }

            @Override
            public void onError(String message) {
                finishExport();
                if (isFinishing() || isDestroyed()) return;
                new MaterialAlertDialogBuilder(ClipEditorActivity.this)
                        .setTitle("Export failed").setMessage(message)
                        .setPositiveButton(android.R.string.ok, null).show();
            }

            @Override
            public void onCancelled() {
                finishExport();
                Toast.makeText(ClipEditorActivity.this, "Export cancelled", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private ClipExporter.Spec copySpec() {
        ClipExporter.Spec c = new ClipExporter.Spec();
        c.uri = spec.uri; c.title = spec.title; c.startMs = spec.startMs; c.endMs = spec.endMs;
        c.crop = spec.crop == null ? null : new RectF(spec.crop);
        c.text = spec.text; c.textPosition = spec.textPosition; c.textSize = spec.textSize; c.textColor = spec.textColor;
        c.gif = spec.gif; c.keepAudio = spec.keepAudio; c.gifWidth = spec.gifWidth; c.gifFps = spec.gifFps;
        c.upscale2x = spec.upscale2x && !spec.gif;
        c.srcW = videoW; c.srcH = videoH;
        return c;
    }

    private void finishExport() {
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
        exporter = null;
    }

    private void showDone(Uri uri, String mime, long bytes) {
        if (isFinishing() || isDestroyed()) return;
        String where = mime.startsWith("video/") ? "Movies/VideoPlayer" : "Pictures/VideoPlayer";
        new MaterialAlertDialogBuilder(this)
                .setTitle(mime.startsWith("video/") ? "Clip saved" : "GIF saved")
                .setMessage("Saved to " + where + " (" + Fmt.size(bytes) + ").")
                .setPositiveButton("Share", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType(mime)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share"));
                })
                .setNeutralButton("Open", (d, w) -> {
                    Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(view);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Done", null)
                .show();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(loopTick);   // no 30 ms ticks while invisible
        if (player != null) player.pause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) {
            handler.removeCallbacks(loopTick);
            handler.post(loopTick);
            if (loopPlaying && exporter == null) player.play();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        metaExecutor.shutdownNow();
        if (exporter != null) exporter.cancel();
        if (progressDialog != null) progressDialog.dismiss();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
