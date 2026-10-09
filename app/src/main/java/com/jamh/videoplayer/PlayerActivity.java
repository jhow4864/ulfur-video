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
import android.view.ViewGroup;
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
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
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
import androidx.media3.exoplayer.DecoderCounters;
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
    /** 1.4.2: how long the thumbnail sweep stays paused after a seek so the seek isn't competing for the decoder. */
    private static final long SEEK_THUMB_HOLDOFF_MS = 4_000;
    private static final long SCRUB_THROTTLE_MS = 80;
    private static final long FINE_SCRUB_THROTTLE_MS = 120;
    private static final int SCRUB_NORMAL = 0, SCRUB_HALF = 1, SCRUB_FINE = 2;
    private static final String[] SCRUB_LABELS = {"Normal scrubbing", "Half-speed scrubbing", "Fine scrubbing · 0.1 s"};
    private static final long SLEEP_FADE_MS = 10_000;
    private static final long SPINNER_DELAY_MS = 500;
    private static final long HIDE_DELAY_MS = 3_500;
    /** Longest we wait for the URI probe (cloud providers can be slow) before starting anyway. */
    private static final long PROBE_TIMEOUT_MS = 1_200;
    /** Upper bound on items taken from one share (each one is probed). */
    private static final int MAX_SHARED_ITEMS = 200;

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
    // Private (vault) playback: FLAG_SECURE, no resume/recents/shortcut, no PiP/background audio
    private boolean currentPrivate;
    private androidx.biometric.BiometricPrompt vaultPrompt;
    private boolean vaultAuthInProgress;
    // Scrub thumbnails (local files only)
    private View scrubPreview;
    private ImageView scrubPreviewImage;
    private View scrubPreviewFrame;
    private TextView scrubPreviewTime;
    @androidx.annotation.Nullable private ScrubThumbnails thumbs;
    private Uri thumbsUri;
    private long previewPos = -1;
    private int previewShownIndex = -1;
    private float previewX, previewY;
    private boolean previewFromSeekBar;
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
    /** Local buffer tier ({@link PlayerFactory#LOCAL_TIERS}); chosen from the probed bitrate. */
    private int bufferTier = PlayerFactory.TIER_UNKNOWN;
    private boolean profileRebuildPending;
    private int pendingBufferTier;
    /** Player released in onStop (nothing visible or audible): rebuilt in onStart. */
    private boolean releasedInStop;
    private long stoppedPositionMs;
    /** Incremented per handled intent so late async results for an older intent are ignored. */
    private int intentToken;
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
    /** 1.4.2: target of the last keyframe seek issued during the current scrub (-1 = none yet). */
    private long lastScrubSeekTarget = -1;

    // 1.5.0 real-time upscaling (GPU shader via Media3 video effects; nothing is saved)
    private int upscaleMode = Upscaler.MODE_OFF;
    private int upscaleQuality = Upscaler.QUALITY_FSR;
    /** The current player was built with the effects pipeline (setVideoEffects before prepare). */
    private boolean upscalePipeline;
    /** Auto-disabled by the frame-drop watchdog for the rest of this session. */
    private boolean upscaleSessionOff;
    /** The current video is HDR: never processed (the player is built without the pipeline). */
    private boolean currentItemHdr;
    private int upscaleGlFailures;
    private int effectBoxW, effectBoxH;
    private String appliedEffectsKey = "";
    private final Upscaler.Watchdog upscaleWatchdog = new Upscaler.Watchdog();
    private long lastPlaybackDisturbanceAt;
    private long watchdogLastDropped = -1;
    private long watchdogLastAt;
    private ImageButton btnUpscale;
    private final Runnable upscaleWatchdogTick = this::onUpscaleWatchdogTick;
    private final Runnable applyUpscaleRunnable = this::applyUpscaleEffects;

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

    // 1.6 bookmarks / remembered speed / subtitles (stable per-video key: VideoKey)
    /** Content fingerprint of the current video (null until computed on the io thread). */
    @androidx.annotation.Nullable private String videoKey;
    private BookmarkStore bookmarkStore;
    private BookmarkTicksView bookmarkTicks;
    /** The user picked a speed for this video, so a late remembered speed must not override it. */
    private boolean speedChosenForVideo;
    /** Keep the restored speed once (process death) instead of resetting it in startMedia. */
    private boolean keepSpeedOnce;
    /** Bookmark seeks use the 1.4.2 instant (keyframe) path. */
    static final SeekParameters BOOKMARK_SEEK = SeekParameters.CLOSEST_SYNC;

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
                    Toast.makeText(this, "Background audio is on. Playback controls stay available in "
                            + "the media controls and on the lock screen.", Toast.LENGTH_LONG).show();
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
        upscaleMode = Prefs.upscaleMode(this);
        updateBadges();
        // Open in whatever orientation the phone is in and follow the system auto-rotate setting
        // (no forced landscape). "Auto-rotate to match video" in the menu restores the old behaviour.
        applyOrientation();
        if (playerView.getSubtitleView() != null) {
            playerView.getSubtitleView().setStyle(new CaptionStyleCompat(0xFFFFFFFF, 0x00000000,
                    0x00000000, CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000, null));
            playerView.getSubtitleView().setFractionalTextSize(
                    PlaybackMemory.subtitleFraction(PlaybackMemory.subtitleSize(this)));
        }
        bookmarkStore = new BookmarkStore(this);

        setupControls();
        setupGestures();
        registerPipReceiver();
        vaultPrompt = VaultAuth.create(this, new VaultAuth.Callback() {
            @Override public void onUnlocked() {
                vaultAuthInProgress = false;
                uncoverPrivate();
            }

            @Override public void onFailed(boolean noScreenLock, CharSequence message) {
                vaultAuthInProgress = false;
                finish(); // no unlock, no private video
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (locked) {
                    flashUnlockButton();
                    return;
                }
                finish();
            }
        });

        Intent toHandle = getIntent();
        if (savedInstanceState != null && savedInstanceState.getParcelable("cur_uri") != null) {
            // Process death: reopen what was actually playing (after Next / auto-advance), not the
            // launch intent; the position comes from the saved resume point.
            Uri cur = savedInstanceState.getParcelable("cur_uri");
            long vid = savedInstanceState.getLong("cur_video_id", -1);
            toHandle = new Intent(Intent.ACTION_VIEW).setDataAndType(cur, savedInstanceState.getString("cur_mime"));
            if (vid >= 0) toHandle.putExtra(EXTRA_VIDEO_ID, vid);
            toHandle.putExtra(EXTRA_SORT, getIntent().getIntExtra(EXTRA_SORT, Sort.NAME));
            speed = savedInstanceState.getFloat("speed", 1f);
            keepSpeedOnce = true;
            aspectMode = savedInstanceState.getInt("aspect", 0);
            btnSpeed.setText(formatSpeed(speed));
        }
        handleIntent(toHandle);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(DefaultPlayerHelper.EXTRA_DEFAULT_SETUP, false)) {
            moveTaskToBack(true); // keep the current video; return to the library
            return;
        }
        if (urisFromIntent(intent).isEmpty()) return; // tapped the media notification
        setIntent(intent);
        saveResume();
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(progressRunnable);
        handler.removeCallbacks(upscaleWatchdogTick);
        watchdogLastDropped = -1;
        handler.postDelayed(upscaleWatchdogTick, Upscaler.Watchdog.WINDOW_MS);
        if (currentPrivate && !VaultLock.isUnlocked()) coverForUnlock();
        if (releasedInStop) {
            releasedInStop = false;
            profileRebuildPending = false;
            if (player == null && currentUri != null) {
                // Decoders and buffers were released while stopped; come back paused where we were.
                rebuildPlayer();
                player.setWakeMode(isNetwork(currentUri) ? C.WAKE_MODE_NETWORK : C.WAKE_MODE_LOCAL);
                player.setMediaItem(buildMediaItem(), stoppedPositionMs);
                player.prepare();
                player.setPlayWhenReady(false);
                updateNavButtons();
                showControls();
            }
            videoDisabledForBackground = false;
        }
        if (profileRebuildPending && player != null) {
            profileRebuildPending = false;
            if (pendingBufferTier != bufferTier) {
                long pos = player.getCurrentPosition();
                boolean pwr = player.getPlayWhenReady();
                bufferTier = pendingBufferTier;
                rebuildPlayer(); // fresh player: video track enabled by default
                videoDisabledForBackground = false;
                player.setMediaItem(buildMediaItem(), pos);
                player.prepare();
                player.setPlayWhenReady(pwr);
            }
        }
        if (sleepEndElapsed > 0 || sleepEndOfVideo) {
            handler.removeCallbacks(sleepTick);
            handler.post(sleepTick);
        }
        if (videoDisabledForBackground && player != null) {
            videoDisabledForBackground = false;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scrubbing || gestureMode != GESTURE_NONE) {
            endGesture();
            endScrub(); // no-op if already ended; seek-bar scrubs have gestureMode == NONE
        }
        saveResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(progressRunnable);
        if (!isInPip()) handler.removeCallbacks(upscaleWatchdogTick);
        if (!isInPip()) stopThumbnails(); // left the player: cancel thumbnail extraction
        if (!isInPip() && player != null) {
            saveResume();
            if (backgroundAudio && !currentPrivate && player.getPlayWhenReady() && !isFinishing()) {
                // Keep the audio going; stop decoding video while nothing is visible.
                ensurePlaybackService();
                videoDisabledForBackground = true;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
            } else {
                player.pause();
                if (!isFinishing()) releaseDecoders();
            }
        }
    }

    /**
     * Frees MediaCodec instances and the (up to ~160 MB) buffer while nothing is visible or
     * audible, e.g. when going back to the library or the Clips feed. onStart rebuilds.
     */
    private void releaseDecoders() {
        if (player == null) return;
        stoppedPositionMs = player.getCurrentPosition();
        saveResume();
        PlaybackService.detachAndStop(mediaSession);
        releasePlayer();
        releasedInStop = true;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (currentUri == null) return;
        out.putParcelable("cur_uri", currentUri);
        out.putLong("cur_video_id", playlistVideoId);
        out.putString("cur_mime", currentMime);
        out.putFloat("speed", speed);
        out.putInt("aspect", aspectMode);
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
        stopThumbnails();
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
        scrubPreview = findViewById(R.id.scrub_preview);
        scrubPreviewImage = findViewById(R.id.scrub_preview_image);
        scrubPreviewFrame = findViewById(R.id.scrub_preview_frame);
        scrubPreviewTime = findViewById(R.id.scrub_preview_time);
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
        bookmarkTicks = findViewById(R.id.bookmark_ticks);
        bookmarkTicks.attachTo(seekBar);
    }

    // =================================================================== intent / playlist

    /** Video URIs carried by VIEW (data), SEND (EXTRA_STREAM) or SEND_MULTIPLE intents. */
    @SuppressWarnings("deprecation")
    static List<Uri> urisFromIntent(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null) out.add(u);
        } else if (intent.getData() != null) {
            out.add(intent.getData());
        }
        if (out.isEmpty() && intent.getClipData() != null
                && (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action))) {
            android.content.ClipData cd = intent.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        if (out.size() > MAX_SHARED_ITEMS) out = new ArrayList<>(out.subList(0, MAX_SHARED_ITEMS));
        return out;
    }

    private void handleIntent(Intent intent) {
        List<Uri> uris = urisFromIntent(intent);
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        boolean shared = Intent.ACTION_SEND.equals(intent.getAction())
                || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction());
        externalSubUri = null;
        externalSubName = null;
        currentMime = shared ? null : intent.getType();
        orientationChosenForVideo = false;
        playlist.clear();
        playlistIndex = -1;
        updateNavButtons();

        releasedInStop = false;  // the new item builds its own player; don't restore the old one
        if (player != null) player.pause();  // old video stops while the new one resolves
        final long id = intent.getLongExtra(EXTRA_VIDEO_ID, -1);
        final int sort = intent.getIntExtra(EXTRA_SORT, Sort.NAME);
        final int token = ++intentToken;
        final Uri first = uris.get(0);
        setPrivateMode(Vault.isVaultUri(first));

        if (currentPrivate) {
            if (!VaultLock.isUnlocked()) coverForUnlock(); // e.g. reopened from Recents after locking
            titleView.setText(quickTitle(first));
            final Context app = getApplicationContext();
            io.execute(() -> {
                // The other private videos become the playlist; nothing is looked up in MediaStore.
                List<Vault.Entry> entries = Vault.list(app);
                List<VideoItem> items = new ArrayList<>();
                int start = -1;
                for (int i = 0; i < entries.size(); i++) {
                    Vault.Entry e = entries.get(i);
                    Uri u;
                    try {
                        u = Vault.uriFor(app, e);
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    String t = e.name;
                    int dot = t.lastIndexOf('.');
                    if (dot > 0) t = t.substring(0, dot);
                    if (e.file.equals(first.getLastPathSegment())) start = items.size();
                    items.add(new VideoItem(-2 - i, u, t, e.durationMs, e.size, e.addedMs / 1000, "", "", e.width, e.height));
                }
                final int startIndex = start;
                handler.post(() -> {
                    if (token != intentToken || isDestroyed()) return;
                    if (startIndex < 0) {
                        Toast.makeText(this, "This private video is no longer available", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    playlist.clear();
                    playlist.addAll(items);
                    playlistIndex = startIndex;
                    VideoItem v = items.get(startIndex);
                    playlistVideoId = v.id;
                    startMedia(v.uri, v.resumeKey(), v.title, v.bitrateBps());
                });
            });
            return;
        }

        if (uris.size() == 1 && isNetwork(first) && id < 0) {
            // Network stream: nothing to resolve or probe; streaming buffer profile.
            playlistVideoId = -1;
            String name = first.getLastPathSegment();
            startMedia(first, first.toString(), name == null ? first.toString() : name, -1);
            return;
        }

        titleView.setText(quickTitle(first));
        final Context app = getApplicationContext();
        final boolean[] started = {false};
        // Slow provider (cloud-only file, sleeping SD card...): start with the safe tier rather
        // than keeping the user waiting; the probe result then only updates the title.
        final Runnable fallback = () -> {
            if (token != intentToken || started[0] || isDestroyed()) return;
            started[0] = true;
            playlistVideoId = -1;
            startMedia(first, first.toString(), quickTitle(first), -1);
        };
        handler.postDelayed(fallback, PROBE_TIMEOUT_MS);

        io.execute(() -> {
            // MediaStore lookups, display names and the probe all do provider I/O: never on main.
            final VideoItem item = resolveItem(app, first, id, -1);
            handler.post(() -> {
                if (token != intentToken || isDestroyed()) return;
                handler.removeCallbacks(fallback);
                if (!started[0]) {
                    started[0] = true;
                    playlistVideoId = item.id;
                    startMedia(item.uri, item.resumeKey(), item.title, item.bitrateBps());
                } else if (first.equals(currentUri)) {
                    currentTitle = item.title;
                    titleView.setText(item.title);
                }
            });
            if (uris.size() > 1) {
                // Shared selection: play the videos in the order they were shared.
                List<VideoItem> list = new ArrayList<>();
                list.add(item);
                for (int i = 1; i < uris.size(); i++) {
                    if (token != intentToken) return;
                    Uri u = uris.get(i);
                    String type = null;
                    try {
                        type = app.getContentResolver().getType(u);
                    } catch (RuntimeException ignored) {
                    }
                    if (type != null && !type.startsWith("video/")) continue; // e.g. photos in a mixed share
                    list.add(resolveItem(app, u, -1, i));
                }
                handler.post(() -> {
                    if (token != intentToken || isDestroyed()) return;
                    playlist.clear();
                    playlist.addAll(list);
                    playlistIndex = 0;
                    for (int i = 0; i < list.size(); i++) if (list.get(i).id == playlistVideoId) playlistIndex = i;
                    updateNavButtons();
                    Toast.makeText(this, list.size() + " videos in this playlist", Toast.LENGTH_SHORT).show();
                });
            } else if (item.id >= 0) {
                // A single MediaStore video: its folder becomes the playlist.
                List<VideoItem> list = VideoRepository.inBucket(app, item.bucketId);
                Sort.videos(list, sort);
                final long vid = item.id;
                handler.post(() -> {
                    if (token != intentToken || isDestroyed() || playlistVideoId != vid) return;
                    playlist.clear();
                    playlist.addAll(list);
                    playlistIndex = -1;
                    for (int i = 0; i < list.size(); i++) if (list.get(i).id == vid) playlistIndex = i;
                    updateNavButtons();
                });
            }
        });
    }

    private static String quickTitle(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null || last.isEmpty() || last.matches("\\d+")) return "Video";
        int slash = last.lastIndexOf('/');
        if (slash >= 0) last = last.substring(slash + 1);
        int dot = last.lastIndexOf('.');
        return dot > 0 ? last.substring(0, dot) : last;
    }

    /**
     * Worker thread: maps a URI to a MediaStore row when possible (keeps resume keys and folder
     * playlists), otherwise probes size/duration/bitrate so the buffers can be sized (P0 fix:
     * files from Photos/Files/Drive are no longer treated as "normal bitrate" blindly).
     */
    @androidx.annotation.WorkerThread
    static VideoItem resolveItem(Context app, Uri uri, long mediaStoreId, int index) {
        VideoItem ms = null;
        if (mediaStoreId >= 0) {
            List<VideoItem> l = VideoRepository.query(app, android.provider.MediaStore.Video.Media._ID + "=?",
                    new String[]{String.valueOf(mediaStoreId)});
            ms = l.isEmpty() ? null : l.get(0);
            if (ms != null) return ms;            // library launch: play the MediaStore URI
        } else if (!isNetwork(uri)) {
            ms = VideoRepository.resolve(app, uri);
            // Keep playing through the caller's URI (it carries the read grant).
            if (ms != null) return ms.withUri(uri);
        }
        if (isNetwork(uri)) {
            String n = uri.getLastPathSegment();
            return new VideoItem(-2 - Math.max(0, index), uri, n == null ? uri.toString() : n, -1, -1, 0, "", "", 0, 0);
        }
        MediaProbe.Result r = MediaProbe.probe(app, uri);
        String name = r.displayName != null ? r.displayName : VideoRepository.displayName(app, uri);
        long bitrate = r.effectiveBitrate();
        // VideoItem derives bitrate from size/duration; when only the container bitrate is known,
        // synthesise a consistent size so bitrateBps() reports it.
        long size = r.sizeBytes, dur = r.durationMs;
        if (bitrate > 0 && (size <= 0 || dur <= 0)) {
            dur = dur > 0 ? dur : 1000;
            size = bitrate * dur / 8_000L;
        }
        return new VideoItem(-2 - Math.max(0, index), uri, name, dur, size, 0, "", "", r.width, r.height);
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
        startMedia(v.uri, v.resumeKey(), v.title, v.bitrateBps());
        updateNavButtons();
        showControls();
    }

    /** @param bitrateBps average bitrate of a local file (-1 = unknown → safe tier). */
    private void startMedia(Uri uri, String key, String title, long bitrateBps) {
        if (thumbsUri != null && !thumbsUri.equals(uri)) stopThumbnails();
        currentUri = uri;
        currentKey = key;
        currentTitle = title;
        titleView.setText(title);
        boolean local = !isNetwork(uri);
        int wantTier = local
                ? PlayerFactory.tierForBitrate(bitrateBps, PlayerFactory.bufferBudgetBytes())
                : PlayerFactory.TIER_NETWORK;
        releasedInStop = false;
        currentItemHdr = false; // re-detected in onTracksChanged
        boolean started = getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
        if (player == null) {
            bufferTier = wantTier;
            rebuildPlayer();
        } else if (started && upscaleWanted() != upscalePipeline) {
            // 1.5.0: the effects pipeline can only be added/removed when the player is built.
            bufferTier = wantTier;
            rebuildPlayer();
        } else if (wantTier != bufferTier) {
            if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                bufferTier = wantTier;
                rebuildPlayer();
            } else {
                // Background audio auto-advance: rebuilding would release the MediaSession and drop
                // the media foreground service (can't be re-promoted from the background on 12+).
                // Video is disabled in the background, so the buffer is audio-only; rebuild in onStart.
                pendingBufferTier = wantTier;
                profileRebuildPending = true;
            }
        }
        prepareVideoMemory(uri, title);
        long start = 0;
        long[] resume = currentPrivate ? null : Prefs.getResume(this, key);
        if (resume != null && resume[0] > 3000 && (resume[1] <= 0 || resume[0] < resume[1] - 5000)) {
            start = resume[0];
        }
        player.setWakeMode(local ? C.WAKE_MODE_LOCAL : C.WAKE_MODE_NETWORK);
        player.setMediaItem(buildMediaItem(), start);
        player.prepare();
        player.play();
        if (currentPrivate && !VaultLock.isUnlocked()) player.pause(); // waits behind the unlock prompt
        droppedFrames = 0;
        if (start > 0) {
            Snackbar.make(root, "Resumed from " + Fmt.time(start), Snackbar.LENGTH_LONG)
                    .setAction("Start over", v -> fastSeekExact(0))
                    .show();
        }
        updateNavButtons();
        showControls();
        if (currentPrivate) return; // private videos never become a launcher shortcut
        final Uri u = uri;
        final long vid = playlistVideoId;
        final VideoItem thumb = playlistIndex >= 0 && playlistIndex < playlist.size()
                && playlist.get(playlistIndex).id == vid ? playlist.get(playlistIndex) : null;
        final Context app = getApplicationContext();
        io.execute(() -> {
            VideoItem src = thumb;
            if (src == null && vid >= 0) {
                List<VideoItem> l = VideoRepository.query(app, android.provider.MediaStore.Video.Media._ID + "=?",
                        new String[]{String.valueOf(vid)});
                src = l.isEmpty() ? null : l.get(0);
            }
            Shortcuts.pushContinueWatching(app, src != null ? src.uri : u, vid, title, src);
        });
    }

    private MediaItem buildMediaItem() {
        MediaItem.Builder b = new MediaItem.Builder()
                .setUri(currentUri)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(currentPrivate ? "Private video" : currentTitle).build());
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
        player = PlayerFactory.create(this, preferSoftware, bufferTier, dialogueBoost);
        // 1.5.0: video effects must be set before prepare() to create the GL pipeline. Only done when
        // upscaling is wanted, so with Upscale off (and for HDR videos) playback is exactly as before.
        upscalePipeline = upscaleWanted();
        appliedEffectsKey = "";
        if (upscalePipeline) applyUpscaleEffects();
        upscaleWatchdog.reset();
        watchdogLastDropped = -1;
        player.addListener(playerListener);
        createSession();
        player.addAnalyticsListener(analyticsListener);
        player.setPlaybackSpeed(speed);
        if (!PlaybackMemory.subtitlesOn(this)) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        }
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
            if (state == Player.STATE_BUFFERING && thumbs != null) thumbs.holdOff(SEEK_THUMB_HOLDOFF_MS);
            if (state != Player.STATE_READY) lastPlaybackDisturbanceAt = SystemClock.elapsedRealtime();
            if (state == Player.STATE_BUFFERING && !scrubbing) {
                // Only show the spinner if buffering lasts longer than ~500 ms.
                handler.removeCallbacks(showSpinnerRunnable);
                handler.postDelayed(showSpinnerRunnable, SPINNER_DELAY_MS);
            } else {
                handler.removeCallbacks(showSpinnerRunnable);
                spinner.setVisibility(View.GONE);
            }
            if (state == Player.STATE_READY) maybeStartThumbnails();
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
            if (Prefs.autoRotateToVideo(PlayerActivity.this)
                    && !orientationChosenByUser && !orientationChosenForVideo) {
                orientationChosenForVideo = true;
                portrait = size.height * 1f > size.width * size.pixelWidthHeightRatio;
                applyOrientation();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            if (upscalePipeline && isVideoEffectsError(error)) {
                handler.post(PlayerActivity.this::onUpscaleGlFailure);
                return;
            }
            showErrorDialog(error);
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            boolean hdr = isHdr(tracks);
            if (hdr == currentItemHdr) return;
            currentItemHdr = hdr;
            if (hdr && upscalePipeline) {
                // HDR is never upscaled: rebuild without the GL pipeline so HDR output is untouched.
                handler.post(() -> {
                    rebuildAtCurrentPosition();
                    if (upscaleMode != Upscaler.MODE_OFF) {
                        showUpscaleToast("Upscale paused", "HDR video: shown as-is");
                    }
                });
            }
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                            @NonNull Player.PositionInfo newPosition, int reason) {
            lastPlaybackDisturbanceAt = SystemClock.elapsedRealtime();
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
        if (thumbs != null) thumbs.holdOff(SEEK_THUMB_HOLDOFF_MS);
        player.seekTo(target);
        player.setSeekParameters(SeekParameters.EXACT); // queued after the seek on the playback thread
        updateProgressUi(target);
    }

    static final int RELEASE_NONE = 0, RELEASE_SNAP = 1, RELEASE_EXACT = 2;

    /** What to do when a scrub ends (pure, unit-tested). */
    static int releaseSeekMode(boolean fine, long lastPreviewSeekTarget, long finalTarget) {
        if (fine) return RELEASE_EXACT;
        if (lastPreviewSeekTarget == finalTarget) return RELEASE_NONE;
        return RELEASE_SNAP;
    }

    /** Starts a scrub: enables Media3 scrubbing mode and keyframe (CLOSEST_SYNC) seeks. */
    private void beginScrub() {
        if (player == null || scrubbing) return;
        scrubbing = true;
        scrubTarget = player.getCurrentPosition();
        lastScrubSeekAt = 0;
        lastScrubSeekTarget = -1;
        handler.removeCallbacks(hideControlsRunnable);
        handler.removeCallbacks(showSpinnerRunnable);
        spinner.setVisibility(View.GONE);
        player.setScrubbingModeEnabled(true);
        if (thumbs != null) thumbs.setScrubbing(true);
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
        lastScrubSeekTarget = scrubTarget;
        player.seekTo(scrubTarget);
    }

    /**
     * Ends a scrub. 1.4.2: lands on the keyframe the preview already showed instead of a second, EXACT seek.
     * Before, every release (and every tap on the seek bar) did a keyframe seek and then an EXACT one, and the
     * EXACT one decodes every frame from the previous keyframe up to the target before playing, which takes
     * seconds on long-GOP files (movies, screen recordings, 4K/HEVC). Fine (0.1 s) scrubbing stays exact.
     */
    private void endScrub() {
        if (!scrubbing) return;
        scrubbing = false;
        handler.removeCallbacks(scrubSeekRunnable);
        if (player != null) {
            player.setScrubbingModeEnabled(false);
            if (thumbs != null) thumbs.holdOff(SEEK_THUMB_HOLDOFF_MS);
            int mode = releaseSeekMode(scrubZone == SCRUB_FINE, lastScrubSeekTarget, scrubTarget);
            if (mode == RELEASE_EXACT) {
                fastSeekExact(scrubTarget);
            } else if (mode == RELEASE_SNAP) {
                // The finger moved after the last preview seek: one keyframe seek to the final spot.
                player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
                player.seekTo(scrubTarget);
                player.setSeekParameters(SeekParameters.EXACT);   // queued after the seek
            } else {
                // Already at the previewed keyframe: no second seek, just restore exact seeks for later.
                player.setSeekParameters(SeekParameters.EXACT);
            }
        }
        lastScrubSeekTarget = -1;
        handler.postDelayed(hideSeekInfo, 400);
        hideScrubPreview();
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
        findViewById(R.id.btn_subs).setOnClickListener(v -> showSubtitlesSheet());
        View btnBookmark = findViewById(R.id.btn_bookmark);
        btnBookmark.setOnClickListener(v -> { addBookmarkHere(); scheduleHide(); });
        btnBookmark.setOnLongClickListener(v -> { showBookmarksSheet(); return true; });
        View pip = findViewById(R.id.btn_pip);
        pip.setOnClickListener(v -> enterPip());
        pip.setVisibility(pipSupported() ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_more).setOnClickListener(this::showMoreMenu);
        btnUpscale = findViewById(R.id.btn_upscale);
        btnUpscale.setOnClickListener(v -> { setUpscaleMode(Upscaler.nextMode(upscaleMode)); scheduleHide(); });
        btnUpscale.setOnLongClickListener(v -> { showUpscaleDialog(); return true; });
        updateUpscaleButton();
        View surface = playerView.getVideoSurfaceView();
        if (surface != null) {
            // Re-target the upscaler when the drawn video area changes (rotation, aspect mode, PiP).
            surface.addOnLayoutChangeListener((sv, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) {
                    handler.removeCallbacks(applyUpscaleRunnable);
                    handler.postDelayed(applyUpscaleRunnable, 250);
                }
            });
        }
        findViewById(R.id.btn_aspect).setOnClickListener(v -> {
            aspectMode = (aspectMode + 1) % ASPECT_NAMES.length;
            applyAspect(true);
            scheduleHide();
        });
        findViewById(R.id.btn_rotate).setOnClickListener(v -> {
            // Toggle relative to what is on screen now (the default mode follows the sensor).
            portrait = getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT;
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
                showScrubPreviewAtSeekBar(scrubTarget);
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
            bookmarkTicks.setDuration(dur);
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
        if (isInPip() || awaitingPrivateUnlock()) visible = false;
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
        setRequestedOrientation(requestedOrientationFor(rotationLocked, orientationChosenByUser,
                Prefs.autoRotateToVideo(this) && orientationChosenForVideo, portrait));
        updateRotationLockIcon();
    }

    /**
     * Orientation policy:
     * <ul>
     *   <li>default: USER, i.e. stay in the phone's current orientation and follow the system
     *       auto-rotate setting (if auto-rotate is off, the screen stays put);</li>
     *   <li>rotate button: USER_PORTRAIT / USER_LANDSCAPE (manual switch, still honouring the
     *       system setting for flipping within that orientation);</li>
     *   <li>"Auto-rotate to match video" (opt-in): SENSOR_* from the video's aspect;</li>
     *   <li>rotation lock: freeze the current (or manually chosen) orientation.</li>
     * </ul>
     */
    static int requestedOrientationFor(boolean locked, boolean chosenByUser, boolean matchVideo, boolean portrait) {
        if (locked) {
            if (chosenByUser) {
                return portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            }
            return ActivityInfo.SCREEN_ORIENTATION_LOCKED;
        }
        if (chosenByUser) {
            return portrait ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
        }
        if (matchVideo) {
            return portrait ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
        return ActivityInfo.SCREEN_ORIENTATION_USER;
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
                        showScrubPreview(scrubTarget, e2.getX(), e2.getY(), false);
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
            int act = e.getActionMasked();
            if (awaitingPrivateUnlock()) return true; // nothing to control until unlocked
            if (isInPip()) {
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) endGesture();
                return false;
            }
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

    // =================================================================== private videos

    private void setPrivateMode(boolean on) {
        currentPrivate = on;
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!on);
        View pip = findViewById(R.id.btn_pip);
        if (pip != null) pip.setVisibility(pipSupported() && !on ? View.VISIBLE : View.GONE);
        updatePipParams();
    }

    boolean isPrivatePlayback() {
        return currentPrivate;
    }

    /** Private video while the vault is locked: hide the picture, pause, ask to unlock. */
    private void coverForUnlock() {
        if (player != null) player.pause();
        playerView.setVisibility(View.INVISIBLE);
        setControlsVisible(false);
        if (vaultAuthInProgress || vaultPrompt == null) return;
        if (!VaultAuth.isAvailable(this)) {
            finish();
            return;
        }
        vaultAuthInProgress = true;
        handler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                VaultAuth.prompt(vaultPrompt);
            } catch (RuntimeException e) {
                vaultAuthInProgress = false;
                finish();
            }
        });
    }

    private boolean awaitingPrivateUnlock() {
        return currentPrivate && !VaultLock.isUnlocked();
    }

    private void uncoverPrivate() {
        playerView.setVisibility(View.VISIBLE);
        showControls();
    }

    // =================================================================== scrub thumbnails

    /** Local videos only, once the duration is known; cached frames load from disk instantly. */
    private void maybeStartThumbnails() {
        if (player == null || currentUri == null || isNetwork(currentUri)) return;
        if (thumbs != null && currentUri.equals(thumbsUri)) return;
        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0 || player.isCurrentMediaItemLive()) return;
        stopThumbnails();
        thumbsUri = currentUri;
        // Private videos: memory only, nothing written to the thumbnail cache.
        thumbs = new ScrubThumbnails(this, currentUri, currentPrivate ? null : currentKey, dur);
        thumbs.start(this::onThumbnailAvailable);
    }

    private void stopThumbnails() {
        if (thumbs != null) thumbs.release();
        thumbs = null;
        thumbsUri = null;
        previewShownIndex = -1;
    }

    private void onThumbnailAvailable(int index) {
        if (thumbs == null || previewPos < 0 || scrubPreview.getVisibility() != View.VISIBLE) return;
        if (index == -1 || previewShownIndex < 0 || index == thumbs.indexFor(previewPos)) {
            showScrubPreview(previewPos, previewX, previewY, previewFromSeekBar);
        }
    }

    /** Above the seek-bar thumb. */
    private void showScrubPreviewAtSeekBar(long pos) {
        int[] sb = new int[2], rt = new int[2];
        seekBar.getLocationInWindow(sb);
        root.getLocationInWindow(rt);
        int max = Math.max(1, seekBar.getMax());
        float track = seekBar.getWidth() - seekBar.getPaddingLeft() - seekBar.getPaddingRight();
        float x = sb[0] - rt[0] + seekBar.getPaddingLeft() + track * seekBar.getProgress() / max;
        showScrubPreview(pos, x, sb[1] - rt[1], true);
    }

    /**
     * Frame (about 160 dp wide, rounded) with the time under it, centred on {@code x} and sitting
     * above {@code y} (seek bar top, or the finger for swipe scrubs). Streams: nothing here, the
     * centre indicator already shows the time.
     */
    private void showScrubPreview(long pos, float x, float y, boolean fromSeekBar) {
        if (currentUri == null || isNetwork(currentUri) || isInPip()) return;
        previewPos = pos;
        previewX = x;
        previewY = y;
        previewFromSeekBar = fromSeekBar;
        float d = getResources().getDisplayMetrics().density;
        ScrubThumbnails.Frame f = thumbs == null ? null : thumbs.lookup(pos);
        if (f != null) {
            int bw = f.bitmap.getWidth(), bh = f.bitmap.getHeight();
            int w, h;
            if (bw >= bh) {
                w = Math.round(160 * d);
                h = Math.round(w * bh / (float) bw);
            } else {
                h = Math.round(160 * d);
                w = Math.round(h * bw / (float) bh);
            }
            ViewGroup.LayoutParams lp = scrubPreviewImage.getLayoutParams();
            if (lp.width != w || lp.height != h) {
                lp.width = w;
                lp.height = h;
                scrubPreviewImage.setLayoutParams(lp);
            }
            if (f.index != previewShownIndex) scrubPreviewImage.setImageBitmap(f.bitmap);
            previewShownIndex = f.index;
            scrubPreviewFrame.setVisibility(View.VISIBLE);
        } else {
            previewShownIndex = -1;
            scrubPreviewFrame.setVisibility(View.GONE); // time only until a frame exists
        }
        scrubPreviewTime.setText(scrubZone == SCRUB_FINE && !fromSeekBar ? Fmt.timePrecise(pos) : Fmt.time(pos));
        scrubPreview.setVisibility(View.VISIBLE);
        scrubPreview.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int pw = scrubPreview.getMeasuredWidth(), ph = scrubPreview.getMeasuredHeight();
        float margin = 8 * d;
        float gap = (fromSeekBar ? 4 : 48) * d; // clear of the finger
        float tx = Math.max(margin, Math.min(root.getWidth() - pw - margin, x - pw / 2f));
        float ty = y - gap - ph;
        if (ty < margin) ty = fromSeekBar ? margin : y + gap; // no room above: go below the finger
        ty = Math.min(ty, Math.max(margin, root.getHeight() - ph - margin));
        if (!fromSeekBar && seekInfo.getVisibility() == View.VISIBLE) {
            // Swipe scrub: keep clear of the centre indicator (top-centre, 72 dp down).
            seekInfo.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            float il = (root.getWidth() - seekInfo.getMeasuredWidth()) / 2f, ir = il + seekInfo.getMeasuredWidth();
            float it = ((ViewGroup.MarginLayoutParams) seekInfo.getLayoutParams()).topMargin;
            float ib = it + seekInfo.getMeasuredHeight();
            if (tx < ir && tx + pw > il && ty < ib && ty + ph > it) {
                float side = x >= (il + ir) / 2f ? ir + margin : il - pw - margin;
                if (side >= margin && side + pw <= root.getWidth() - margin) {
                    tx = side;                                  // beside the indicator
                } else if (ib + margin + ph <= y - margin) {
                    ty = ib + margin;                           // between indicator and finger
                } else {
                    ty = Math.min(Math.max(y + gap, ib + margin), Math.max(margin, root.getHeight() - ph - margin));
                }
            }
        }
        scrubPreview.setTranslationX(tx);
        scrubPreview.setTranslationY(ty);
    }

    private void hideScrubPreview() {
        previewPos = -1;
        if (scrubPreview != null) scrubPreview.setVisibility(View.GONE);
        if (thumbs != null) thumbs.setScrubbing(false);
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
                    setSpeedForVideo(SPEEDS[which]);
                    d.dismiss();
                })
                .setNeutralButton("Default: " + formatSpeed(PlaybackMemory.defaultSpeed(this)) + "…",
                        (d, w) -> showDefaultSpeedDialog())
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
        // The temporary grant from the picker lasts as long as this activity, which is all we need
        // (persisting it every time would pile up grants toward the per-app limit).
        externalSubUri = uri;
        externalSubName = VideoRepository.displayName(this, uri);
        PlaybackMemory.setSubtitlesOn(this, true);
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

    // =================================================================== 1.6 bookmarks / speed / subtitles

    /**
     * Called by startMedia: resets the per-video state, then resolves the video's stable key
     * ({@link VideoKey}: content fingerprint, same in and out of Private) and, if enabled, a
     * same-name .srt/.vtt next to it, off the main thread.
     */
    private void prepareVideoMemory(Uri uri, String title) {
        videoKey = null;
        bookmarkTicks.setBookmarks(new long[0], 0);
        if (keepSpeedOnce) {
            keepSpeedOnce = false;
            speedChosenForVideo = true;
        } else {
            speedChosenForVideo = false;
            applySpeed(PlaybackMemory.defaultSpeed(this));
        }
        final VideoItem item = playlistIndex >= 0 && playlistIndex < playlist.size()
                && uri.equals(playlist.get(playlistIndex).uri) ? playlist.get(playlistIndex) : null;
        final long size = item != null ? item.sizeBytes : -1;
        final long dur = item != null ? item.durationMs : -1;
        final String name = item != null ? item.displayName : null;
        final boolean findSubs = externalSubUri == null && !currentPrivate && PlaybackMemory.autoLoadSubtitles(this);
        final Context app = getApplicationContext();
        io.execute(() -> {
            String key = VideoKey.of(app, uri, size, dur, name != null ? name : title);
            SubtitleFinder.Found sub = findSubs ? SubtitleFinder.find(app, uri, name) : null;
            handler.post(() -> {
                if (isDestroyed() || !uri.equals(currentUri)) return;
                onVideoKeyReady(key);
                if (sub != null && externalSubUri == null) loadAutoSubtitle(sub.uri, sub.name);
            });
        });
    }

    private void onVideoKeyReady(@androidx.annotation.Nullable String key) {
        videoKey = key;
        if (speedChosenForVideo) {
            PlaybackMemory.setVideoSpeed(this, key, speed); // chosen before the key was known
        } else {
            float s = PlaybackMemory.startSpeed(this, key);
            if (Math.abs(s - speed) > 0.001f) applySpeed(s);
        }
        refreshBookmarkTicks();
    }

    private void applySpeed(float s) {
        speed = PlaybackMemory.sanitizeSpeed(s);
        if (player != null) player.setPlaybackSpeed(speed);
        btnSpeed.setText(formatSpeed(speed));
    }

    /** Speed picked in the player: used now and remembered for this video. */
    private void setSpeedForVideo(float s) {
        applySpeed(s);
        speedChosenForVideo = true;
        PlaybackMemory.setVideoSpeed(this, videoKey, speed);
    }

    private void showDefaultSpeedDialog() {
        float def = PlaybackMemory.defaultSpeed(this);
        String[] labels = new String[SPEEDS.length];
        int checked = 2;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = formatSpeed(SPEEDS[i]);
            if (Math.abs(SPEEDS[i] - def) < 0.01f) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Default speed for all videos")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    boolean followsDefault = PlaybackMemory.videoSpeed(this, videoKey) == null
                            && Math.abs(speed - PlaybackMemory.defaultSpeed(this)) < 0.001f;
                    PlaybackMemory.setDefaultSpeed(this, SPEEDS[which]);
                    if (followsDefault) applySpeed(SPEEDS[which]);
                    Toast.makeText(this, "Default speed " + formatSpeed(SPEEDS[which])
                            + ". Videos with their own speed keep it", Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setOnDismissListener(d -> hideSystemUi())
                .show();
    }

    private void refreshBookmarkTicks() {
        List<BookmarkStore.Bookmark> l = bookmarkStore.list(videoKey);
        long[] t = new long[l.size()];
        for (int i = 0; i < t.length; i++) t[i] = l.get(i).timeMs;
        long dur = player == null ? 0 : player.getDuration();
        bookmarkTicks.setBookmarks(t, dur == C.TIME_UNSET ? 0 : dur);
    }

    /** Bookmark button: saves the current position (name it from the snackbar). */
    void addBookmarkHere() {
        if (player == null || currentUri == null) return;
        final long pos = player.getCurrentPosition();
        if (videoKey == null) {
            // Key still being computed on the io thread: queue behind it.
            final Uri u = currentUri;
            io.execute(() -> handler.post(() -> {
                if (u.equals(currentUri) && videoKey != null) addBookmarkAt(pos);
            }));
            return;
        }
        addBookmarkAt(pos);
    }

    private void addBookmarkAt(long pos) {
        int before = bookmarkStore.list(videoKey).size();
        BookmarkStore.Bookmark b = bookmarkStore.add(videoKey, pos, null);
        if (b == null) {
            Toast.makeText(this, "Couldn't add a bookmark here", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshBookmarkTicks();
        final String key = videoKey;
        boolean added = bookmarkStore.list(videoKey).size() > before;
        Snackbar.make(root, (added ? "Bookmarked " : "Already bookmarked ") + Fmt.time(b.timeMs), Snackbar.LENGTH_LONG)
                .setAction(b.name.isEmpty() ? "Name" : "Rename", v -> BookmarksSheet.showNameDialog(this,
                        "Name bookmark", b.name, Fmt.time(b.timeMs), n -> {
                            bookmarkStore.rename(key, b.id, n);
                            hideSystemUi();
                        }))
                .show();
    }

    /** Jumps to a bookmark with the 1.4.2 instant (keyframe, CLOSEST_SYNC) seek. */
    void jumpToBookmark(long positionMs) {
        if (player == null) return;
        if (thumbs != null) thumbs.holdOff(SEEK_THUMB_HOLDOFF_MS);
        long target = Math.max(0, positionMs);
        player.setSeekParameters(BOOKMARK_SEEK);
        player.seekTo(target);
        player.setSeekParameters(SeekParameters.EXACT); // queued after the seek
        updateProgressUi(target);
        showSeekInfo(target, null, null);
        handler.postDelayed(hideSeekInfo, 900);
        showControls();
    }

    private void showBookmarksSheet() {
        handler.removeCallbacks(hideControlsRunnable);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_bookmarks, null);
        sheet.setContentView(v);
        bindBookmarksSheet(v, sheet);
        sheet.setOnShowListener(d -> sheet.getBehavior().setSkipCollapsed(true));
        sheet.setOnDismissListener(d -> { hideSystemUi(); scheduleHide(); });
        sheet.show();
    }

    /** Package-private so tests can render the sheet. */
    void bindBookmarksSheet(View v, @androidx.annotation.Nullable android.app.Dialog sheet) {
        BookmarksSheet.bind(v, sheet, bookmarkStore, new BookmarksSheet.Host() {
            @Override public String videoKey() { return videoKey; }
            @Override public long positionMs() { return player == null ? 0 : player.getCurrentPosition(); }
            @Override public void jumpTo(long positionMs) { jumpToBookmark(positionMs); }
            @Override public void onBookmarksChanged() { refreshBookmarkTicks(); }
        });
    }

    private void showSubtitlesSheet() {
        handler.removeCallbacks(hideControlsRunnable);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_subtitles, null);
        sheet.setContentView(v);
        bindSubtitlesSheet(v, sheet);
        sheet.setOnShowListener(d -> {
            sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            sheet.getBehavior().setSkipCollapsed(true);
        });
        sheet.setOnDismissListener(d -> { hideSystemUi(); scheduleHide(); });
        sheet.show();
    }

    /** Package-private so tests can render the sheet. */
    void bindSubtitlesSheet(View v, @androidx.annotation.Nullable android.app.Dialog sheet) {
        SubtitleSheet.bind(v, sheet, new SubtitleSheet.Host() {
            @Override public Player player() { return player; }
            @Override public void setSubtitlesOn(boolean on) { setSubtitlesEnabled(on); }
            @Override public void setSubtitleSize(int index) { applySubtitleSize(index); }
            @Override public void pickSubtitleFile() { subtitlePicker.launch(new String[]{"*/*"}); }
            @Override public String externalSubtitleName() { return externalSubName; }
        });
    }

    void setSubtitlesEnabled(boolean on) {
        PlaybackMemory.setSubtitlesOn(this, on);
        if (player != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build());
        }
    }

    void applySubtitleSize(int index) {
        PlaybackMemory.setSubtitleSize(this, index);
        if (playerView.getSubtitleView() != null) {
            playerView.getSubtitleView().setFractionalTextSize(PlaybackMemory.subtitleFraction(PlaybackMemory.subtitleSize(this)));
        }
    }

    /** A same-name subtitle file found next to the video: added like a picked one. */
    private void loadAutoSubtitle(Uri uri, String name) {
        if (player == null) return;
        externalSubUri = uri;
        externalSubName = name;
        long pos = player.getCurrentPosition();
        boolean pwr = player.getPlayWhenReady();
        player.setMediaItem(buildMediaItem(), pos);
        player.prepare();
        player.setPlayWhenReady(pwr);
        if (PlaybackMemory.subtitlesOn(this)) {
            Toast.makeText(this, "Subtitles: " + name, Toast.LENGTH_SHORT).show();
        }
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
        MaterialSwitch swRotate = v.findViewById(R.id.switch_auto_rotate);
        swRotate.setChecked(Prefs.autoRotateToVideo(this));
        swRotate.setOnCheckedChangeListener((b, on) -> setAutoRotateToVideo(on));
        v.findViewById(R.id.row_auto_rotate).setOnClickListener(x -> swRotate.toggle());
        v.findViewById(R.id.row_decoder).setOnClickListener(x -> swDecoder.toggle());
        v.findViewById(R.id.row_sleep).setOnClickListener(x -> { sheet.dismiss(); showSleepTimerDialog(); });
        v.findViewById(R.id.row_clip).setOnClickListener(x -> { if (canClip) { sheet.dismiss(); openClipEditor(); } });
        v.findViewById(R.id.row_info).setOnClickListener(x -> { sheet.dismiss(); showInfoDialog(); });
        TextView upscaleValue = v.findViewById(R.id.upscale_value);
        upscaleValue.setText(upscaleSheetSubtitle());
        v.findViewById(R.id.row_upscale).setOnClickListener(x -> { sheet.dismiss(); showUpscaleDialog(); });
        int marks = bookmarkStore.list(videoKey).size();
        ((TextView) v.findViewById(R.id.bookmarks_value)).setText(marks == 0
                ? "Mark moments in this video and jump back to them"
                : marks + (marks == 1 ? " bookmark" : " bookmarks") + " in this video");
        v.findViewById(R.id.row_bookmarks).setOnClickListener(x -> { sheet.dismiss(); showBookmarksSheet(); });
    }

    private void setAutoRotateToVideo(boolean on) {
        Prefs.setAutoRotateToVideo(this, on);
        if (on && player != null && !orientationChosenByUser) {
            VideoSize vs = player.getVideoSize();
            if (vs.width > 0 && vs.height > 0) {
                orientationChosenForVideo = true;
                portrait = vs.height * 1f > vs.width * vs.pixelWidthHeightRatio;
            }
        }
        applyOrientation();
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
        // Also restores full volume if the user seeks back out of the fade window.
        player.setVolume(remaining <= SLEEP_FADE_MS ? Math.max(0f, remaining / (float) SLEEP_FADE_MS) : 1f);
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
        int[] t = PlayerFactory.LOCAL_TIERS[bufferTier];
        sb.append("\nBuffer profile: ").append(currentUri != null && isNetwork(currentUri)
                ? "stream · 30 s back / 30–120 s ahead"
                : "local tier " + bufferTier + " · " + t[2] / 1000 + " s back / " + t[0] / 1000 + "–" + t[1] / 1000
                + " s ahead · capped at " + PlayerFactory.bufferBudgetBytes() / (1 << 20) + " MB");
        sb.append("\nDecoder preference: ").append(preferSoftware ? "software first" : "hardware first (auto fallback)");
        sb.append("\n\nUpscale: ").append(upscaleStatusText());
        new MaterialAlertDialogBuilder(this)
                .setTitle("Playback info")
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> hideSystemUi())
                .show();
    }

    // =================================================================== upscaling (1.5.0)

    private boolean upscaleWanted() {
        return upscaleMode != Upscaler.MODE_OFF && !upscaleSessionOff && !currentItemHdr;
    }

    /** Effects are actually processing frames (pipeline present, wanted, and not in the PiP window). */
    private boolean upscaleRunning() {
        return upscalePipeline && upscaleWanted() && !isInPip();
    }

    /** Size of the area the video is drawn into (the PlayerView's surface), or the screen before layout. */
    private void updateEffectBox() {
        View s = playerView == null ? null : playerView.getVideoSurfaceView();
        int w = s == null ? 0 : s.getWidth();
        int h = s == null ? 0 : s.getHeight();
        if (w <= 0 || h <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        effectBoxW = w;
        effectBoxH = h;
    }

    /** Pushes the current upscale effect (or none) to the player; no-op if nothing changed. */
    private void applyUpscaleEffects() {
        if (player == null || !upscalePipeline) return;
        updateEffectBox();
        boolean run = upscaleRunning();
        String key = run ? upscaleMode + "/" + upscaleQuality + "/" + effectBoxW + "x" + effectBoxH : "none";
        if (key.equals(appliedEffectsKey)) return;
        appliedEffectsKey = key;
        List<Effect> effects = run
                ? Collections.singletonList(new UpscaleEffect(upscaleMode, upscaleQuality, effectBoxW, effectBoxH))
                : Collections.emptyList();
        try {
            player.setVideoEffects(effects);
        } catch (RuntimeException e) {
            // e.g. media3-effect missing or the renderer refusing effects: behave like a GL failure.
            handler.post(this::onUpscaleGlFailure);
        }
    }

    /** Rebuilds the player (e.g. to add or remove the effects pipeline) and continues where we were. */
    private void rebuildAtCurrentPosition() {
        if (player == null || currentUri == null) return;
        long pos = player.getCurrentPosition();
        boolean pwr = player.getPlayWhenReady();
        rebuildPlayer();
        player.setWakeMode(isNetwork(currentUri) ? C.WAKE_MODE_NETWORK : C.WAKE_MODE_LOCAL);
        player.setMediaItem(buildMediaItem(), pos);
        player.prepare();
        player.setPlayWhenReady(pwr && !(currentPrivate && !VaultLock.isUnlocked()));
    }

    void setUpscaleMode(int mode) {
        upscaleMode = Upscaler.sanitizeMode(mode);
        Prefs.setUpscaleMode(this, upscaleMode);
        upscaleSessionOff = false;
        upscaleWatchdog.reset();
        watchdogLastDropped = -1;
        updateUpscaleButton();
        String detail;
        if (upscaleMode == Upscaler.MODE_OFF) {
            applyUpscaleEffects(); // instant; the pipeline itself goes with the next player rebuild
            detail = null;
        } else if (currentItemHdr) {
            detail = "Paused for HDR video";
        } else if (player != null && !upscalePipeline) {
            rebuildAtCurrentPosition(); // adds the GL pipeline (brief reload at the same position)
            detail = upscalePlanText();
        } else {
            applyUpscaleEffects();
            detail = upscalePlanText();
        }
        showUpscaleToast(upscaleMode == Upscaler.MODE_OFF ? "Upscale off" : "Upscale · " + Upscaler.modeLabel(upscaleMode), detail);
    }

    private final Runnable hideUpscaleToast = () -> {
        View t = findViewById(R.id.upscale_toast);
        if (t != null) t.animate().alpha(0f).setDuration(180).withEndAction(() -> t.setVisibility(View.GONE)).start();
    };

    /** Quick Ulfur-styled label under the top bar (title + optional detail line), ~1.6 s. */
    void showUpscaleToast(String title, @androidx.annotation.Nullable String detail) {
        View t = findViewById(R.id.upscale_toast);
        if (t == null || isInPip()) return;
        ((TextView) findViewById(R.id.upscale_toast_title)).setText(title);
        TextView d = findViewById(R.id.upscale_toast_detail);
        d.setText(detail == null ? "" : detail);
        d.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        t.animate().cancel();
        t.setAlpha(1f);
        t.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideUpscaleToast);
        handler.postDelayed(hideUpscaleToast, 1_600);
    }

    private void showUpscaleDialog() {
        handler.removeCallbacks(hideControlsRunnable);
        String[] items = {
                "Off",
                "Balanced: FSR upscale + gentle sharpening",
                "Sharp: FSR upscale + strong sharpening"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Upscale")
                .setSingleChoiceItems(items, upscaleMode, (d, which) -> {
                    d.dismiss();
                    if (which != upscaleMode) setUpscaleMode(which);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> { hideSystemUi(); scheduleHide(); })
                .show();
    }

    void updateUpscaleButton() {
        if (btnUpscale == null) return;
        boolean on = upscaleMode != Upscaler.MODE_OFF;
        androidx.core.widget.ImageViewCompat.setImageTintList(btnUpscale, ColorStateList.valueOf(
                on ? ContextCompat.getColor(this, R.color.accent) : 0xFFFFFFFF));
        btnUpscale.setActivated(on);
        btnUpscale.setContentDescription("Upscale: " + Upscaler.modeLabel(upscaleMode));
    }

    /** "640×360 → 1920×1080" for the current video, or null before the size is known. */
    @androidx.annotation.Nullable
    private String upscalePlanText() {
        if (player == null) return null;
        VideoSize vs = player.getVideoSize();
        if (vs.width <= 0 || vs.height <= 0) return null;
        updateEffectBox();
        int w = vs.pixelWidthHeightRatio > 0 && vs.pixelWidthHeightRatio != 1f
                ? Math.round(vs.width * vs.pixelWidthHeightRatio) : vs.width;
        return Upscaler.plan(w, vs.height, effectBoxW, effectBoxH, upscaleMode).describe(w, vs.height);
    }

    String upscaleSheetSubtitle() {
        if (upscaleMode == Upscaler.MODE_OFF) return "Off · sharper low-res video, upscaled live on the GPU";
        String s = Upscaler.modeLabel(upscaleMode);
        if (currentItemHdr) return s + " · paused for HDR video";
        if (upscaleSessionOff) return s + " · paused (this phone couldn't keep up)";
        return s + " · " + (upscaleQuality == Upscaler.QUALITY_LITE ? "Lite" : "FSR 1.0");
    }

    private String upscaleStatusText() {
        if (upscaleMode == Upscaler.MODE_OFF) return "off";
        StringBuilder sb = new StringBuilder(Upscaler.modeLabel(upscaleMode));
        if (currentItemHdr) return sb.append(" · paused for HDR video").toString();
        if (upscaleSessionOff) return sb.append(" · paused: frames were dropping").toString();
        if (!upscalePipeline) return sb.append(" · starts with the next video").toString();
        sb.append(" · ").append(Upscaler.qualityLabel(upscaleQuality));
        if (isInPip()) return sb.append(" · paused in PiP").toString();
        String plan = upscalePlanText();
        if (plan != null) sb.append("\n").append(plan);
        return sb.toString();
    }

    static boolean isVideoEffectsError(PlaybackException e) {
        return e.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED
                || e.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED;
    }

    static boolean isHdr(Tracks tracks) {
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO || !g.isSelected()) continue;
            for (int i = 0; i < g.length; i++) {
                if (g.isTrackSelected(i) && ColorInfo.isTransferHdr(g.getTrackFormat(i).colorInfo)) return true;
            }
        }
        return false;
    }

    /** GPU couldn't build or run the shaders: FSR → Lite once, then turn Upscale off for good. */
    private void onUpscaleGlFailure() {
        upscaleGlFailures++;
        String msg;
        if (currentItemHdr) {
            msg = "Upscale paused for this HDR video";
        } else if (upscaleQuality == Upscaler.QUALITY_FSR && upscaleGlFailures == 1) {
            upscaleQuality = Upscaler.QUALITY_LITE;
            msg = "Upscale switched to Lite: this phone's GPU couldn't run FSR";
        } else {
            upscaleMode = Upscaler.MODE_OFF;
            Prefs.setUpscaleMode(this, Upscaler.MODE_OFF);
            msg = "Upscale turned off: this phone's GPU couldn't run it";
        }
        updateUpscaleButton();
        rebuildAtCurrentPosition();
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
    }

    /** Every {@link Upscaler.Watchdog#WINDOW_MS}: steps down if the upscaler makes frames drop. */
    private void onUpscaleWatchdogTick() {
        handler.postDelayed(upscaleWatchdogTick, Upscaler.Watchdog.WINDOW_MS);
        long now = SystemClock.elapsedRealtime();
        DecoderCounters dc = player == null ? null : player.getVideoDecoderCounters();
        boolean steady = player != null && upscaleRunning() && player.isPlaying() && !scrubbing
                && player.getPlaybackState() == Player.STATE_READY
                && now - lastPlaybackDisturbanceAt > 1_500 && dc != null;
        if (!steady) {
            watchdogLastDropped = -1;
            return;
        }
        dc.ensureUpdated();
        long dropped = dc.droppedBufferCount;
        if (watchdogLastDropped >= 0 && dropped >= watchdogLastDropped) {
            Format f = player.getVideoFormat();
            float fps = f != null && f.frameRate > 0 ? f.frameRate : 30f;
            double expected = (now - watchdogLastAt) / 1000.0 * fps * player.getPlaybackParameters().speed;
            if (upscaleWatchdog.onWindow(dropped - watchdogLastDropped, expected)) stepDownUpscale();
        }
        watchdogLastDropped = dropped;
        watchdogLastAt = now;
    }

    private void stepDownUpscale() {
        String msg;
        if (upscaleQuality == Upscaler.QUALITY_FSR) {
            upscaleQuality = Upscaler.QUALITY_LITE;
            msg = "Upscale switched to Lite to keep playback smooth";
        } else {
            upscaleSessionOff = true;
            msg = "Upscale paused: this phone couldn't keep up. It will try again next time.";
        }
        applyUpscaleEffects();
        updateUpscaleButton();
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
    }

    // =================================================================== resume

    private void saveResume() {
        if (player == null || currentKey == null) return;
        if (currentPrivate) return; // no resume points / recents for private videos
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
        if (currentPrivate) return; // the PiP window would show a private video outside the app
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
                // Framework limits are 0.41841..2.39 (inclusive). Round inwards so the Rational
                // never lands outside them (Math.round(0.41841 * 1000) / 1000 = 0.418 -> IAE).
                int num = (int) Math.ceil(Math.max(0.4185f, Math.min(2.39f, ratio)) * 10_000);
                num = Math.min(num, 23_900);
                b.setAspectRatio(new Rational(num, 10_000));
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
            // playWhenReady (not isPlaying) so a rebuffer/seek in progress still auto-enters PiP.
            b.setAutoEnterEnabled(player != null && player.getPlayWhenReady()
                    && player.getPlaybackState() != Player.STATE_ENDED && !locked && !backgroundAudio
                    && !currentPrivate);
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
                && player != null && player.isPlaying() && !locked && !backgroundAudio && !currentPrivate) {
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
            hideScrubPreview();
            btnUnlock.setVisibility(View.GONE);
            setZoom(1f);
            applyUpscaleEffects(); // no upscaling in the small PiP window
        } else {
            applyUpscaleEffects();
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                // PiP window was dismissed: pause and free the decoders (onStart rebuilds).
                if (player != null) player.pause();
                saveResume();
                releaseDecoders();
            } else {
                applyAspect(false);
                showControls();
            }
        }
    }
}
