package com.jamh.videoplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small async MediaStore thumbnail loader with an in-memory LRU cache. */
public final class ThumbnailLoader {
    private static final LruCache<Long, Bitmap> CACHE =
            new LruCache<Long, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 8, 64L * 1024 * 1024)) {
                @Override
                protected int sizeOf(Long key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ThumbnailLoader() {}

    public static void load(ImageView view, VideoItem item) {
        view.setTag(R.id.tag_thumb, item.id);
        Bitmap cached = CACHE.get(item.id);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        final Context app = view.getContext().getApplicationContext();
        EXECUTOR.execute(() -> {
            Bitmap bmp = loadSync(app, item);
            if (bmp == null) return;
            CACHE.put(item.id, bmp);
            MAIN.post(() -> {
                Object tag = view.getTag(R.id.tag_thumb);
                if (tag instanceof Long && (Long) tag == item.id) view.setImageBitmap(bmp);
            });
        });
    }

    @SuppressWarnings("deprecation")
    private static Bitmap loadSync(Context context, VideoItem item) {
        ContentResolver cr = context.getContentResolver();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return cr.loadThumbnail(item.uri, new Size(384, 216), null);
            } else {
                return MediaStore.Video.Thumbnails.getThumbnail(cr, item.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null);
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
