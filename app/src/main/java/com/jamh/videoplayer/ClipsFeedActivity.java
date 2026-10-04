package com.jamh.videoplayer;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen vertical clip feed (Shorts-style). Swipe up/down, clips autoplay and loop,
 * tap to pause. Uses a pool of exactly two ExoPlayers: one plays the current page while the
 * other has the neighbouring clip prepared (first frame rendered) for an instant swipe.
 */
@OptIn(markerClass = UnstableApi.class)
public class ClipsFeedActivity extends AppCompatActivity {

    public static final String EXTRA_START = "start";

    /** Clip list handed over from the library (avoids huge intent extras). */
    static List<VideoItem> pendingClips;

    private final ExoPlayer[] players = new ExoPlayer[2];
    private final int[] loadedPos = {-1, -1};
    private final PlayerView[] attachedView = new PlayerView[2];
    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<VideoItem> clips = new ArrayList<>();
    private ViewPager2 pager;
    private TextView counter;
    private int current = -1;
    private boolean userPaused;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            updateProgress();
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        clips = pendingClips == null ? new ArrayList<>() : new ArrayList<>(pendingClips);
        if (clips.isEmpty()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_clips_feed);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
        View top = findViewById(R.id.feed_top);
        ViewCompat.setOnApplyWindowInsetsListener(top, (v, insets) -> {
            Insets i = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(v.getPaddingLeft(), i.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        findViewById(R.id.feed_back).setOnClickListener(v -> finish());
        counter = findViewById(R.id.feed_counter);

        for (int i = 0; i < 2; i++) {
            players[i] = PlayerFactory.createLight(this);
            players[i].setPlayWhenReady(false);
            final int index = i;
            players[i].addListener(new Player.Listener() {
                @Override public void onRenderedFirstFrame() {
                    // Hide the thumbnail poster once real video frames reach this page's surface.
                    PlayerView pv = attachedView[index];
                    if (pv != null) {
                        View poster = ((View) pv.getParent()).findViewById(R.id.clip_poster);
                        if (poster != null) poster.setVisibility(View.GONE);
                    }
                }
            });
        }

        pager = findViewById(R.id.pager);
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(new PageAdapter());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                selectPage(position);
            }
        });
        int start = Math.max(0, Math.min(clips.size() - 1, getIntent().getIntExtra(EXTRA_START, 0)));
        pager.setCurrentItem(start, false);
        pager.post(() -> selectPage(pager.getCurrentItem()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (current >= 0 && !userPaused) playerFor(current).play();
        handler.post(progressTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(progressTick);
        for (ExoPlayer p : players) if (p != null) p.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        for (int i = 0; i < 2; i++) {
            if (attachedView[i] != null) attachedView[i].setPlayer(null);
            if (players[i] != null) players[i].release();
            players[i] = null;
        }
    }

    private ExoPlayer playerFor(int position) {
        return players[position % 2];
    }

    /** Makes {@code position} current: play its player, preload the neighbour on the other one. */
    void selectPage(int position) {
        if (players[0] == null || position < 0 || position >= clips.size()) return;
        if (position == current && playerFor(position).getPlayWhenReady()) return;
        current = position;
        userPaused = false;
        int idx = position % 2, other = 1 - idx;
        players[other].pause();
        ensureLoaded(idx, position);
        ExoPlayer p = players[idx];
        if (p.getCurrentPosition() > 150) p.seekTo(0);
        p.play();
        // The neighbour we are most likely to swipe to next has the same parity as position ± 1.
        int preload = position + 1 < clips.size() ? position + 1 : position - 1;
        if (preload >= 0 && preload != position) {
            if (ensureLoaded(other, preload)) players[other].seekTo(0);
        }
        attachViews();
        counter.setText((position + 1) + " / " + clips.size());
    }

    /** Returns true if a new item had to be loaded. */
    private boolean ensureLoaded(int idx, int position) {
        if (loadedPos[idx] == position) return false;
        ExoPlayer p = players[idx];
        p.setMediaItem(MediaItem.fromUri(clips.get(position).uri));
        p.prepare();
        p.setPlayWhenReady(false);
        loadedPos[idx] = position;
        return true;
    }

    /** Connects each pooled player to the PlayerView of the page it holds (if bound). */
    void attachViews() {
        if (pager == null) return;
        RecyclerView rv = (RecyclerView) pager.getChildAt(0);
        for (int i = 0; i < 2; i++) {
            PlayerView target = null;
            if (loadedPos[i] >= 0) {
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(loadedPos[i]);
                if (vh instanceof PageHolder) target = ((PageHolder) vh).playerView;
            }
            if (attachedView[i] == target) continue;
            if (attachedView[i] != null && attachedView[i].getPlayer() == players[i]) attachedView[i].setPlayer(null);
            if (target != null) {
                // Make sure the other pooled player is not using this view.
                int o = 1 - i;
                if (attachedView[o] == target) { target.setPlayer(null); attachedView[o] = null; }
                target.setPlayer(players[i]);
            }
            attachedView[i] = target;
        }
    }

    private void togglePause(PageHolder h) {
        if (current < 0) return;
        ExoPlayer p = playerFor(current);
        if (p.getPlayWhenReady()) {
            p.pause();
            userPaused = true;
            h.paused.setVisibility(View.VISIBLE);
        } else {
            p.play();
            userPaused = false;
            h.paused.setVisibility(View.GONE);
        }
    }

    private void updateProgress() {
        if (current < 0 || pager == null) return;
        RecyclerView rv = (RecyclerView) pager.getChildAt(0);
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(current);
        if (!(vh instanceof PageHolder)) return;
        ExoPlayer p = playerFor(current);
        long dur = p.getDuration();
        if (dur != C.TIME_UNSET && dur > 0) {
            ((PageHolder) vh).progress.setProgress((int) (1000 * p.getCurrentPosition() / dur));
        }
    }

    // ------------------------------------------------------------------ adapter

    static final class PageHolder extends RecyclerView.ViewHolder {
        final PlayerView playerView;
        final ImageView poster, paused;
        final TextView title, meta;
        final ProgressBar progress;

        PageHolder(View v) {
            super(v);
            playerView = v.findViewById(R.id.clip_player);
            poster = v.findViewById(R.id.clip_poster);
            paused = v.findViewById(R.id.clip_paused);
            title = v.findViewById(R.id.clip_title);
            meta = v.findViewById(R.id.clip_meta);
            progress = v.findViewById(R.id.clip_progress);
        }
    }

    private final class PageAdapter extends RecyclerView.Adapter<PageHolder> {
        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PageHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_clip_page, parent, false));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public void onBindViewHolder(@NonNull PageHolder h, int position) {
            VideoItem v = clips.get(position);
            for (int i = 0; i < 2; i++) {
                if (attachedView[i] == h.playerView) {
                    h.playerView.setPlayer(null);
                    attachedView[i] = null;
                }
            }
            h.playerView.setResizeMode(v.isPortrait() ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            h.title.setText(v.title);
            h.meta.setText(Fmt.time(v.durationMs) + "  ·  " + v.bucketName);
            h.progress.setProgress(0);
            h.paused.setVisibility(View.GONE);
            h.poster.setVisibility(View.VISIBLE);
            h.poster.setScaleType(v.isPortrait() ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            ThumbnailLoader.load(h.poster, v);
            h.itemView.setOnClickListener(x -> togglePause(h));
        }

        @Override
        public void onViewAttachedToWindow(@NonNull PageHolder holder) {
            pager.post(ClipsFeedActivity.this::attachViews);
        }

        @Override
        public int getItemCount() {
            return clips.size();
        }
    }
}
