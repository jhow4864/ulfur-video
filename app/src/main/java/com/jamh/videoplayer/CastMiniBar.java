package com.jamh.videoplayer;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

/**
 * 1.9.2: Ulfur's own Cast mini bar (title, device, play/pause, stop; tap opens the TV controls),
 * built on plain views and {@link RemoteMediaClient}. It replaces Google's
 * {@code MiniControllerFragment}, which crashed every launch on the Android 17 beta.
 *
 * <p>No fragment, nothing Cast-related happens at startup: the bar view is only inflated the first
 * time a Cast session is actually connected with something playing, and every Cast call is
 * wrapped so a broken Cast module can only ever hide the bar.
 */
final class CastMiniBar implements Caster.Listener {

    /** What the bar shows. */
    static final class State {
        final String device;
        @Nullable final String title;
        final boolean playing;

        State(String device, @Nullable String title, boolean playing) {
            this.device = device;
            this.title = title;
            this.playing = playing;
        }
    }

    private final Activity activity;
    @Nullable private final ViewGroup container;
    @Nullable private View bar;
    private TextView title;
    private TextView subtitle;
    private ImageButton playPause;
    @Nullable private RemoteMediaClient registered;
    private boolean started;

    private final RemoteMediaClient.Callback mediaCallback = new RemoteMediaClient.Callback() {
        @Override public void onStatusUpdated() {
            refresh();
        }

        @Override public void onMetadataUpdated() {
            refresh();
        }
    };

    private CastMiniBar(Activity activity, @Nullable ViewGroup container) {
        this.activity = activity;
        this.container = container;
        if (container != null) container.setVisibility(View.GONE);
    }

    /** Binds to the (initially GONE) container. Touches no Cast API. */
    static CastMiniBar attach(Activity a, @Nullable ViewGroup container) {
        return new CastMiniBar(a, container);
    }

    /** onStart/onResume: listen for Cast changes. Cast is only initialised (async) if available. */
    @MainThread
    void start() {
        if (started) return;
        started = true;
        try {
            Caster.get().addListener(this);
            // After the first frame: Cast (async) init must never compete with launch.
            View anchor = container != null ? container : activity.getWindow().getDecorView();
            anchor.post(() -> {
                if (started) CastSupport.whenReady(activity, this::refresh);
            });
        } catch (Throwable ignored) {
        }
        refresh();
    }

    /** onStop/onDestroy. */
    @MainThread
    void stop() {
        started = false;
        try {
            Caster.get().removeListener(this);
        } catch (Throwable ignored) {
        }
        unregister();
    }

    @Override public void onSessionStarted() {
        refresh();
    }

    @Override public void onCastingChanged() {
        refresh();
    }

    @Override public void onCastHandedBack(String key, boolean isPrivate, long positionMs) {
        refresh();
    }

    /** Re-reads the Cast state; any failure just hides the bar. */
    @MainThread
    void refresh() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        State s;
        try {
            s = started ? readState() : null;
        } catch (Throwable t) {
            s = null;
        }
        bind(s);
    }

    @Nullable
    private RemoteMediaClient client() {
        if (Caster.testRemote != null) return null;
        CastContext cc = CastSupport.isReady() ? CastSupport.context(activity) : null;
        if (cc == null) return null;
        CastSession session = cc.getSessionManager().getCurrentCastSession();
        if (session == null || !session.isConnected()) return null;
        return session.getRemoteMediaClient();
    }

    @Nullable
    private State readState() {
        Caster.Remote r = Caster.get().remote(activity);
        if (r == null || !r.isConnected()) {
            unregister();
            return null;
        }
        String device = r.deviceName();
        if (device == null || device.isEmpty()) device = "TV";
        if (Caster.testRemote != null) {
            Caster.Casting c = Caster.get().current();
            return c == null ? null : new State(device, null, true);
        }
        RemoteMediaClient c = client();
        if (c != registered) {
            unregister();
            if (c != null) {
                c.registerCallback(mediaCallback);
                registered = c;
            }
        }
        if (c == null || !c.hasMediaSession()) return null; // connected but nothing playing: hidden
        String t = null;
        MediaInfo mi = c.getMediaInfo();
        if (mi != null && mi.getMetadata() != null) t = mi.getMetadata().getString(MediaMetadata.KEY_TITLE);
        return new State(device, t, c.isPlaying() || c.isBuffering());
    }

    private void unregister() {
        RemoteMediaClient c = registered;
        registered = null;
        if (c != null) {
            try {
                c.unregisterCallback(mediaCallback);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Shows {@code s}, or hides the bar when null. */
    @VisibleForTesting
    void bind(@Nullable State s) {
        if (container == null) return;
        if (s == null) {
            container.setVisibility(View.GONE);
            return;
        }
        try {
            ensureInflated();
            title.setText(s.title == null || s.title.isEmpty()
                    ? activity.getString(R.string.cast_mini_casting_to, s.device) : s.title);
            subtitle.setText(s.title == null || s.title.isEmpty()
                    ? "" : activity.getString(R.string.cast_mini_casting_to, s.device));
            subtitle.setVisibility(s.title == null || s.title.isEmpty() ? View.GONE : View.VISIBLE);
            playPause.setImageResource(s.playing ? R.drawable.ic_pause : R.drawable.ic_play);
            container.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            container.setVisibility(View.GONE);
        }
    }

    private void ensureInflated() {
        if (bar != null || container == null) return;
        bar = LayoutInflater.from(activity).inflate(R.layout.view_cast_mini_bar, container, false);
        title = bar.findViewById(R.id.cast_mini_title);
        subtitle = bar.findViewById(R.id.cast_mini_subtitle);
        playPause = bar.findViewById(R.id.cast_mini_play_pause);
        container.removeAllViews();
        container.addView(bar);
        bar.setOnClickListener(v -> {
            try {
                if (CastSupport.isReady()) activity.startActivity(new Intent(activity, CastControllerActivity.class));
            } catch (Throwable ignored) {
            }
        });
        playPause.setOnClickListener(v -> {
            try {
                RemoteMediaClient c = client();
                if (c != null) c.togglePlayback();
            } catch (Throwable ignored) {
            }
        });
        bar.findViewById(R.id.cast_mini_stop).setOnClickListener(v -> {
            try {
                Caster.Remote r = Caster.get().remote(activity);
                if (r != null) r.endSession(); // hands the position back to the phone
            } catch (Throwable ignored) {
            }
            bind(null);
        });
    }

    @VisibleForTesting
    boolean isShowing() {
        return container != null && container.getVisibility() == View.VISIBLE;
    }

    @VisibleForTesting
    @Nullable
    View barView() {
        return bar;
    }
}
