package com.jamh.videoplayer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Shows either videos or folders, in grid or list layout. */
public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.Holder> {

    public interface Listener {
        void onVideoClick(VideoItem item);
        void onFolderClick(Folder folder);
        void onClipClick(VideoItem clip);

        /** Long-press on a video or clip: options menu (Move to Private...). */
        default void onVideoLongClick(VideoItem item, View anchor) {
        }
    }

    private final List<Object> items = new ArrayList<>();
    private final Listener listener;
    private boolean grid = true;
    private boolean clipsMode;

    public void setClipsMode(boolean clipsMode) {
        if (this.clipsMode == clipsMode) return;
        this.clipsMode = clipsMode;
        notifyDataSetChanged();
    }

    public LibraryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setGrid(boolean grid) {
        this.grid = grid;
        notifyDataSetChanged();
    }

    public void submit(List<?> data) {
        items.clear();
        items.addAll(data);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (clipsMode) return 2;
        return grid ? 1 : 0;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == 2 ? R.layout.item_clip
                : viewType == 1 ? R.layout.item_video_grid : R.layout.item_video_list;
        Holder h = new Holder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
        if (viewType == 2 && h.thumb instanceof RatioImageView) ((RatioImageView) h.thumb).setRatio(16f / 9f);
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Object o = items.get(position);
        Context ctx = h.itemView.getContext();
        if (o instanceof VideoItem && clipsMode) {
            VideoItem v = (VideoItem) o;
            h.title.setText(v.title);
            h.duration.setText(Fmt.time(v.durationMs));
            ThumbnailLoader.load(h.thumb, v);
            h.itemView.setOnClickListener(x -> listener.onClipClick(v));
            h.itemView.setOnLongClickListener(x -> {
                listener.onVideoLongClick(v, x);
                return true;
            });
        } else if (o instanceof VideoItem) {
            VideoItem v = (VideoItem) o;
            h.title.setText(v.title);
            String meta = grid ? Fmt.size(v.sizeBytes)
                    : join(Fmt.size(v.sizeBytes), Fmt.date(v.dateAddedSec),
                           v.height > 0 ? Math.min(v.width, v.height) + "p" : "");
            h.meta.setText(meta);
            h.duration.setVisibility(View.VISIBLE);
            h.duration.setText(Fmt.time(v.durationMs));
            h.folderBadge.setVisibility(View.GONE);
            h.thumb.setAlpha(1f);
            ThumbnailLoader.load(h.thumb, v);
            long[] resume = Prefs.getResume(ctx, v.resumeKey());
            long dur = v.durationMs > 0 ? v.durationMs : (resume != null ? resume[1] : 0);
            if (resume != null && dur > 0) {
                h.resume.setVisibility(View.VISIBLE);
                h.resume.setProgress((int) (1000 * resume[0] / dur));
            } else {
                h.resume.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(x -> listener.onVideoClick(v));
            h.itemView.setOnLongClickListener(x -> {
                listener.onVideoLongClick(v, x);
                return true;
            });
        } else {
            Folder f = (Folder) o;
            h.title.setText(f.name);
            int n = f.videos.size();
            h.meta.setText(join(n + (n == 1 ? " video" : " videos"), Fmt.size(f.totalSize)));
            h.duration.setVisibility(View.GONE);
            h.resume.setVisibility(View.GONE);
            h.folderBadge.setVisibility(View.VISIBLE);
            VideoItem cover = f.cover();
            if (cover != null) {
                ThumbnailLoader.load(h.thumb, cover);
                h.thumb.setAlpha(grid ? 1f : 0.45f);
            } else {
                h.thumb.setImageDrawable(null);
            }
            h.itemView.setOnClickListener(x -> listener.onFolderClick(f));
            h.itemView.setOnLongClickListener(null);
            h.itemView.setLongClickable(false);
        }
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(p);
        }
        return sb.toString();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb, folderBadge;
        final TextView title, meta, duration;
        final ProgressBar resume;

        Holder(View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);  // present in all layouts
            folderBadge = v.findViewById(R.id.folder_badge);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            duration = v.findViewById(R.id.duration);
            resume = v.findViewById(R.id.resume_progress);
        }
    }
}
