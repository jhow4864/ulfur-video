package com.jamh.videoplayer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.6.0 Trash. Two modes:
 * <ul>
 *   <li>Gallery (library ⋮ → Trash, Android 11+): Android's own trash (MediaStore IS_TRASHED),
 *       30 days, purged by the system. Restore / delete forever go through MediaStore requests
 *       (auto-approved with media management).</li>
 *   <li>Private ({@link #EXTRA_PRIVATE}, from Private videos ⋮): the app-internal trash; files
 *       stay encrypted; purged after 30 days. FLAG_SECURE, and only while Private is unlocked.</li>
 * </ul>
 */
public class TrashActivity extends AppCompatActivity {

    static final String EXTRA_PRIVATE = "private";

    /** One row: either a gallery item or a private entry. */
    static final class Row {
        @Nullable final GalleryTrash.Item gallery;
        @Nullable final Vault.Entry vault;

        Row(@Nullable GalleryTrash.Item g, @Nullable Vault.Entry v) {
            gallery = g;
            vault = v;
        }

        String title() {
            return gallery != null ? gallery.video.title : vault.name;
        }

        long size() {
            return gallery != null ? gallery.video.sizeBytes : vault.size;
        }

        long expiresMs() {
            return gallery != null ? gallery.expiresSec * 1000L : Vault.expiresAt(vault);
        }

        String key() {
            return gallery != null ? "g" + gallery.video.id : "v" + vault.id;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private boolean privateMode;
    private TextView empty, info;
    private Adapter adapter;
    private ActivityResultLauncher<IntentSenderRequest> requestLauncher;
    @Nullable private List<Row> pendingRequest;
    private int pendingKind;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        privateMode = getIntent().getBooleanExtra(EXTRA_PRIVATE, false);
        if (privateMode) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        }
        setContentView(R.layout.activity_trash);
        View root = findViewById(R.id.trash_root);
        MaterialToolbar tb = findViewById(R.id.trash_toolbar);
        if (privateMode) {
            root.setBackgroundColor(ContextCompat.getColor(this, R.color.private_bg));
            tb.setBackgroundColor(ContextCompat.getColor(this, R.color.private_bg));
            findViewById(R.id.trash_divider).setBackgroundColor(ContextCompat.getColor(this, R.color.private_stroke));
            tb.setTitle(R.string.trash_private_title);
        }
        tb.setNavigationOnClickListener(v -> finish());
        tb.inflateMenu(R.menu.trash);
        tb.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_trash_restore_all) {
                restore(new ArrayList<>(rows));
                return true;
            } else if (item.getItemId() == R.id.action_trash_empty) {
                confirmDeleteForever(new ArrayList<>(rows), true);
                return true;
            }
            return false;
        });
        info = findViewById(R.id.trash_info);
        empty = findViewById(R.id.trash_empty);
        info.setText(privateMode ? R.string.trash_info_private
                : DeleteVideosFlow.usesTrash() ? R.string.trash_info_gallery : R.string.trash_info_no_trash);
        RecyclerView list = findViewById(R.id.trash_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
        requestLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(),
                r -> onRequestResult(r.getResultCode() == Activity.RESULT_OK));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (privateMode && !VaultLock.isUnlocked()) {
            // Locked meanwhile: Private videos asks for the fingerprint again, not this screen.
            rows.clear();
            adapter.notifyDataSetChanged();
            finish();
            return;
        }
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    void reload() {
        final Context app = getApplicationContext();
        io.execute(() -> {
            List<Row> l = new ArrayList<>();
            if (privateMode) {
                Vault.purgeExpired(app, System.currentTimeMillis());
                for (Vault.Entry e : Vault.listTrashed(app)) l.add(new Row(null, e));
            } else {
                for (GalleryTrash.Item g : GalleryTrash.query(app)) l.add(new Row(g, null));
            }
            handler.post(() -> {
                if (isDestroyed()) return;
                bind(l);
            });
        });
    }

    @VisibleForTesting
    void bind(List<Row> l) {
        rows.clear();
        rows.addAll(l);
        adapter.notifyDataSetChanged();
        empty.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
        MaterialToolbar tb = findViewById(R.id.trash_toolbar);
        tb.getMenu().findItem(R.id.action_trash_restore_all).setVisible(!l.isEmpty());
        tb.getMenu().findItem(R.id.action_trash_empty).setVisible(!l.isEmpty());
        long bytes = 0;
        for (Row r : l) bytes += Math.max(0, r.size());
        String base = getString(privateMode ? R.string.trash_info_private
                : DeleteVideosFlow.usesTrash() ? R.string.trash_info_gallery : R.string.trash_info_no_trash);
        info.setText(l.isEmpty() ? base
                : l.size() + (l.size() == 1 ? " video · " : " videos · ") + Fmt.size(bytes) + "\n" + base);
    }

    List<Row> rows() {
        return rows;
    }

    @VisibleForTesting
    AlertDialog showActions(Row r) {
        return new MaterialAlertDialogBuilder(this)
                .setTitle(r.title())
                .setItems(new CharSequence[]{"Restore", "Delete forever"}, (d, which) -> {
                    List<Row> one = new ArrayList<>();
                    one.add(r);
                    if (which == 0) restore(one); else confirmDeleteForever(one, false);
                })
                .show();
    }

    // ------------------------------------------------------------------ actions

    void restore(List<Row> list) {
        if (list.isEmpty()) return;
        if (privateMode) {
            final Context app = getApplicationContext();
            io.execute(() -> {
                List<Vault.Entry> es = new ArrayList<>();
                for (Row r : list) es.add(r.vault);
                try {
                    Vault.restore(app, es);
                } catch (java.io.IOException ignored) {
                }
                handler.post(() -> {
                    toast(list.size() == 1 ? "Restored to Private videos" : "Restored " + list.size() + " videos");
                    reload();
                });
            });
        } else {
            request(list, DeleteVideosFlow.REQ_UNTRASH);
        }
    }

    @VisibleForTesting
    @Nullable
    AlertDialog confirmDeleteForever(List<Row> list, boolean all) {
        if (list.isEmpty()) return null;
        // Android asks itself (gallery, no media management): that prompt is the one confirmation.
        if (!privateMode && DeleteVideosFlow.systemWillAsk(this)) {
            request(list, DeleteVideosFlow.REQ_DELETE);
            return null;
        }
        String title = all ? "Empty trash?" : list.size() == 1 ? "Delete forever?" : "Delete " + list.size() + " videos forever?";
        String what = list.size() == 1 ? "\u201c" + list.get(0).title() + "\u201d" : list.size() + " videos";
        AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(what + " will be permanently deleted. This can't be undone.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(all ? "Empty trash" : "Delete forever", (dlg, w) -> deleteForever(list))
                .show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.danger));
        return d;
    }

    private void deleteForever(List<Row> list) {
        if (privateMode) {
            final Context app = getApplicationContext();
            io.execute(() -> {
                for (Row r : list) {
                    Vault.delete(app, r.vault);
                }
                handler.post(() -> {
                    toast(list.size() == 1 ? "Deleted forever" : "Deleted " + list.size() + " videos forever");
                    reload();
                });
            });
        } else {
            request(list, DeleteVideosFlow.REQ_DELETE);
        }
    }

    private void request(List<Row> list, int kind) {
        List<Uri> uris = new ArrayList<>();
        for (Row r : list) uris.add(DeleteVideosFlow.mediaStoreUri(r.gallery.video));
        try {
            PendingIntent pi = DeleteVideosFlow.requestMaker.make(getContentResolver(), uris, kind);
            pendingRequest = list;
            pendingKind = kind;
            requestLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
        } catch (RuntimeException e) {
            toast("Couldn't do that: " + e.getMessage());
        }
    }

    @VisibleForTesting
    void onRequestResult(boolean ok) {
        List<Row> list = pendingRequest;
        pendingRequest = null;
        if (!ok || list == null) return;
        final Context app = getApplicationContext();
        if (pendingKind == DeleteVideosFlow.REQ_DELETE) {
            for (Row r : list) Prefs.clearResume(app, r.gallery.video.resumeKey());
            toast(list.size() == 1 ? "Deleted forever" : "Deleted " + list.size() + " videos forever");
        } else {
            toast(list.size() == 1 ? "Restored 1 video" : "Restored " + list.size() + " videos");
        }
        List<Row> left = new ArrayList<>(rows);
        left.removeAll(list);
        bind(left);
        reload();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ list

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_list, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row r = rows.get(position);
            h.title.setText(r.title());
            String left = GalleryTrash.timeLeft(r.expiresMs(), System.currentTimeMillis());
            String size = Fmt.size(r.size());
            h.meta.setText(size.isEmpty() ? left : left + " · " + size);
            long dur = r.gallery != null ? r.gallery.video.durationMs : r.vault.durationMs;
            if (h.duration != null) {
                h.duration.setText(Fmt.time(dur));
                h.duration.setVisibility(dur > 0 ? View.VISIBLE : View.GONE);
            }
            h.itemView.setOnClickListener(v -> showActions(r));
            h.boundKey = r.key();
            h.thumb.setImageDrawable(null);
            h.thumb.setAlpha(0.6f);
            if (r.gallery != null) {
                ThumbnailLoader.load(h.thumb, r.gallery.video);
            } else {
                final Context app = getApplicationContext();
                io.execute(() -> {
                    Bitmap b = Vault.loadThumb(app, r.vault);
                    if (b == null) return;
                    handler.post(() -> {
                        if (r.key().equals(h.boundKey) && VaultLock.isUnlocked()) h.thumb.setImageBitmap(b);
                    });
                });
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title, meta, duration;
        String boundKey;

        Holder(View v) {
            super(v);
            View frame = v.findViewById(R.id.thumb_frame);
            if (frame != null) frame.setClipToOutline(true);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            duration = v.findViewById(R.id.duration);
            View check = v.findViewById(R.id.select_check);
            if (check != null) check.setVisibility(View.GONE);
            View ov = v.findViewById(R.id.selection_overlay);
            if (ov != null) ov.setVisibility(View.GONE);
            View rp = v.findViewById(R.id.resume_progress);
            if (rp != null) rp.setVisibility(View.GONE);
            View badge = v.findViewById(R.id.folder_badge);
            if (badge != null) badge.setVisibility(View.GONE);
        }
    }

    static Intent intent(Context ctx, boolean isPrivate) {
        return new Intent(ctx, TrashActivity.class).putExtra(EXTRA_PRIVATE, isPrivate);
    }
}
