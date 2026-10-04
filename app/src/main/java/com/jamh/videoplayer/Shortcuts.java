package com.jamh.videoplayer;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

/**
 * Launcher shortcuts: a dynamic "Continue watching" shortcut to the last played video (the
 * static "Clips" shortcut lives in res/xml/shortcuts.xml).
 */
final class Shortcuts {

    static final String ID_CONTINUE = "continue_watching";
    static final String ACTION_OPEN_CLIPS = "com.jamh.videoplayer.action.OPEN_CLIPS";

    private Shortcuts() {
    }

    /**
     * Only URIs we can still open later from a launcher tap qualify: MediaStore rows (covered by
     * our media permission), file paths and web URLs. Content URIs granted temporarily by another
     * app (Photos, Files, Drive, share sheet) expire, so the shortcut would just fail.
     */
    static boolean canReopenLater(Uri uri, long videoId) {
        if (uri != null && Vault.isVaultUri(uri)) return false; // private videos never leave the app
        if (videoId >= 0) return true;
        String s = uri.getScheme();
        if ("file".equals(s) || "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s)) return true;
        return "content".equals(s) && MediaStore.AUTHORITY.equals(uri.getAuthority());
    }

    @WorkerThread
    static void pushContinueWatching(Context ctx, Uri uri, long videoId, String title, @Nullable VideoItem thumbSource) {
        if (!canReopenLater(uri, videoId)) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri, ctx, PlayerActivity.class);
            if (videoId >= 0) i.putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId);
            IconCompat icon = null;
            if (thumbSource != null && thumbSource.id >= 0) {
                Bitmap thumb = ThumbnailLoader.loadSync(ctx, thumbSource);
                if (thumb != null) icon = IconCompat.createWithAdaptiveBitmap(adaptiveIcon(thumb));
            }
            if (icon == null) icon = IconCompat.createWithResource(ctx, R.drawable.ic_shortcut_continue);
            String label = title == null || title.isEmpty() ? "Video" : title;
            ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(ctx, ID_CONTINUE)
                    .setShortLabel("Continue watching")
                    .setLongLabel(ellipsize("Continue: " + label, 25))
                    .setDisabledMessage("This video is no longer available")
                    .setIcon(icon)
                    .setIntent(i)
                    .setRank(0)
                    .build();
            ShortcutManagerCompat.pushDynamicShortcut(ctx, info);
        } catch (RuntimeException ignored) {
            // Launcher without shortcut support, or rate-limited: not important enough to surface.
        }
    }

    /** Drops "Continue watching" when it points at a video that was moved to Private / deleted. */
    static void removeContinueWatchingFor(Context ctx, long videoId, @Nullable Uri uri) {
        try {
            for (ShortcutInfoCompat sc : ShortcutManagerCompat.getDynamicShortcuts(ctx)) {
                if (!ID_CONTINUE.equals(sc.getId())) continue;
                Intent i = sc.getIntent();
                boolean match = (videoId >= 0 && i.getLongExtra(PlayerActivity.EXTRA_VIDEO_ID, -1) == videoId)
                        || (uri != null && uri.equals(i.getData()));
                if (match) {
                    ShortcutManagerCompat.removeDynamicShortcuts(ctx, java.util.Collections.singletonList(ID_CONTINUE));
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static String ellipsize(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }

    /** 108 dp adaptive-icon canvas: centre-cropped thumbnail with a play badge in the safe zone. */
    static Bitmap adaptiveIcon(Bitmap thumb) {
        int size = 324; // 108 dp at 3x
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int s = Math.min(thumb.getWidth(), thumb.getHeight());
        Rect src = new Rect((thumb.getWidth() - s) / 2, (thumb.getHeight() - s) / 2,
                (thumb.getWidth() + s) / 2, (thumb.getHeight() + s) / 2);
        c.drawBitmap(thumb, src, new Rect(0, 0, size, size), new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x99000000);
        float cx = size / 2f, cy = size / 2f, r = size * 0.16f;
        c.drawCircle(cx, cy, r, p);
        p.setColor(0xFFFFFFFF);
        Path tri = new Path();
        tri.moveTo(cx - r * 0.35f, cy - r * 0.5f);
        tri.lineTo(cx + r * 0.55f, cy);
        tri.lineTo(cx - r * 0.35f, cy + r * 0.5f);
        tri.close();
        c.drawPath(tri, p);
        return out;
    }
}
