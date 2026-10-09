package com.jamh.videoplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaTrack;
import com.google.android.gms.cast.TextTrackStyle;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.7.0: Cast to TV. Sends a video to the connected Chromecast (Default Media Receiver):
 * network streams by their URL, local and Private videos through {@link LocalMediaServer}
 * (Private ones decrypted on the fly, after a "visible on the TV" warning). Starts at the
 * phone's position, and when casting ends the TV's position becomes the video's resume point
 * (and the open player jumps there). The server stops when the Cast session ends.
 */
final class Caster {

    /** What a Cast session can do (the real one wraps the Cast SDK; tests use a fake). */
    interface Remote {
        boolean isConnected();

        @Nullable
        String deviceName();

        void load(MediaLoadRequestData request, LoadCallback cb);

        void endSession();
    }

    interface LoadCallback {
        void done(boolean ok, @Nullable String error);
    }

    interface Listener {
        /** A TV got connected (the player offers to move the current video there). */
        default void onSessionStarted() {
        }

        /** Casting ended; {@code key} resumes at {@code positionMs} (resume key, or vault file for private). */
        default void onCastHandedBack(String key, boolean isPrivate, long positionMs) {
        }

        default void onCastingChanged() {
        }
    }

    /** What to cast. */
    static final class Request {
        Uri uri;
        String title = "Video";
        @Nullable String mime;
        long positionMs;
        long durationMs;
        /** Gallery: {@link VideoItem#resumeKey()}; Private: the entry's logical file name. */
        String key;
        boolean isPrivate;
        @Nullable Vault.Entry entry;
        @Nullable Uri subtitleUri;
        @Nullable String subtitleName;
        /** Gallery video whose thumbnail becomes the poster (notification / mini controller). */
        @Nullable VideoItem posterItem;
        boolean openController = true;
    }

    /** What is playing on the TV. */
    static final class Casting {
        final String key;
        final boolean isPrivate;
        final long durationMs;
        final String contentUrl;

        Casting(String key, boolean isPrivate, long durationMs, String contentUrl) {
            this.key = key;
            this.isPrivate = isPrivate;
            this.durationMs = durationMs;
            this.contentUrl = contentUrl;
        }
    }

    static final class NoWifiException extends IOException {
        NoWifiException() {
            super("Not on Wi-Fi");
        }
    }

    private static final Caster INSTANCE = new Caster();
    private static final long MAX_SUBTITLE_BYTES = 4 * 1024 * 1024;

    @VisibleForTesting static volatile Remote testRemote;
    /** Tests: bind the server here (loopback) instead of the Wi-Fi address. */
    @VisibleForTesting static volatile InetAddress testBindAddress;
    @VisibleForTesting static volatile AlertDialog lastWarning;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "caster");
        t.setDaemon(true);
        return t;
    });
    /** Looked up per use (cheap) so it always targets the current main looper. */
    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private Context app;
    private boolean attached;
    @Nullable private LocalMediaServer server;
    @Nullable private volatile Casting current;
    private volatile long lastKnownPositionMs = -1;

    static Caster get() {
        return INSTANCE;
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------ session

    @MainThread
    void attach(Context appCtx, CastContext cc) {
        if (attached) return;
        attached = true;
        app = appCtx.getApplicationContext();
        cc.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
    }

    @Nullable
    Remote remote(Context ctx) {
        if (testRemote != null) return testRemote;
        CastContext cc = CastSupport.context(ctx);
        return cc == null ? null : new CastRemote(cc);
    }

    boolean isConnected(Context ctx) {
        Remote r = remote(ctx);
        return r != null && r.isConnected();
    }

    @Nullable
    Casting current() {
        return current;
    }

    /** True while this video (resume key / vault file) is the one on the TV. */
    boolean isCasting(@Nullable String key) {
        Casting c = current;
        return key != null && c != null && c.key.equals(key);
    }

    private final RemoteMediaClient.ProgressListener progress = (pos, dur) -> {
        if (current != null && pos > 0) lastKnownPositionMs = pos;
    };

    private final SessionManagerListener<CastSession> sessionListener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarting(CastSession s) {
        }

        @Override public void onSessionStarted(CastSession s, String id) {
            watch(s);
            for (Listener l : listeners) l.onSessionStarted();
        }

        @Override public void onSessionStartFailed(CastSession s, int e) {
            handleSessionEnded(app);
        }

        @Override public void onSessionEnding(CastSession s) {
            RemoteMediaClient c = s.getRemoteMediaClient();
            if (c != null && current != null) {
                MediaInfo mi = c.getMediaInfo();
                // Someone else may have taken over the TV: only hand back our own video's position.
                if (mi == null || current.contentUrl.equals(mi.getContentId())
                        || current.contentUrl.equals(mi.getContentUrl())) {
                    handleSessionEnding(c.getApproximateStreamPosition());
                }
            }
        }

        @Override public void onSessionEnded(CastSession s, int e) {
            handleSessionEnded(app);
        }

        @Override public void onSessionResuming(CastSession s, String id) {
        }

        @Override public void onSessionResumed(CastSession s, boolean wasSuspended) {
            watch(s);
        }

        @Override public void onSessionResumeFailed(CastSession s, int e) {
            handleSessionEnded(app);
        }

        @Override public void onSessionSuspended(CastSession s, int reason) {
        }
    };

    private void watch(CastSession s) {
        RemoteMediaClient c = s.getRemoteMediaClient();
        if (c != null) {
            c.removeProgressListener(progress);
            c.addProgressListener(progress, 1000);
        }
    }

    @VisibleForTesting
    void handleSessionEnding(long positionMs) {
        if (positionMs > 0) lastKnownPositionMs = positionMs;
    }

    /** Session over: stop serving, hand the TV's position back to the phone. */
    @VisibleForTesting
    void handleSessionEnded(@Nullable Context ctx) {
        Casting c = current;
        current = null;
        long pos = lastKnownPositionMs;
        lastKnownPositionMs = -1;
        stopServer();
        if (c == null) return;
        Context context = ctx != null ? ctx.getApplicationContext() : app;
        if (pos >= 0 && context != null) {
            if (c.isPrivate) PrivateResume.save(context, c.key, pos, c.durationMs);
            else Prefs.saveResume(context, c.key, pos, c.durationMs);
            WatchHistory.mark(context, c.key, c.isPrivate);
        }
        main().post(() -> {
            for (Listener l : listeners) {
                if (pos >= 0) l.onCastHandedBack(c.key, c.isPrivate, pos);
                l.onCastingChanged();
            }
        });
    }

    /** Keeps the hand-back position fresh (also used by tests). */
    @VisibleForTesting
    void onRemoteProgress(long positionMs) {
        if (current != null && positionMs > 0) lastKnownPositionMs = positionMs;
    }

    // ------------------------------------------------------------------ casting

    void castGalleryVideo(Activity a, VideoItem v) {
        Request r = new Request();
        r.uri = v.uri;
        r.title = v.title;
        r.key = v.resumeKey();
        r.durationMs = v.durationMs;
        r.mime = mimeFromName(v.displayName);
        r.posterItem = v;
        long[] resume = Prefs.getResume(a, r.key);
        if (resume != null && (v.durationMs <= 0 || resume[0] < v.durationMs - 5000)) r.positionMs = resume[0];
        final Context appCtx = a.getApplicationContext();
        io.execute(() -> {
            // A matching .srt / .vtt next to the video goes along as subtitles.
            SubtitleFinder.Found f = SubtitleFinder.find(appCtx, v.uri, v.displayName);
            main().post(() -> {
                if (f != null) {
                    r.subtitleUri = f.uri;
                    r.subtitleName = f.name;
                }
                cast(a, r, null);
            });
        });
    }

    void castPrivateVideo(Activity a, Vault.Entry e) {
        Request r = new Request();
        r.uri = Vault.uriFor(a, e);
        r.title = e.name;
        r.key = e.file;
        r.isPrivate = true;
        r.entry = e;
        r.durationMs = e.durationMs;
        r.mime = e.mime;
        long[] resume = PrivateResume.get(a, e.file);
        if (resume != null) r.positionMs = resume[0];
        cast(a, r, null);
    }

    /** Casts {@code r}; Private videos ask first. {@code onStarted} runs once the TV accepted it. */
    @MainThread
    void cast(Activity a, Request r, @Nullable Runnable onStarted) {
        Remote rm = remote(a);
        if (rm == null || !rm.isConnected()) {
            Toast.makeText(a, "Connect to a TV first: tap the Cast button", Toast.LENGTH_SHORT).show();
            return;
        }
        if (r.isPrivate) {
            confirmPrivate(a, rm.deviceName(), () -> start(a, r, rm, onStarted));
        } else {
            start(a, r, rm, onStarted);
        }
    }

    /** "It'll be visible on the TV" warning before a Private video leaves the phone. */
    @MainThread
    AlertDialog confirmPrivate(Activity a, @Nullable String device, Runnable go) {
        String tv = device == null || device.isEmpty() ? "the TV" : device;
        AlertDialog d = new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.cast_private_title)
                .setMessage(a.getString(R.string.cast_private_body, tv))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cast_private_confirm, (dlg, w) -> go.run())
                .show();
        lastWarning = d;
        return d;
    }

    private void start(Activity a, Request r, Remote rm, @Nullable Runnable onStarted) {
        final Context appCtx = a.getApplicationContext();
        if (app == null) app = appCtx;
        io.execute(() -> {
            MediaLoadRequestData req;
            String contentUrl;
            try {
                req = buildLoad(appCtx, r);
                contentUrl = req.getMediaInfo() == null ? "" : req.getMediaInfo().getContentId();
            } catch (NoWifiException e) {
                toast(appCtx, appCtx.getString(R.string.cast_no_wifi));
                return;
            } catch (IOException | RuntimeException e) {
                toast(appCtx, "Couldn't cast this video: " + (e.getMessage() == null ? "can't read it" : e.getMessage()));
                return;
            }
            main().post(() -> rm.load(req, (ok, err) -> {
                if (!ok) {
                    Toast.makeText(appCtx, "The TV couldn't play this video" + (err == null || err.isEmpty() ? "" : ": " + err),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                current = new Casting(r.key, r.isPrivate, r.durationMs, contentUrl);
                lastKnownPositionMs = r.positionMs;
                if (!r.isPrivate) WatchHistory.mark(appCtx, r.key, false);
                else WatchHistory.mark(appCtx, r.key, true);
                if (onStarted != null) onStarted.run();
                for (Listener l : listeners) l.onCastingChanged();
                requestNotificationPermission(a);
                if (r.openController && !a.isFinishing() && testRemote == null) {
                    a.startActivity(new Intent(a, CastControllerActivity.class));
                }
            }));
        });
    }

    /** Worker thread: registers the media with the server (if local) and builds the load request. */
    @VisibleForTesting
    MediaLoadRequestData buildLoad(Context ctx, Request r) throws IOException {
        boolean network = isNetwork(r.uri);
        String mime = r.mime != null && !r.mime.isEmpty() ? r.mime
                : network ? guessNetworkMime(r.uri) : mimeFromName(r.uri.getLastPathSegment());
        LocalMediaServer s = null;
        String url;
        if (network) {
            url = r.uri.toString();
        } else {
            if (r.isPrivate && r.entry == null) r.entry = Vault.findByUri(ctx, r.uri);
            if (r.isPrivate && r.entry == null) throw new IOException("it's no longer in Private");
            s = ensureServer(ctx);
            s.unregisterAll(); // only what's on the TV now is reachable
            LocalMediaServer.Source src = r.isPrivate
                    ? new LocalMediaServer.VaultSource(ctx, r.entry)
                    : "file".equals(r.uri.getScheme()) && r.uri.getPath() != null
                    ? new LocalMediaServer.FileSource(new java.io.File(r.uri.getPath()), mime)
                    : new LocalMediaServer.ContentSource(ctx, r.uri, mime);
            src.length(); // fail here (with a message) rather than on the TV
            url = s.register(src, r.isPrivate ? "private" + extFor(mime) : safeName(r.title) + extFor(mime));
        }
        MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        md.putString(MediaMetadata.KEY_TITLE, r.isPrivate ? "Private video" : r.title);
        md.putString(MediaMetadata.KEY_SUBTITLE, ctx.getString(R.string.app_name));
        if (!r.isPrivate && r.posterItem != null) {
            if (s == null) s = ensureServerOrNull(ctx);
            if (s != null) {
                VideoItem item = r.posterItem;
                String poster = s.register(new LocalMediaServer.BytesSource(() -> jpeg(ThumbnailLoader.loadSync(ctx, item)),
                        "image/jpeg"), "poster.jpg");
                md.addImage(new WebImage(Uri.parse(poster)));
            }
        }
        List<MediaTrack> tracks = new ArrayList<>();
        if (r.subtitleUri != null && SubtitleConvert.supported(r.subtitleName)) {
            if (s == null) s = ensureServerOrNull(ctx);
            byte[] vtt = readSubtitle(ctx, r.subtitleUri);
            if (s != null && vtt != null) {
                String subUrl = s.register(new LocalMediaServer.BytesSource(vtt, "text/vtt"), "subtitles.vtt");
                tracks.add(new MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
                        .setName(r.subtitleName == null ? "Subtitles" : r.subtitleName)
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setContentId(subUrl)
                        .setContentType("text/vtt")
                        .setLanguage("und")
                        .build());
            }
        }
        MediaInfo.Builder info = new MediaInfo.Builder(url)
                .setContentUrl(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mime)
                .setMetadata(md);
        if (r.durationMs > 0) info.setStreamDuration(r.durationMs);
        if (!tracks.isEmpty()) {
            info.setMediaTracks(tracks);
            TextTrackStyle style = new TextTrackStyle();
            style.setForegroundColor(0xFFFFFFFF);
            style.setEdgeType(TextTrackStyle.EDGE_TYPE_OUTLINE);
            style.setEdgeColor(0xFF000000);
            style.setBackgroundColor(0x00000000);
            info.setTextTrackStyle(style);
        }
        MediaLoadRequestData.Builder b = new MediaLoadRequestData.Builder()
                .setMediaInfo(info.build())
                .setAutoplay(true)
                .setCurrentTime(Math.max(0, r.positionMs));
        if (!tracks.isEmpty()) b.setActiveTrackIds(new long[]{1});
        return b.build();
    }

    // ------------------------------------------------------------------ server

    synchronized LocalMediaServer ensureServer(Context ctx) throws IOException {
        InetAddress addr = testBindAddress != null ? testBindAddress : WifiAddress.find(ctx);
        if (addr == null) throw new NoWifiException();
        if (server != null && server.isRunning() && server.address().equals(addr)) return server;
        if (server != null) server.stop();
        server = LocalMediaServer.start(addr, testBindAddress != null);
        return server;
    }

    @Nullable
    private LocalMediaServer ensureServerOrNull(Context ctx) {
        try {
            return ensureServer(ctx);
        } catch (IOException e) {
            return null;
        }
    }

    synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @VisibleForTesting
    @Nullable
    synchronized LocalMediaServer server() {
        return server;
    }

    // ------------------------------------------------------------------ helpers

    private void toast(Context ctx, String msg) {
        main().post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    private static void requestNotificationPermission(Activity a) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(a,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                ActivityCompat.requestPermissions(a, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7107);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Nullable
    private static byte[] readSubtitle(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_SUBTITLE_BYTES) return null;
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return SubtitleConvert.toVtt(text).getBytes(StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static byte[] jpeg(@Nullable Bitmap b) {
        if (b == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return out.toByteArray();
    }

    static boolean isNetwork(@Nullable Uri u) {
        String s = u == null ? null : u.getScheme();
        return "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s);
    }

    static String mimeFromName(@Nullable String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".m3u8")) return "application/x-mpegURL";
        if (n.endsWith(".mpd")) return "application/dash+xml";
        if (n.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }

    private static String guessNetworkMime(Uri u) {
        String p = u.getPath() == null ? "" : u.getPath();
        return mimeFromName(p);
    }

    private static String extFor(String mime) {
        switch (mime) {
            case "video/webm": return ".webm";
            case "video/x-matroska": return ".mkv";
            case "video/quicktime": return ".mov";
            case "video/mp2t": return ".ts";
            default: return ".mp4";
        }
    }

    private static String safeName(@Nullable String title) {
        String t = title == null ? "video" : title.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (t.length() > 60) t = t.substring(0, 60);
        return t.isEmpty() ? "video" : t;
    }

    /** The real Cast session. */
    private static final class CastRemote implements Remote {
        private final CastContext cc;

        CastRemote(CastContext cc) {
            this.cc = cc;
        }

        @Nullable
        private CastSession session() {
            try {
                return cc.getSessionManager().getCurrentCastSession();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override public boolean isConnected() {
            CastSession s = session();
            return s != null && s.isConnected();
        }

        @Nullable
        @Override public String deviceName() {
            CastSession s = session();
            return s == null || s.getCastDevice() == null ? null : s.getCastDevice().getFriendlyName();
        }

        @Override public void load(MediaLoadRequestData request, LoadCallback cb) {
            CastSession s = session();
            RemoteMediaClient c = s == null ? null : s.getRemoteMediaClient();
            if (c == null) {
                cb.done(false, "not connected");
                return;
            }
            c.load(request).setResultCallback(res -> cb.done(res.getStatus().isSuccess(),
                    res.getStatus().getStatusMessage()));
        }

        @Override public void endSession() {
            cc.getSessionManager().endCurrentSession(true);
        }
    }
}
