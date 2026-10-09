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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shows either videos or folders, in grid or list layout. */
public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.Holder> {

    public interface Listener {
        void onVideoClick(VideoItem item);
        void onFolderClick(Folder folder);
        void onClipClick(VideoItem clip);

        /** Long-press on a video or clip: starts multi-select (1.4.0). */
        default void onVideoLongClick(VideoItem item, View anchor) {
        }

        /** Selection changed (count may be 0). */
        default void onSelectionChanged(int count) {
        }
    }

    // ------------------------------------------------------------- multi-select (1.4.0)
    private final Set<Long> selected = new LinkedHashSet<>();
    private boolean selectionMode;

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean on) {
        if (selectionMode == on) return;
        selectionMode = on;
        if (!on) selected.clear();
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    public void toggle(VideoItem v) {
        if (v == null || v.id < 0) return;
        if (!selected.remove(v.id)) selected.add(v.id);
        int pos = items.indexOf(v);
        if (pos >= 0) notifyItemChanged(pos); else notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    public boolean isSelected(VideoItem v) {
        return v != null && selected.contains(v.id);
    }

    /** Selects every video currently shown (folders are skipped). */
    public void selectAllVisible() {
        for (Object o : items) if (o instanceof VideoItem && ((VideoItem) o).id >= 0) selected.add(((VideoItem) o).id);
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    public int visibleVideoCount() {
        int n = 0;
        for (Object o : items) if (o instanceof VideoItem && ((VideoItem) o).id >= 0) n++;
        return n;
    }

    public int selectedCount() {
        return selected.size();
    }

    public Set<Long> selectedIds() {
        return new LinkedHashSet<>(selected);
    }

    /** Restores a selection (e.g. after rotation). */
    public void restoreSelection(long[] ids) {
        selected.clear();
        if (ids != null) for (long id : ids) selected.add(id);
        selectionMode = true;
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    /** Drops ids that are no longer in {@code present} (e.g. after videos were moved away). */
    public void retainSelection(Set<Long> present) {
        if (selected.retainAll(present)) listener.onSelectionChanged(selected.size());
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
            bindSelection(h, v);
            h.itemView.setOnClickListener(x -> {
                if (selectionMode) toggle(v); else listener.onClipClick(v);
            });
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
            bindSelection(h, v);
            h.itemView.setOnClickListener(x -> {
                if (selectionMode) toggle(v); else listener.onVideoClick(v);
            });
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
            bindSelection(h, null);
            h.itemView.setAlpha(selectionMode ? 0.4f : 1f);
            h.itemView.setOnClickListener(x -> {
                if (!selectionMode) listener.onFolderClick(f);
            });
            h.itemView.setOnLongClickListener(null);
            h.itemView.setLongClickable(false);
        }
    }

    private void bindSelection(Holder h, VideoItem v) {
        h.itemView.setAlpha(1f);
        boolean sel = v != null && selected.contains(v.id);
        if (h.selectionOverlay != null) h.selectionOverlay.setVisibility(sel ? View.VISIBLE : View.GONE);
        if (h.check != null) {
            h.check.setVisibility(selectionMode && v != null ? View.VISIBLE : View.GONE);
            h.check.setBackgroundResource(sel ? R.drawable.bg_check_on : R.drawable.bg_check_off);
            h.check.setImageResource(sel ? R.drawable.ic_check : 0);
        }
        h.itemView.setActivated(sel);
        if (v != null) {
            h.itemView.setContentDescription(selectionMode ? (sel ? "Selected: " : "Not selected: ") + v.title : null);
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
        final ImageView thumb, folderBadge, check;
        final TextView title, meta, duration;
        final ProgressBar resume;
        final View selectionOverlay;

        Holder(View v) {
            super(v);
            check = v.findViewById(R.id.select_check);
            selectionOverlay = v.findViewById(R.id.selection_overlay);
            View frame = v.findViewById(R.id.thumb_frame);
            if (frame != null) frame.setClipToOutline(true);   // rounded Ulfur thumbnails
            thumb = v.findViewById(R.id.thumb);  // present in all layouts
            folderBadge = v.findViewById(R.id.folder_badge);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            duration = v.findViewById(R.id.duration);
            resume = v.findViewById(R.id.resume_progress);
        }
    }
}
