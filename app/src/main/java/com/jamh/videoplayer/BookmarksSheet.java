package com.jamh.videoplayer;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.6: the player's bookmarks sheet. Tap a row to jump (keyframe seek, see
 * {@link PlayerActivity#jumpToBookmark}), ⋮ to rename or delete (with Undo), and the gradient
 * button adds one at the current position.
 */
final class BookmarksSheet {

    interface Host {
        @Nullable String videoKey();

        long positionMs();

        void jumpTo(long positionMs);

        /** Called after any add / rename / delete so the seek-bar ticks can refresh. */
        void onBookmarksChanged();
    }

    private BookmarksSheet() {
    }

    static void bind(View v, @Nullable Dialog dialog, BookmarkStore store, Host host) {
        RecyclerView list = v.findViewById(R.id.bookmarks_list);
        TextView empty = v.findViewById(R.id.bookmarks_empty);
        TextView title = v.findViewById(R.id.bookmarks_title);
        TextView add = v.findViewById(R.id.bookmarks_add);
        Adapter adapter = new Adapter();
        list.setLayoutManager(new LinearLayoutManager(v.getContext()));
        list.setAdapter(adapter);

        Runnable refresh = new Runnable() {
            @Override public void run() {
                List<BookmarkStore.Bookmark> items = store.list(host.videoKey());
                adapter.set(items);
                empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                list.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                title.setText(items.isEmpty() ? "Bookmarks" : "Bookmarks · " + items.size());
                add.setText("Add at " + Fmt.time(host.positionMs()));
            }
        };
        boolean available = host.videoKey() != null;
        add.setEnabled(available);
        add.setAlpha(available ? 1f : 0.4f);
        add.setOnClickListener(x -> {
            BookmarkStore.Bookmark b = store.add(host.videoKey(), host.positionMs(), null);
            if (b == null) return;
            host.onBookmarksChanged();
            refresh.run();
            int idx = adapter.indexOf(b.id);
            if (idx >= 0) list.scrollToPosition(idx);
        });
        adapter.onClick = b -> {
            host.jumpTo(b.timeMs);
            if (dialog != null) dialog.dismiss();
        };
        adapter.onMore = (b, anchor) -> {
            PopupMenu pm = new PopupMenu(anchor.getContext(), anchor);
            pm.getMenu().add(0, 1, 0, "Rename");
            pm.getMenu().add(0, 2, 1, "Delete");
            pm.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    showNameDialog(anchor.getContext(), "Rename bookmark", b.name, Fmt.time(b.timeMs), name -> {
                        store.rename(host.videoKey(), b.id, name);
                        host.onBookmarksChanged();
                        refresh.run();
                    });
                } else {
                    String key = host.videoKey();
                    store.delete(key, b.id);
                    host.onBookmarksChanged();
                    refresh.run();
                    Snackbar.make(v, "Deleted " + b.label(), Snackbar.LENGTH_LONG)
                            .setAction("Undo", u -> {
                                store.restore(key, b);
                                host.onBookmarksChanged();
                                refresh.run();
                            })
                            .show();
                }
                return true;
            });
            pm.show();
        };
        refresh.run();
    }

    interface NameCallback {
        void onName(String name);
    }

    /** Name / rename prompt (empty is allowed: the bookmark is then shown by its time). */
    static androidx.appcompat.app.AlertDialog showNameDialog(Context ctx, String title, @Nullable String current,
                                                             String time, NameCallback cb) {
        TextInputLayout til = new TextInputLayout(ctx, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint("Name (optional)");
        til.setHelperText("At " + time);
        til.setCounterEnabled(true);
        til.setCounterMaxLength(BookmarkStore.MAX_NAME);
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (current != null) {
            et.setText(current);
            et.setSelection(current.length());
        }
        til.addView(et);
        FrameLayout box = new FrameLayout(ctx);
        int pad = (int) (20 * ctx.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(til, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Save", (dd, w) -> cb.onName(et.getText() == null ? "" : et.getText().toString()))
                .create();
        et.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                cb.onName(et.getText() == null ? "" : et.getText().toString());
                d.dismiss();
                return true;
            }
            return false;
        });
        d.show();
        return d;
    }

    // ------------------------------------------------------------------ list

    interface OnClick {
        void on(BookmarkStore.Bookmark b);
    }

    interface OnMore {
        void on(BookmarkStore.Bookmark b, View anchor);
    }

    static final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<BookmarkStore.Bookmark> items = new ArrayList<>();
        OnClick onClick;
        OnMore onMore;

        @SuppressWarnings("NotifyDataSetChanged")
        void set(List<BookmarkStore.Bookmark> l) {
            items.clear();
            items.addAll(l);
            notifyDataSetChanged();
        }

        int indexOf(long id) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).id == id) return i;
            return -1;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bookmark, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            BookmarkStore.Bookmark b = items.get(position);
            h.time.setText(Fmt.time(b.timeMs));
            h.name.setText(b.name.isEmpty() ? "Untitled bookmark" : b.name);
            h.name.setAlpha(b.name.isEmpty() ? 0.7f : 1f);
            h.itemView.setContentDescription(b.label() + ". Tap to jump");
            h.itemView.setOnClickListener(x -> { if (onClick != null) onClick.on(b); });
            h.more.setOnClickListener(x -> { if (onMore != null) onMore.on(b, x); });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView time, name;
            final View more;

            VH(View v) {
                super(v);
                time = v.findViewById(R.id.bookmark_time);
                name = v.findViewById(R.id.bookmark_name);
                more = v.findViewById(R.id.bookmark_more);
            }
        }
    }
}
