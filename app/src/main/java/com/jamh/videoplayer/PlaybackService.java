package com.jamh.videoplayer;

import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Foreground media service for background audio. The ExoPlayer and MediaSession are owned by
 * PlayerActivity; this service just hosts the session so Media3 posts the media notification
 * (play/pause, ±10 s, previous/next) which also appears on the lock screen.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlaybackService extends MediaSessionService {

    private static MediaSession session;
    private static PlaybackService instance;

    /** Registers (or replaces) the session hosted by the service. */
    static void attach(MediaSession s) {
        MediaSession old = session;
        session = s;
        if (instance != null) {
            if (old != null && old != s) instance.removeSession(old);
            if (s != null && !instance.isSessionAdded(s)) instance.addSession(s);
        }
    }

    /** Removes the session and stops the service (background audio turned off / player closed). */
    static void detachAndStop(MediaSession s) {
        if (session == s) session = null;
        if (instance != null) {
            if (s != null && instance.isSessionAdded(s)) instance.removeSession(s);
            instance.stopSelf();
        }
    }

    static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (session != null) addSession(session);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        Player p = session == null ? null : session.getPlayer();
        if (p == null || !p.getPlayWhenReady() || p.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
