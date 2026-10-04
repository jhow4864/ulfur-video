package com.jamh.videoplayer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.DefaultTrackNameProvider;
import androidx.media3.ui.PlayerView;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import com.google.common.collect.ImmutableList;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_ID = "video_id";
    public static final String EXTRA_BUCKET_ID = "bucket_id";
    public static final String EXTRA_SORT = "sort";

    private static final long SKIP_MS = 10_000;
    /** Keyframe-snapping window for double-tap skips (fast, but never moves the wrong way). */
    private static final long SKIP_SNAP_TOLERANCE_US = 4_000_000;
    private static final long SCRUB_THROTTLE_MS = 80;
    private static final long FINE_SCRUB_THROTTLE_MS = 120;
    private static final int SCRUB_NORMAL = 0, SCRUB_HALF = 1, SCRUB_FINE = 2;
    private static final String[] SCRUB_LABELS = {"Normal scrubbing", "Half-speed scrubbing", "Fine scrubbing · 0.1 s"};
    private static final long SLEEP_FADE_MS = 10_000;
    private static final long SPINNER_DELAY_MS = 500;
    private static final long HIDE_DELAY_MS = 3_500;
    private static final long HEAVY_BITRATE_BPS = 40_000_000L;

    private static final String ACTION_PIP = "com.jamh.videoplayer.PIP_CONTROL";
    private static final String EXTRA_PIP = "control";
    private static final int PIP_PLAY_PAUSE = 1, PIP_REWIND = 2, PIP_FORWARD = 3;

    private static final int GESTURE_NONE = 0, GESTURE_SCRUB = 1, GESTURE_BRIGHTNESS = 2,
            GESTURE_VOLUME = 3, GESTURE_ZOOM = 4;

    private static final String[] ASPECT_NAMES = {"Fit to screen", "Crop to fill", "Stretch", "Zoom"};
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ExoPlayer player;
    private PlayerView playerView;
    private View root, controls, gestureLayer, gesturePanel;
    private ImageButton btnPlay, btnPrev, btnNext, btnLock, btnUnlock, btnRotationLock;
    private TextView titleView, positionView, durationView, seekInfo, gestureText, btnSpeed,
            aspectLabel, skipLeft, skipRight;
    private ImageView gestureIcon;
    private ProgressBar gestureProgress, spinner;
    private SeekBar seekBar;
    private AudioManager audioManager;

    // Current media
    private Uri currentUri;
    private String currentKey;
    private String currentTitle;
    private String currentMime;
    private Uri externalSubUri;
    private String externalSubName;
    private final List<VideoItem> playlist = new ArrayList<>();
    private int playlistIndex = -1;
    private long playlistVideoId = -1;

    // Player config
    private boolean preferSoftware;
    private boolean heavyProfile;
    private float speed = 1f;
    private int aspectMode = 0;
    private float zoom = 1f;

    // UI state
    private boolean controlsVisible = true;
    private boolean locked;
    private boolean rotationLocked;
    private boolean portrait;
    private boolean orientationChosenByUser;
    private boolean orientationChosenForVideo;

    // Gesture state
    private int gestureMode = GESTURE_NONE;
    private float brightness = -1f;
    private float volume;
    private int maxVolume;
    private long scrubStartPos;
    private float gestureStartX, gestureStartY;
    private int skipAccumDir;
    private long skipAccumMs;
    private long lastSkipTapAt;

    // Scrubbing state
    private boolean scrubbing;
    private long scrubTarget;
    private long lastScrubSeekAt;

    // Diagnostics
    private String videoDecoderName = "-";
    private String audioDecoderName = "-";
    private int droppedFrames;

    private BroadcastReceiver pipReceiver;

    // Audio / session / background
    private DialogueBoostProcessor dialogueBoost;
    private MediaSession mediaSession;
    private boolean backgroundAudio;
    private boolean videoDisabledForBackground;
    private TextView badgeNight, badgeSleep;

    // Precision scrubbing
    private int scrubZone = SCRUB_NORMAL;
    private float scrubAccumMs;

    // Sleep timer
    private long sleepEndElapsed;        // 0 = off
    private boolean sleepEndOfVideo;
    private final Runnable sleepTick = this::onSleepTick;

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, "Notifications are off: background audio will still play, "
                            + "but controls may not show.", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String[]> subtitlePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onSubtitlePicked);

    private final Runnable hideControlsRunnable = () -> setControlsVisible(false);
    private final Runnable hideGesturePanel = () -> gesturePanel.setVisibility(View.GONE);
    private final Runnable hideSeekInfo = () -> seekInfo.setVisibility(View.GONE);
    private final Runnable hideUnlock = () -> btnUnlock.setVisibility(View.GONE);
    private final Runnable hideSkipFeedback = () -> {
        skipLeft.setVisibility(View.GONE);
        skipRight.setVisibility(View.GONE);
        skipAccumMs = 0;
        skipAccumDir = 0;
    };
    private final Runnable showSpinnerRunnable = () -> {
        if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING && !scrubbing) {
            spinner.setVisibility(View.VISIBLE);
        }
    };
    private final Runnable progressRunnable = new Runnable() {
        @Override public void run() {
            updateProgress();
            handler.postDelayed(this, 200);
        }
    };
    private final Runnable scrubSeekRunnable = this::performScrubSeek;

    // =================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra(DefaultPlayerHelper.EXTRA_DEFAULT_SETUP, false)) {
            // Launched from the "set as default" flow: nothing to play, go straight back so the
            // library can re-check the default and report the result.
            finish();
            return;
        }
        setContentView(R.layout.activity_player);
        bindViews();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        hideSystemUi();
        ViewCompat.setOnApplyWindowInsetsListener(controls, (v, insets) -> {
            Insets i = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
        });

        preferSoftware = Prefs.preferSoftwareDecoder(this);
        backgroundAudio = Prefs.backgroundAudio(this);
        updateBadges();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (playerView.getSubtitleView() != null) {
            playerView.getSubtitleView().setStyle(new CaptionStyleCompat(0xFFFFFFFF, 0x00000000,
                    0x00000000, CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000, null));
            playerView.getSubtitleView().setFractionalTextSize(0.0533f * 1.1f);
        }

        setupControls();
        setupGestures();
        registerPipReceiver();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (locked) {
                    flashUnlockButton();
                    return;
                }
                finish();
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(DefaultPlayerHelper.EXTRA_DEFAULT_SETUP, false)) {
            moveTaskToBack(true); // keep the current video; return to the library
            return;
        }
        if (intent.getData() == null) return; // tapped the media notification
        setIntent(intent);
        saveResume();
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(progressRunnable);
        if (videoDisabledForBackground && player != null) {
            videoDisabledForBackground = false;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(progressRunnable);
        if (!isInPip() && player != null) {
            saveResume();
            if (backgroundAudio && player.getPlayWhenReady() && !isFinishing()) {
                // Keep the audio going; stop decoding video while nothing is visible.
                ensurePlaybackService();
                videoDisabledForBackground = true;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
            } else {
                player.pause();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        saveResume();
        if (pipReceiver != null) {
            try { unregisterReceiver(pipReceiver); } catch (RuntimeException ignored) { }
        }
        handler.removeCallbacks(sleepTick);
        PlaybackService.detachAndStop(mediaSession);
        releasePlayer();
        io.shutdownNow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemUi();
    }

    private void bindViews() {
        root = findViewById(R.id.player_root);
        playerView = findViewById(R.id.player_view);
        controls = findViewById(R.id.controls);
        gestureLayer = findViewById(R.id.gesture_layer);
        gesturePanel = findViewById(R.id.gesture_panel);
        gestureIcon = findViewById(R.id.gesture_icon);
        gestureText = findViewById(R.id.gesture_text);
        gestureProgress = findViewById(R.id.gesture_progress);
        spinner = findViewById(R.id.buffering);
        seekInfo = findViewById(R.id.seek_info);
        skipLeft = findViewById(R.id.skip_left);
        skipRight = findViewById(R.id.skip_right);
        titleView = findViewById(R.id.title);
        positionView = findViewById(R.id.position);
        durationView = findViewById(R.id.duration);
        seekBar = findViewById(R.id.seekbar);
        btnPlay = findViewById(R.id.btn_play);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        btnLock = findViewById(R.id.btn_lock);
        btnUnlock = findViewById(R.id.btn_unlock);
        btnRotationLock = findViewById(R.id.btn_rotation_lock);
        btnSpeed = findViewById(R.id.btn_speed);
        badgeNight = findViewById(R.id.badge_night);
        badgeSleep = findViewById(R.id.badge_sleep);
        aspectLabel = findViewById(R.id.aspect_label);
    }

    // =================================================================== intent / playlist

    private void handleIntent(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        externalSubUri = null;
        externalSubName = null;
        currentMime = intent.getType();
        orientationChosenForVideo = false;
        playlist.clear();
        playlistIndex = -1;

        long id = intent.getLongExtra(EXTRA_VIDEO_ID, -1);
        final int sort = intent.getIntExtra(EXTRA_SORT, Sort.NAME);
        VideoItem item = null;
        if (id < 0 && !isNetwork(uri)) {
            // Opened from another app: try to map it to a MediaStore row (fast single-row query).
            item = VideoRepository.resolve(this, uri);
        }
        if (id >= 0) {
            List<VideoItem> l = VideoRepository.query(this, android.provider.MediaStore.Video.Media._ID + "=?",
                    new String[]{String.valueOf(id)});
            item = l.isEmpty() ? null : l.get(0);
        }

        if (item != null) {
            playlistVideoId = item.id;
            startMedia(id >= 0 ? item.uri : uri, item.resumeKey(), item.title, isHeavy(item));
            final String bucket = item.bucketId;
            final long vid = item.id;
            io.execute(() -> {
                List<VideoItem> list = VideoRepository.inBucket(getApplicationContext(), bucket);
                Sort.videos(list, sort);
                handler.post(() -> {
                    if (isDestroyed() || playlistVideoId != vid) return;
                    playlist.clear();
                    playlist.addAll(list);
                    playlistIndex = -1;
                    for (int i = 0; i < list.size(); i++) if (list.get(i).id == vid) playlistIndex = i;
                    updateNavButtons();
                });
            });
        } else {
            playlistVideoId = -1;
            String name = isNetwork(uri) ? uri.getLastPathSegment() : VideoRepository.displayName(this, uri);
            if (name == null) name = uri.toString();
            int dot = name.lastIndexOf('.');
            if (dot > 0 && !isNetwork(uri)) name = name.substring(0, dot);
            startMedia(uri, uri.toString(), name, false);
        }
    }

    private static boolean isHeavy(VideoItem v) {
        if (v.durationMs <= 0 || v.sizeBytes <= 0) return false;
        return v.sizeBytes * 8L * 1000L / v.durationMs > HEAVY_BITRATE_BPS;
    }

    private static boolean isNetwork(Uri uri) {
        String s = uri.getScheme();
        return "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s) || "rtsp".equalsIgnoreCase(s);
    }

    private void playIndex(int index) {
        if (index < 0 || index >= playlist.size()) return;
        saveResume();
        playlistIndex = index;
        VideoItem v = playlist.get(index);
        playlistVideoId = v.id;
        externalSubUri = null;
        externalSubName = null;
        currentMime = null;
        orientationChosenForVideo = false;
        startMedia(v.uri, v.resumeKey(), v.title, isHeavy(v));
        updateNavButtons();
        showControls();
    }

    private void startMedia(Uri uri, String key, String title, boolean heavy) {
        currentUri = uri;
        currentKey = key;
        currentTitle = title;
        titleView.setText(title);
        boolean local = !isNetwork(uri);
        boolean wantHeavy = local && heavy;
        if (player == null || wantHeavy != heavyProfile) {
            heavyProfile = wantHeavy;
            rebuildPlayer();
        }
        long start = 0;
        long[] resume = Prefs.getResume(this, key);
        if (resume != null && resume[0] > 3000 && (resume[1] <= 0 || resume[0] < resume[1] - 5000)) {
            start = resume[0];
        }
        player.setWakeMode(local ? C.WAKE_MODE_LOCAL : C.WAKE_MODE_NETWORK);
        player.setMediaItem(buildMediaItem(), start);
        player.prepare();
        player.play();
        droppedFrames = 0;
        if (start > 0) {
            Snackbar.make(root, "Resumed from " + Fmt.time(start), Snackbar.LENGTH_LONG)
                    .setAction("Start over", v -> fastSeekExact(0))
                    .show();
        }
        updateNavButtons();
        showControls();
    }

    private MediaItem buildMediaItem() {
        MediaItem.Builder b = new MediaItem.Builder()
                .setUri(currentUri)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(currentTitle).build());
        if (isNetwork(currentUri)) {
            String path = currentUri.getPath() == null ? "" : currentUri.getPath().toLowerCase(Locale.ROOT);
            String mime = currentMime == null ? "" : currentMime.toLowerCase(Locale.ROOT);
            if (mime.contains("mpegurl") || path.endsWith(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
            else if (mime.contains("dash") || path.endsWith(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD);
        }
        if (externalSubUri != null) {
            b.setSubtitleConfigurations(Collections.singletonList(
                    new MediaItem.SubtitleConfiguration.Builder(externalSubUri)
                            .setMimeType(subtitleMime(externalSubName))
                            .setLabel(externalSubName)
                            .setLanguage("und")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED)
                            .build()));
        }
        return b.build();
    }

    private static String subtitleMime(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (n.endsWith(".ass") || n.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        if (n.endsWith(".ttml") || n.endsWith(".dfxp") || n.endsWith(".xml")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    // =================================================================== player

    private void rebuildPlayer() {
        releasePlayer();
        dialogueBoost = new DialogueBoostProcessor(Prefs.dialogueBoost(this));
        player = PlayerFactory.create(this, preferSoftware, heavyProfile, dialogueBoost);
        player.addListener(playerListener);
        createSession();
        player.addAnalyticsListener(analyticsListener);
        player.setPlaybackSpeed(speed);
        playerView.setPlayer(player);
        applyAspect(false);
    }

    private void releasePlayer() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
        }
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            playerView.setKeepScreenOn(isPlaying);
            updatePlayIcon();
            updatePipParams();
            if (isPlaying) scheduleHide();
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            updatePlayIcon();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_BUFFERING && !scrubbing) {
                // Only show the spinner if buffering lasts longer than ~500 ms.
                handler.removeCallbacks(showSpinnerRunnable);
                handler.postDelayed(showSpinnerRunnable, SPINNER_DELAY_MS);
            } else {
                handler.removeCallbacks(showSpinnerRunnable);
                spinner.setVisibility(View.GONE);
            }
            if (state == Player.STATE_ENDED) {
                Prefs.clearResume(PlayerActivity.this, currentKey);
                if (sleepEndOfVideo) {
                    cancelSleepTimer();
                    Toast.makeText(PlayerActivity.this, "Sleep timer: stopped at end of video", Toast.LENGTH_SHORT).show();
                    showControls();
                } else if (playlistIndex >= 0 && playlistIndex < playlist.size() - 1) {
                    playIndex(playlistIndex + 1);
                } else {
                    showControls();
                }
            }
            updatePlayIcon();
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            if (size.width <= 0 || size.height <= 0) return;
            updatePipParams();
            if (!orientationChosenByUser && !orientationChosenForVideo) {
                orientationChosenForVideo = true;
                portrait = size.height * 1f > size.width * size.pixelWidthHeightRatio;
                applyOrientation();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            showErrorDialog(error);
        }
    };

    private final AnalyticsListener analyticsListener = new AnalyticsListener() {
        @Override
        public void onVideoDecoderInitialized(@NonNull EventTime t, @NonNull String name, long a, long b) {
            videoDecoderName = name;
        }

        @Override
        public void onAudioDecoderInitialized(@NonNull EventTime t, @NonNull String name, long a, long b) {
            audioDecoderName = name;
        }

        @Override
        public void onDroppedVideoFrames(@NonNull EventTime t, int count, long elapsedMs) {
            droppedFrames += count;
        }
    };

    private void showErrorDialog(PlaybackException error) {
        String msg = error.getErrorCodeName();
        if (error.getCause() != null && error.getCause().getMessage() != null) {
            msg += "\n\n" + error.getCause().getMessage();
        }
        boolean decoderIssue = error.errorCode >= PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                && error.errorCode < 5000;
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle("Can't play this video")
                .setMessage(msg)
                .setNegativeButton("Close", (d, w) -> finish());
        if (decoderIssue && !preferSoftware) {
            b.setPositiveButton("Try software decoder", (d, w) -> setPreferSoftware(true));
        } else {
            b.setPositiveButton("Retry", (d, w) -> {
                if (player != null) { player.prepare(); player.play(); }
            });
        }
        if (playlistIndex >= 0 && playlistIndex < playlist.size() - 1) {
            b.setNeutralButton("Next video", (d, w) -> playIndex(playlistIndex + 1));
        }
        b.setOnDismissListener(d -> hideSystemUi());
        b.show();
    }

    private void setPreferSoftware(boolean sw) {
        preferSoftware = sw;
        Prefs.setPreferSoftwareDecoder(this, sw);
        if (player == null) return;
        long pos = player.getCurrentPosition();
        boolean pwr = player.getPlayWhenReady() || player.getPlayerError() != null;
        rebuildPlayer();
        player.setMediaItem(buildMediaItem(), pos);
        player.prepare();
        player.setPlayWhenReady(pwr);
        Toast.makeText(this, sw ? "Software decoder preferred" : "Hardware decoder preferred",
                Toast.LENGTH_SHORT).show();
    }

    // =================================================================== seeking

    /** Exact seek (used on scrub release and explicit jumps). */
    private void fastSeekExact(long positionMs) {
        if (player == null) return;
        player.setSeekParameters(SeekParameters.EXACT);
        player.seekTo(Math.max(0, positionMs));
    }

    /**
     * Double-tap / button skip. Snaps to the nearest keyframe within +-4 s of the target (no
     * decode-to-position, so it is near-instant), otherwise seeks exactly. Playback is not paused.
     */
    private void skipBy(long deltaMs) {
        if (player == null || !player.isCurrentMediaItemSeekable()) return;
        long dur = player.getDuration();
        long target = player.getCurrentPosition() + deltaMs;
        if (dur != C.TIME_UNSET) target = Math.min(target, Math.max(0, dur - 500));
        target = Math.max(0, target);
        player.setSeekParameters(new SeekParameters(SKIP_SNAP_TOLERANCE_US, SKIP_SNAP_TOLERANCE_US));
        player.seekTo(target);
        player.setSeekParameters(SeekParameters.EXACT); // queued after the seek on the playback thread
        updateProgressUi(target);
    }

    /** Starts a scrub: enables Media3 scrubbing mode and keyframe (CLOSEST_SYNC) seeks. */
    private void beginScrub() {
        if (player == null || scrubbing) return;
        scrubbing = true;
        scrubTarget = player.getCurrentPosition();
        lastScrubSeekAt = 0;
        handler.removeCallbacks(hideControlsRunnable);
        handler.removeCallbacks(showSpinnerRunnable);
        spinner.setVisibility(View.GONE);
        player.setScrubbingModeEnabled(true);
        scrubZone = SCRUB_NORMAL;
        scrubAccumMs = 0;
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
    }

    /**
     * Precision zones for the horizontal swipe: top third normal, middle half speed, bottom third
     * fine (0.1 s steps). Normal/half keep keyframe (CLOSEST_SYNC) previews; fine mode previews
     * with EXACT seeks, otherwise the frame would not change between keyframes.
     */
    private void setScrubZone(int zone) {
        if (zone == scrubZone || player == null) return;
        scrubZone = zone;
        player.setSeekParameters(zone == SCRUB_FINE ? SeekParameters.EXACT : SeekParameters.CLOSEST_SYNC);
    }

    /** Live preview: throttled keyframe seeks so the surface keeps updating during the drag. */
    private void scrubTo(long positionMs) {
        if (player == null) return;
        if (!scrubbing) beginScrub();
        long dur = player.getDuration();
        if (dur != C.TIME_UNSET) positionMs = Math.min(positionMs, dur);
        scrubTarget = Math.max(0, positionMs);
        updateProgressUi(scrubTarget);
        long since = SystemClock.uptimeMillis() - lastScrubSeekAt;
        handler.removeCallbacks(scrubSeekRunnable);
        long throttle = scrubZone == SCRUB_FINE ? FINE_SCRUB_THROTTLE_MS : SCRUB_THROTTLE_MS;
        if (since >= throttle) {
            performScrubSeek();
        } else {
            handler.postDelayed(scrubSeekRunnable, throttle - since); // trailing edge
        }
    }

    private void performScrubSeek() {
        if (!scrubbing || player == null) return;
        lastScrubSeekAt = SystemClock.uptimeMillis();
        player.seekTo(scrubTarget);
    }

    /** Ends a scrub with a single exact seek to the final position. */
    private void endScrub() {
        if (!scrubbing) return;
        scrubbing = false;
        handler.removeCallbacks(scrubSeekRunnable);
        if (player != null) {
            player.setScrubbingModeEnabled(false);
            fastSeekExact(scrubTarget);
        }
        handler.postDelayed(hideSeekInfo, 400);
        scheduleHide();
    }

    // =================================================================== controls

    @SuppressLint("ClickableViewAccessibility")
    private void setupControls() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> { togglePlay(); scheduleHide(); });
        findViewById(R.id.btn_rew).setOnClickListener(v -> { skipBy(-SKIP_MS); scheduleHide(); });
        findViewById(R.id.btn_ffwd).setOnClickListener(v -> { skipBy(SKIP_MS); scheduleHide(); });
        btnPrev.setOnClickListener(v -> {
            if (player != null && player.getCurrentPosition() > 5000) fastSeekExact(0);
            else playIndex(playlistIndex - 1);
        });
        btnNext.setOnClickListener(v -> playIndex(playlistIndex + 1));
        btnSpeed.setOnClickListener(v -> showSpeedDialog());
        findViewById(R.id.btn_audio).setOnClickListener(v -> showTrackDialog(C.TRACK_TYPE_AUDIO));
        findViewById(R.id.btn_subs).setOnClickListener(v -> showTrackDialog(C.TRACK_TYPE_TEXT));
        View pip = findViewById(R.id.btn_pip);
        pip.setOnClickListener(v -> enterPip());
        pip.setVisibility(pipSupported() ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_more).setOnClickListener(this::showMoreMenu);
        findViewById(R.id.btn_aspect).setOnClickListener(v -> {
            aspectMode = (aspectMode + 1) % ASPECT_NAMES.length;
            applyAspect(true);
            scheduleHide();
        });
        findViewById(R.id.btn_rotate).setOnClickListener(v -> {
            portrait = !portrait;
            orientationChosenByUser = true;
            applyOrientation();
            scheduleHide();
        });
        btnRotationLock.setOnClickListener(v -> {
            rotationLocked = !rotationLocked;
            applyOrientation();
            Toast.makeText(this, rotationLocked ? "Rotation locked" : "Auto-rotate on", Toast.LENGTH_SHORT).show();
            scheduleHide();
        });
        btnLock.setOnClickListener(v -> setLocked(true));
        btnUnlock.setOnClickListener(v -> setLocked(false));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar sb) {
                beginScrub();
            }
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || player == null) return;
                long target = progress * 100L;
                scrubTo(target);
                showSeekInfo(target, null, null);
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                endScrub();
            }
        });
        updateRotationLockIcon();
        aspectLabel.setText("");
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            fastSeekExact(0);
            player.play();
        } else if (player.getPlayWhenReady()) {
            player.pause();
        } else {
            player.play();
        }
    }

    private void updatePlayIcon() {
        boolean showPause = player != null && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED;
        btnPlay.setImageResource(showPause ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updateNavButtons() {
        boolean hasPrev = playlistIndex > 0;
        boolean hasNext = playlistIndex >= 0 && playlistIndex < playlist.size() - 1;
        btnPrev.setEnabled(true); // always usable: restarts the current video when there is no previous
        btnPrev.setAlpha(hasPrev ? 1f : 0.6f);
        btnNext.setEnabled(hasNext);
        btnNext.setAlpha(hasNext ? 1f : 0.35f);
    }

    private void updateProgress() {
        if (player == null) return;
        long dur = player.getDuration();
        if (dur != C.TIME_UNSET && dur > 0) {
            int max = (int) Math.min(Integer.MAX_VALUE, dur / 100);
            if (seekBar.getMax() != max) seekBar.setMax(max);
            durationView.setText(Fmt.time(dur));
            seekBar.setEnabled(player.isCurrentMediaItemSeekable());
        } else {
            durationView.setText(player.isCurrentMediaItemLive() ? "LIVE" : "--:--");
        }
        seekBar.setSecondaryProgress((int) (player.getBufferedPosition() / 100));
        if (!scrubbing) updateProgressUi(player.getCurrentPosition());
    }

    private void updateProgressUi(long pos) {
        positionView.setText(Fmt.time(pos));
        if (!scrubbing || gestureMode == GESTURE_SCRUB) seekBar.setProgress((int) (pos / 100));
    }

    private void setControlsVisible(boolean visible) {
        if (isInPip()) visible = false;
        if (locked && visible) {
            flashUnlockButton();
            return;
        }
        controlsVisible = visible;
        controls.animate().cancel();
        if (visible) {
            controls.setVisibility(View.VISIBLE);
            controls.animate().alpha(1f).setDuration(150).start();
            scheduleHide();
        } else {
            controls.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> { if (!controlsVisible) controls.setVisibility(View.GONE); })
                    .start();
            hideSystemUi();
        }
    }

    private void showControls() {
        setControlsVisible(true);
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable);
        if (player != null && player.isPlaying() && !scrubbing) {
            handler.postDelayed(hideControlsRunnable, HIDE_DELAY_MS);
        }
    }

    private void setLocked(boolean lock) {
        locked = lock;
        if (lock) {
            controlsVisible = false;
            controls.animate().cancel();
            controls.setAlpha(0f);
            controls.setVisibility(View.GONE);
            flashUnlockButton();
            Toast.makeText(this, "Screen locked", Toast.LENGTH_SHORT).show();
        } else {
            handler.removeCallbacks(hideUnlock);
            btnUnlock.setVisibility(View.GONE);
            showControls();
        }
    }

    private void flashUnlockButton() {
        btnUnlock.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideUnlock);
        handler.postDelayed(hideUnlock, 2500);
    }

    private void hideSystemUi() {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }

    // =================================================================== orientation / aspect

    private void applyOrientation() {
        if (rotationLocked) {
            setRequestedOrientation(orientationChosenByUser
                    ? (portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                    : ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        } else {
            setRequestedOrientation(portrait ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        updateRotationLockIcon();
    }

    private void updateRotationLockIcon() {
        btnRotationLock.setImageTintList(ColorStateList.valueOf(
                rotationLocked ? MaterialColors.getColor(btnRotationLock, androidx.appcompat.R.attr.colorPrimary) : 0xFFFFFFFF));
        btnRotationLock.setContentDescription(rotationLocked ? "Unlock rotation" : "Lock rotation");
    }

    private void applyAspect(boolean announce) {
        int mode;
        float z = 1f;
        switch (aspectMode) {
            case 1: mode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; break;   // crop to fill
            case 2: mode = AspectRatioFrameLayout.RESIZE_MODE_FILL; break;   // stretch
            case 3: mode = AspectRatioFrameLayout.RESIZE_MODE_FIT; z = 1.5f; break; // zoom
            default: mode = AspectRatioFrameLayout.RESIZE_MODE_FIT; break;   // fit
        }
        playerView.setResizeMode(mode);
        setZoom(z);
        aspectLabel.setText(ASPECT_NAMES[aspectMode]);
        if (announce) showGesturePanel(R.drawable.ic_aspect, ASPECT_NAMES[aspectMode], -1);
    }

    private void setZoom(float z) {
        zoom = Math.max(0.5f, Math.min(4f, z));
        View surface = playerView.getVideoSurfaceView();
        if (surface != null) {
            surface.setScaleX(zoom);
            surface.setScaleY(zoom);
        }
    }

    // =================================================================== gestures

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                gestureStartX = e.getX();
                gestureStartY = e.getY();
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                // Taps following a double-tap keep skipping (Samsung/YouTube style +10 s, +20 s ...).
                if (!locked && SystemClock.uptimeMillis() - lastSkipTapAt < 700) {
                    int side = sideOf(e.getX());
                    if (side != 0 && side == skipAccumDir) {
                        doubleTapSkip(side);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (SystemClock.uptimeMillis() - lastSkipTapAt < 700) return true;
                if (locked) {
                    flashUnlockButton();
                } else {
                    setControlsVisible(!controlsVisible);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (locked || player == null) return true;
                int side = sideOf(e.getX());
                if (side == 0) {
                    togglePlay();
                    showGesturePanel(player.getPlayWhenReady() ? R.drawable.ic_play : R.drawable.ic_pause,
                            player.getPlayWhenReady() ? "Play" : "Pause", -1);
                } else {
                    doubleTapSkip(side);
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
                if (locked || player == null || e1 == null || gestureMode == GESTURE_ZOOM) return false;
                int w = gestureLayer.getWidth(), h = gestureLayer.getHeight();
                if (gestureMode == GESTURE_NONE) {
                    // Ignore swipes starting in the system gesture zones at the top/bottom edges.
                    if (gestureStartY < h * 0.06f || gestureStartY > h * 0.94f) return false;
                    float totalX = e2.getX() - gestureStartX, totalY = e2.getY() - gestureStartY;
                    if (Math.abs(totalX) > Math.abs(totalY)) {
                        if (!player.isCurrentMediaItemSeekable() || player.getDuration() == C.TIME_UNSET) return false;
                        gestureMode = GESTURE_SCRUB;
                        scrubStartPos = player.getCurrentPosition();
                        beginScrub();
                    } else if (gestureStartX < w / 2f) {
                        gestureMode = GESTURE_BRIGHTNESS;
                        brightness = currentBrightness();
                    } else {
                        gestureMode = GESTURE_VOLUME;
                        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    }
                }
                switch (gestureMode) {
                    case GESTURE_SCRUB: {
                        long dur = player.getDuration();
                        float y = e2.getY();
                        int zone = y < h / 3f ? SCRUB_NORMAL : y < h * 2f / 3f ? SCRUB_HALF : SCRUB_FINE;
                        setScrubZone(zone);
                        float msPerPx;
                        if (zone == SCRUB_FINE) {
                            msPerPx = 100f / (12f * getResources().getDisplayMetrics().density); // 12 dp = 0.1 s
                        } else {
                            long range = Math.min(dur, 90_000); // full width = 90 s (or whole video)
                            msPerPx = range / (float) w * (zone == SCRUB_HALF ? 0.5f : 1f);
                        }
                        scrubAccumMs += -dx * msPerPx; // dx is last - current
                        long target = scrubStartPos + Math.round(scrubAccumMs);
                        if (zone == SCRUB_FINE) target = Math.round(target / 100.0) * 100L;
                        target = Math.max(0, Math.min(dur, target));
                        scrubTo(target);
                        showSeekInfo(target, target - scrubStartPos, SCRUB_LABELS[zone]);
                        break;
                    }
                    case GESTURE_BRIGHTNESS: {
                        brightness = Math.max(0.01f, Math.min(1f, brightness + dy / (h * 0.7f)));
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.screenBrightness = brightness;
                        getWindow().setAttributes(lp);
                        int pct = Math.round(brightness * 100);
                        showGesturePanel(R.drawable.ic_brightness, pct + "%", pct);
                        break;
                    }
                    case GESTURE_VOLUME: {
                        volume = Math.max(0, Math.min(maxVolume, volume + dy / (h * 0.7f) * maxVolume));
                        int v = Math.round(volume);
                        if (v != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                        }
                        int pct = maxVolume == 0 ? 0 : Math.round(100f * v / maxVolume);
                        showGesturePanel(v == 0 ? R.drawable.ic_volume_off : R.drawable.ic_volume,
                                String.valueOf(v), pct);
                        break;
                    }
                    default:
                        break;
                }
                return true;
            }
        });

        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                        if (locked) return false;
                        if (gestureMode == GESTURE_SCRUB) endScrub();
                        gestureMode = GESTURE_ZOOM;
                        return true;
                    }

                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector d) {
                        setZoom(zoom * d.getScaleFactor());
                        showGesturePanel(R.drawable.ic_zoom, Math.round(zoom * 100) + "%", -1);
                        return true;
                    }

                    @Override
                    public void onScaleEnd(@NonNull ScaleGestureDetector d) {
                        if (Math.abs(zoom - 1f) < 0.06f) setZoom(1f);
                    }
                });

        gestureLayer.setOnTouchListener((v, e) -> {
            if (isInPip()) return false;
            scaleDetector.onTouchEvent(e);
            if (!scaleDetector.isInProgress() && gestureMode != GESTURE_ZOOM) {
                detector.onTouchEvent(e);
            }
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                endGesture();
            }
            return true;
        });
    }

    /** -1 = left (rewind), 0 = middle, 1 = right (forward). */
    private int sideOf(float x) {
        float w = gestureLayer.getWidth();
        if (x < w * 0.38f) return -1;
        if (x > w * 0.62f) return 1;
        return 0;
    }

    private void doubleTapSkip(int side) {
        if (player == null || !player.isCurrentMediaItemSeekable()) return;
        lastSkipTapAt = SystemClock.uptimeMillis();
        if (skipAccumDir != side) skipAccumMs = 0;
        skipAccumDir = side;
        skipAccumMs += SKIP_MS;
        skipBy(side * SKIP_MS);
        TextView view = side < 0 ? skipLeft : skipRight;
        (side < 0 ? skipRight : skipLeft).setVisibility(View.GONE);
        view.setText((side < 0 ? "-" : "+") + (skipAccumMs / 1000) + "s");
        view.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSkipFeedback);
        handler.postDelayed(hideSkipFeedback, 800);
    }

    private void endGesture() {
        int mode = gestureMode;
        gestureMode = GESTURE_NONE;
        if (mode == GESTURE_SCRUB) {
            endScrub();
        } else if (mode == GESTURE_BRIGHTNESS || mode == GESTURE_VOLUME || mode == GESTURE_ZOOM) {
            handler.removeCallbacks(hideGesturePanel);
            handler.postDelayed(hideGesturePanel, 700);
        }
    }

    private float currentBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            int sys = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return Math.max(0.05f, sys / 255f);
        } catch (Settings.SettingNotFoundException e) {
            return 0.5f;
        }
    }

    private void showGesturePanel(int icon, String text, int percent) {
        gestureIcon.setImageResource(icon);
        gestureText.setText(text);
        gestureProgress.setVisibility(percent >= 0 ? View.VISIBLE : View.GONE);
        if (percent >= 0) gestureProgress.setProgress(percent);
        gesturePanel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideGesturePanel);
        handler.postDelayed(hideGesturePanel, 900);
    }

    private void showSeekInfo(long target, Long delta, String mode) {
        long dur = player == null ? 0 : player.getDuration();
        boolean fine = scrubZone == SCRUB_FINE && mode != null;
        String text = (fine ? Fmt.timePrecise(target) : Fmt.time(target)) + (dur > 0 ? " / " + Fmt.time(dur) : "");
        if (delta != null) text = (fine ? Fmt.signedTimePrecise(delta) : Fmt.signedTime(delta)) + "\n" + text;
        if (mode != null) text += "\n" + mode;
        seekInfo.setText(text);
        seekInfo.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSeekInfo);
    }

    // =================================================================== dialogs

    private void showSpeedDialog() {
        String[] labels = new String[SPEEDS.length];
        int checked = 2;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = formatSpeed(SPEEDS[i]);
            if (Math.abs(SPEEDS[i] - speed) < 0.01f) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Playback speed")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    speed = SPEEDS[which];
                    if (player != null) player.setPlaybackSpeed(speed);
                    btnSpeed.setText(formatSpeed(speed));
                    d.dismiss();
                })
                .setOnDismissListener(d -> hideSystemUi())
                .show();
    }

    private static String formatSpeed(float s) {
        return (s == (int) s ? String.format(Locale.UK, "%.1f", s) : String.format(Locale.UK, "%.2f", s)
                .replaceAll("0$", "")) + "x";
    }

    private void showTrackDialog(int trackType) {
        if (player == null) return;
        DefaultTrackNameProvider names = new DefaultTrackNameProvider(getResources());
        Tracks tracks = player.getCurrentTracks();
        TrackSelectionParameters params = player.getTrackSelectionParameters();
        boolean disabled = params.disabledTrackTypes.contains(trackType);

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int checked = -1;

        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add("Off");
            actions.add(() -> player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()));
        }
        boolean anySelected = false;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != trackType) continue;
            TrackGroup tg = group.getMediaTrackGroup();
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i, /* allowExceedsCapabilities= */ true)) continue;
                Format f = group.getTrackFormat(i);
                String label = names.getTrackName(f);
                if (f.label == null && f.codecs != null && trackType == C.TRACK_TYPE_AUDIO) {
                    label += "  (" + f.codecs + ")";
                }
                if (group.isTrackSelected(i) && !disabled) {
                    checked = labels.size();
                    anySelected = true;
                }
                labels.add(label);
                final int index = i;
                actions.add(() -> player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(trackType, false)
                        .setOverrideForType(new TrackSelectionOverride(tg, index))
                        .build()));
            }
        }
        if (trackType == C.TRACK_TYPE_TEXT) {
            if (!anySelected) checked = 0;
            labels.add("Load subtitle file (.srt, .vtt, .ass)…");
            actions.add(() -> subtitlePicker.launch(new String[]{"*/*"}));
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "No audio tracks", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(trackType == C.TRACK_TYPE_AUDIO ? "Audio track" : "Subtitles")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    actions.get(which).run();
                    d.dismiss();
                })
                .setOnDismissListener(d -> hideSystemUi())
                .show();
    }

    private void onSubtitlePicked(Uri uri) {
        hideSystemUi();
        if (uri == null || player == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
        }
        externalSubUri = uri;
        externalSubName = VideoRepository.displayName(this, uri);
        long pos = player.getCurrentPosition();
        boolean pwr = player.getPlayWhenReady();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build());
        player.setMediaItem(buildMediaItem(), pos);
        player.prepare();
        player.setPlayWhenReady(pwr);
        Toast.makeText(this, "Subtitles: " + externalSubName, Toast.LENGTH_SHORT).show();
    }

    private void showMoreMenu(View anchor) {
        handler.removeCallbacks(hideControlsRunnable);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_player_menu, null);
        sheet.setContentView(v);
        bindMenuSheet(v, sheet);
        sheet.setOnShowListener(d -> {
            BottomSheetBehavior<?> b = sheet.getBehavior();
            b.setState(BottomSheetBehavior.STATE_EXPANDED);
            b.setSkipCollapsed(true);
        });
        sheet.setOnDismissListener(d -> { hideSystemUi(); scheduleHide(); });
        sheet.show();
    }

    /** Wires the player menu rows. Package-private so tests can render the sheet. */
    void bindMenuSheet(View v, android.app.Dialog sheet) {
        MaterialSwitch swDialogue = v.findViewById(R.id.switch_dialogue);
        MaterialSwitch swBackground = v.findViewById(R.id.switch_background);
        MaterialSwitch swDecoder = v.findViewById(R.id.switch_decoder);
        TextView sleepValue = v.findViewById(R.id.sleep_value);
        swDialogue.setChecked(Prefs.dialogueBoost(this));
        swBackground.setChecked(backgroundAudio);
        swDecoder.setChecked(preferSoftware);
        sleepValue.setText(sleepStatusText());
        boolean canClip = currentUri != null && !isNetwork(currentUri);
        v.findViewById(R.id.row_clip).setEnabled(canClip);
        v.findViewById(R.id.row_clip).setAlpha(canClip ? 1f : 0.4f);
        if (!canClip) ((TextView) v.findViewById(R.id.clip_subtitle)).setText("Available for videos on this device");

        swDialogue.setOnCheckedChangeListener((b, on) -> setDialogueBoost(on));
        v.findViewById(R.id.row_dialogue).setOnClickListener(x -> swDialogue.toggle());
        swBackground.setOnCheckedChangeListener((b, on) -> setBackgroundAudio(on));
        v.findViewById(R.id.row_background).setOnClickListener(x -> swBackground.toggle());
        swDecoder.setOnCheckedChangeListener((b, on) -> { if (on != preferSoftware) setPreferSoftware(on); });
        v.findViewById(R.id.row_decoder).setOnClickListener(x -> swDecoder.toggle());
        v.findViewById(R.id.row_sleep).setOnClickListener(x -> { sheet.dismiss(); showSleepTimerDialog(); });
        v.findViewById(R.id.row_clip).setOnClickListener(x -> { if (canClip) { sheet.dismiss(); openClipEditor(); } });
        v.findViewById(R.id.row_info).setOnClickListener(x -> { sheet.dismiss(); showInfoDialog(); });
    }

    private void setDialogueBoost(boolean on) {
        Prefs.setDialogueBoost(this, on);
        if (dialogueBoost != null) dialogueBoost.setEnabled(on);
        updateBadges();
    }

    private void setBackgroundAudio(boolean on) {
        backgroundAudio = on;
        Prefs.setBackgroundAudio(this, on);
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
            ensurePlaybackService();
        } else {
            PlaybackService.detachAndStop(mediaSession);
        }
        updatePipParams();
    }

    /** Starts the media service while we are in the foreground (required on Android 12+). */
    private void ensurePlaybackService() {
        if (mediaSession == null) return;
        PlaybackService.attach(mediaSession);
        if (!PlaybackService.isRunning()) {
            try {
                startService(new Intent(this, PlaybackService.class));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Couldn't start background playback", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void createSession() {
        NavForwardingPlayer sessionPlayer = new NavForwardingPlayer(player, new NavForwardingPlayer.Navigator() {
            @Override public void next() {
                if (playlistIndex >= 0 && playlistIndex < playlist.size() - 1) playIndex(playlistIndex + 1);
            }
            @Override public void previous() {
                if (player != null && player.getCurrentPosition() > 5000) fastSeekExact(0);
                else if (playlistIndex > 0) playIndex(playlistIndex - 1);
                else fastSeekExact(0);
            }
        });
        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        ImmutableList<CommandButton> buttons = ImmutableList.of(
                new CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK).setDisplayName("Back 10 s")
                        .setSlots(CommandButton.SLOT_BACK).build(),
                new CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setDisplayName("Forward 10 s")
                        .setSlots(CommandButton.SLOT_FORWARD).build(),
                new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS).setDisplayName("Previous")
                        .setSlots(CommandButton.SLOT_OVERFLOW).build(),
                new CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT).setDisplayName("Next")
                        .setSlots(CommandButton.SLOT_OVERFLOW).build());
        try {
            mediaSession = new MediaSession.Builder(this, sessionPlayer)
                    .setId("player-" + SystemClock.elapsedRealtime())
                    .setSessionActivity(pi)
                    .setMediaButtonPreferences(buttons)
                    .build();
            if (backgroundAudio) ensurePlaybackService();
        } catch (RuntimeException e) {
            mediaSession = null; // the player still works without a session
        }
    }

    private void updateBadges() {
        if (badgeNight == null) return;
        badgeNight.setVisibility(Prefs.dialogueBoost(this) ? View.VISIBLE : View.GONE);
        boolean sleepOn = sleepEndElapsed > 0 || sleepEndOfVideo;
        badgeSleep.setVisibility(sleepOn ? View.VISIBLE : View.GONE);
        if (sleepOn) badgeSleep.setText(sleepEndOfVideo ? "End" : Fmt.time(Math.max(0, sleepEndElapsed - SystemClock.elapsedRealtime())));
    }

    // =================================================================== sleep timer

    private String sleepStatusText() {
        if (sleepEndOfVideo) return "At the end of this video";
        if (sleepEndElapsed > 0) return Fmt.time(sleepEndElapsed - SystemClock.elapsedRealtime()) + " left";
        return "Off";
    }

    void showSleepTimerDialog() {
        String[] labels = {"Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of video", "Custom…"};
        int checked = sleepEndOfVideo ? 5 : sleepEndElapsed > 0 ? -1 : 0;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Sleep timer")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    switch (which) {
                        case 0: cancelSleepTimer(); break;
                        case 1: startSleepTimer(15); break;
                        case 2: startSleepTimer(30); break;
                        case 3: startSleepTimer(45); break;
                        case 4: startSleepTimer(60); break;
                        case 5: startSleepAtEnd(); break;
                        default: showCustomSleepDialog(); break;
                    }
                })
                .setOnDismissListener(d -> hideSystemUi())
                .show();
    }

    private void showCustomSleepDialog() {
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(1);
        picker.setMaxValue(240);
        picker.setValue(20);
        picker.setWrapSelectorWheel(false);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.addView(picker, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Stop playback after (minutes)")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Start", (d, w) -> startSleepTimer(picker.getValue()))
                .setOnDismissListener(d -> hideSystemUi())
                .show();
    }

    void startSleepTimer(int minutes) {
        cancelSleepTimer();
        sleepEndElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L;
        handler.post(sleepTick);
        Toast.makeText(this, "Sleep timer: " + minutes + " min", Toast.LENGTH_SHORT).show();
        updateBadges();
    }

    private void startSleepAtEnd() {
        cancelSleepTimer();
        sleepEndOfVideo = true;
        handler.post(sleepTick);
        Toast.makeText(this, "Sleep timer: end of video", Toast.LENGTH_SHORT).show();
        updateBadges();
    }

    private void cancelSleepTimer() {
        handler.removeCallbacks(sleepTick);
        sleepEndElapsed = 0;
        sleepEndOfVideo = false;
        if (player != null) player.setVolume(1f);
        updateBadges();
    }

    /** Runs every 250 ms (also in the background): fades out over the last 10 s, then pauses. */
    private void onSleepTick() {
        if (player == null || (sleepEndElapsed == 0 && !sleepEndOfVideo)) return;
        long remaining;
        if (sleepEndOfVideo) {
            long dur = player.getDuration();
            remaining = dur == C.TIME_UNSET ? Long.MAX_VALUE
                    : (long) ((dur - player.getCurrentPosition()) / Math.max(0.1f, speed));
        } else {
            remaining = sleepEndElapsed - SystemClock.elapsedRealtime();
        }
        if (remaining <= SLEEP_FADE_MS) {
            player.setVolume(Math.max(0f, remaining / (float) SLEEP_FADE_MS));
        }
        if (!sleepEndOfVideo && remaining <= 0) {
            player.pause();
            saveResume();
            cancelSleepTimer();
            Toast.makeText(this, "Sleep timer: playback paused", Toast.LENGTH_SHORT).show();
            return;
        }
        updateBadges();
        handler.postDelayed(sleepTick, 250);
    }

    // =================================================================== clip editor

    private void openClipEditor() {
        if (player == null || currentUri == null) return;
        Intent i = new Intent(this, ClipEditorActivity.class);
        i.setData(currentUri);
        i.putExtra(ClipEditorActivity.EXTRA_TITLE, currentTitle);
        i.putExtra(ClipEditorActivity.EXTRA_POSITION_MS, player.getCurrentPosition());
        if (player.getDuration() > 0) i.putExtra(ClipEditorActivity.EXTRA_DURATION_MS, player.getDuration());
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        player.pause();
        startActivity(i);
    }

    private void showInfoDialog() {
        if (player == null) return;
        Format v = player.getVideoFormat();
        Format a = player.getAudioFormat();
        StringBuilder sb = new StringBuilder();
        if (v != null) {
            sb.append("Video: ").append(v.sampleMimeType).append("  ").append(v.width).append("×").append(v.height);
            if (v.frameRate > 0) sb.append(String.format(Locale.UK, "  %.2f fps", v.frameRate));
            if (v.bitrate > 0) sb.append(String.format(Locale.UK, "  %.1f Mbit/s", v.bitrate / 1e6));
            sb.append("\nVideo decoder: ").append(videoDecoderName);
        }
        if (a != null) {
            sb.append("\n\nAudio: ").append(a.sampleMimeType).append("  ").append(a.channelCount).append("ch  ")
                    .append(a.sampleRate).append(" Hz");
            sb.append("\nAudio decoder: ").append(audioDecoderName);
        }
        sb.append("\n\nDropped frames: ").append(droppedFrames);
        sb.append("\nBuffered ahead: ").append((player.getBufferedPosition() - player.getCurrentPosition()) / 1000).append(" s");
        sb.append("\nBuffer profile: ").append(heavyProfile ? "high-bitrate local (10 s back / 10–30 s ahead)"
                : "30 s back / 30–120 s ahead");
        sb.append("\nDecoder preference: ").append(preferSoftware ? "software first" : "hardware first (auto fallback)");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Playback info")
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> hideSystemUi())
                .show();
    }

    // =================================================================== resume

    private void saveResume() {
        if (player == null || currentKey == null) return;
        if (player.isCurrentMediaItemLive()) return;
        long dur = player.getDuration();
        long pos = scrubbing ? scrubTarget : player.getCurrentPosition();
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            Prefs.clearResume(this, currentKey);
            return;
        }
        if (player.getPlaybackState() == Player.STATE_IDLE && pos == 0) return;
        Prefs.saveResume(this, currentKey, pos, dur == C.TIME_UNSET ? 0 : dur);
    }

    // =================================================================== picture-in-picture

    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private boolean isInPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    private void enterPip() {
        if (!pipSupported() || player == null) return;
        try {
            setControlsVisible(false);
            enterPictureInPictureMode(buildPipParams());
        } catch (RuntimeException e) {
            Toast.makeText(this, "Picture-in-picture unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    @RequiresApi(26)
    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        if (player != null) {
            VideoSize vs = player.getVideoSize();
            if (vs.width > 0 && vs.height > 0) {
                float ratio = vs.width * vs.pixelWidthHeightRatio / vs.height;
                ratio = Math.max(1f / 2.39f, Math.min(2.39f, ratio));
                b.setAspectRatio(new Rational(Math.round(ratio * 1000), 1000));
            }
        }
        List<RemoteAction> actions = new ArrayList<>();
        actions.add(pipAction(R.drawable.ic_fast_rewind, "Back 10 s", PIP_REWIND));
        boolean playing = player != null && player.getPlayWhenReady();
        actions.add(pipAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                playing ? "Pause" : "Play", PIP_PLAY_PAUSE));
        actions.add(pipAction(R.drawable.ic_fast_forward, "Forward 10 s", PIP_FORWARD));
        b.setActions(actions);
        Rect hint = new Rect();
        if (playerView.getGlobalVisibleRect(hint)) b.setSourceRectHint(hint);
        if (Build.VERSION.SDK_INT >= 31) {
            // With background audio on, leaving the app keeps audio playing instead of PiP.
            b.setAutoEnterEnabled(player != null && player.isPlaying() && !backgroundAudio);
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    @RequiresApi(26)
    private RemoteAction pipAction(int icon, String title, int control) {
        Intent intent = new Intent(ACTION_PIP).setPackage(getPackageName()).putExtra(EXTRA_PIP, control);
        PendingIntent pi = PendingIntent.getBroadcast(this, control, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new RemoteAction(Icon.createWithResource(this, icon), title, title, pi);
    }

    private void updatePipParams() {
        if (!pipSupported() || player == null) return;
        try {
            setPictureInPictureParams(buildPipParams());
        } catch (RuntimeException ignored) {
        }
    }

    private void registerPipReceiver() {
        pipReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (player == null) return;
                switch (intent.getIntExtra(EXTRA_PIP, 0)) {
                    case PIP_PLAY_PAUSE: togglePlay(); break;
                    case PIP_REWIND: skipBy(-SKIP_MS); break;
                    case PIP_FORWARD: skipBy(SKIP_MS); break;
                    default: break;
                }
                updatePipParams();
            }
        };
        ContextCompat.registerReceiver(this, pipReceiver, new IntentFilter(ACTION_PIP),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 12+ uses setAutoEnterEnabled; older versions enter PiP manually.
        if (Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT < 31
                && player != null && player.isPlaying() && !locked && !backgroundAudio) {
            enterPip();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        if (inPip) {
            handler.removeCallbacks(hideControlsRunnable);
            controls.setVisibility(View.GONE);
            controlsVisible = false;
            gesturePanel.setVisibility(View.GONE);
            seekInfo.setVisibility(View.GONE);
            btnUnlock.setVisibility(View.GONE);
            setZoom(1f);
        } else {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                // PiP window was dismissed.
                if (player != null) player.pause();
                saveResume();
            } else {
                applyAspect(false);
                showControls();
            }
        }
    }
}
