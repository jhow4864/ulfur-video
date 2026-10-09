package com.jamh.videoplayer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Private videos screen. Shown only after BiometricPrompt (fingerprint/face or screen lock),
 * FLAG_SECURE (no screenshots, blank in Recents), re-locks when the app goes to the background.
 */
public class VaultActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Vault.Entry> entries = new ArrayList<>();
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private View lockedPanel, content, emptyView;
    private TextView lockedTitle, lockedBody, storageText;
    private LinearProgressIndicator storageBar;
    private MaterialButton unlockButton;
    private RecyclerView list;
    private Adapter adapter;
    private BiometricPrompt prompt;
    private boolean authInProgress;
    private boolean noScreenLock;
    private List<Vault.Entry> pendingMoveOut;
    // 1.4.0 multi-select
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting;
    private com.google.android.material.appbar.MaterialToolbar toolbar;
    private View selectionBar;
    private TextView selectAllButton, moveOutButton;
    private ActivityResultLauncher<String> writePermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        setContentView(R.layout.activity_vault);
        com.google.android.material.appbar.MaterialToolbar tb = findViewById(R.id.vault_toolbar);
        toolbar = tb;
        tb.setNavigationOnClickListener(v -> {
            if (selecting) setSelecting(false); else finish();
        });
        tb.inflateMenu(R.menu.vault);
        tb.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_lock_now) {
                VaultLock.lock();
                finish();
                return true;
            } else if (id == R.id.action_vault_select_all) {
                toggleSelectAll();
                return true;
            } else if (id == R.id.action_vault_move_out) {
                requestMoveOut(selectedEntries());
                return true;
            } else if (id == R.id.action_vault_delete) {
                confirmDelete(selectedEntries());
                return true;
            }
            return false;
        });
        selectionBar = findViewById(R.id.vault_selection_bar);
        selectAllButton = findViewById(R.id.vault_select_all);
        moveOutButton = findViewById(R.id.vault_move_out_selected);
        selectAllButton.setOnClickListener(v -> toggleSelectAll());
        moveOutButton.setOnClickListener(v -> requestMoveOut(selectedEntries()));
        findViewById(R.id.vault_delete_selected).setOnClickListener(v -> confirmDelete(selectedEntries()));
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (selecting) setSelecting(false); else finish();
            }
        });
        lockedPanel = findViewById(R.id.vault_locked);
        content = findViewById(R.id.vault_content);
        emptyView = findViewById(R.id.vault_empty);
        lockedTitle = findViewById(R.id.vault_locked_title);
        lockedBody = findViewById(R.id.vault_locked_body);
        storageText = findViewById(R.id.vault_storage);
        storageBar = findViewById(R.id.vault_storage_bar);
        unlockButton = findViewById(R.id.vault_unlock);
        list = findViewById(R.id.vault_list);
        int span = getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 2;
        list.setLayoutManager(new GridLayoutManager(this, span));
        adapter = new Adapter();
        list.setAdapter(adapter);
        unlockButton.setOnClickListener(v -> {
            if (noScreenLock) {
                try {
                    startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                } catch (RuntimeException e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            } else {
                requestUnlock();
            }
        });
        prompt = VaultAuth.create(this, new VaultAuth.Callback() {
            @Override public void onUnlocked() {
                authInProgress = false;
                showContent();
            }

            @Override public void onFailed(boolean noLock, CharSequence message) {
                authInProgress = false;
                showLocked(noLock);
            }
        });
        writePermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            List<Vault.Entry> e = pendingMoveOut;
            pendingMoveOut = null;
            if (granted && e != null) moveOut(e);
            else if (e != null) Toast.makeText(this, "Storage permission is needed to move videos out", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (VaultLock.isUnlocked()) {
            showContent();
        } else {
            showLocked(false);
            requestUnlock();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!VaultLock.isUnlocked()) hideContentNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private void requestUnlock() {
        if (authInProgress) return;
        if (!VaultAuth.isAvailable(this)) {
            showLocked(true);
            return;
        }
        authInProgress = true;
        handler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                VaultAuth.prompt(prompt);
            } catch (RuntimeException e) {
                authInProgress = false;
                showLocked(false);
            }
        });
    }

    @VisibleForTesting
    void showLocked(boolean noLock) {
        noScreenLock = noLock;
        hideContentNow();
        lockedPanel.setVisibility(View.VISIBLE);
        lockedTitle.setText(noLock ? R.string.vault_no_lock_title : R.string.vault_locked_title);
        lockedBody.setText(noLock ? R.string.vault_no_lock_body : R.string.vault_locked_body);
        unlockButton.setText(noLock ? R.string.vault_open_settings : R.string.vault_unlock);
    }

    private void hideContentNow() {
        if (selecting) setSelecting(false);
        content.setVisibility(View.GONE);
        entries.clear();
        adapter.notifyDataSetChanged();
        thumbs.evictAll();
    }

    @VisibleForTesting
    void showContent() {
        lockedPanel.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
        reload();
    }

    private void reload() {
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            List<Vault.Entry> l = Vault.list(app);
            long used = Vault.usedBytes(app);
            long free = Vault.dir(app).getUsableSpace();
            handler.post(() -> {
                if (isDestroyed() || !VaultLock.isUnlocked()) return;
                bind(l, used, free);
            });
        });
    }

    @VisibleForTesting
    void bind(List<Vault.Entry> l, long used, long free) {
        entries.clear();
        entries.addAll(l);
        Set<String> present = new java.util.HashSet<>();
        for (Vault.Entry e : l) present.add(e.id);
        selected.retainAll(present);
        if (selecting && l.isEmpty()) setSelecting(false);
        adapter.notifyDataSetChanged();
        updateSelectionUi();
        emptyView.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
        storageText.setText(storageLabel(l.size(), used, free));
        long total = used + free;
        storageBar.setProgress(total <= 0 ? 0 : (int) Math.max(used > 0 ? 8 : 0, 1000 * used / total));
    }

    static String storageLabel(int count, long used, long free) {
        return count + (count == 1 ? " video" : " videos") + " · " + Fmt.size(used) + " used · "
                + Fmt.size(free) + " free on this phone";
    }

    private void play(Vault.Entry e) {
        Uri uri = Vault.uriFor(this, e);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime).setClass(this, PlayerActivity.class);
        startActivity(i);
    }

    // ------------------------------------------------------------------ multi-select (1.4.0)

    @VisibleForTesting
    void setSelecting(boolean on) {
        if (selecting == on) return;
        selecting = on;
        if (!on) selected.clear();
        toolbar.getMenu().clear();
        toolbar.inflateMenu(on ? R.menu.vault_selection : R.menu.vault);
        toolbar.setNavigationIcon(on ? R.drawable.ic_close : R.drawable.ic_arrow_back);
        if (toolbar.getNavigationIcon() != null) toolbar.getNavigationIcon().setTint(0xFFF2F2F7);
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    @VisibleForTesting
    void toggle(Vault.Entry e) {
        if (!selected.remove(e.id)) selected.add(e.id);
        int pos = entries.indexOf(e);
        if (pos >= 0) adapter.notifyItemChanged(pos); else adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void toggleSelectAll() {
        if (!selecting) setSelecting(true);
        if (!entries.isEmpty() && selected.size() >= entries.size()) {
            selected.clear();
        } else {
            for (Vault.Entry e : entries) selected.add(e.id);
        }
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        if (toolbar == null) return;
        int n = selected.size();
        toolbar.setTitle(selecting ? getString(R.string.selection_count, n) : getString(R.string.vault_title));
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        moveOutButton.setEnabled(n > 0);
        moveOutButton.setAlpha(n > 0 ? 1f : 0.45f);
        moveOutButton.setText(n > 1 ? "Move " + n + " out of Private" : getString(R.string.move_out_selected));
        selectAllButton.setText(!entries.isEmpty() && n >= entries.size() ? R.string.clear_selection : R.string.select_all);
        int pad = selecting ? (int) (96 * getResources().getDisplayMetrics().density) : 0;
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), pad + list.getPaddingLeft());
    }

    private List<Vault.Entry> selectedEntries() {
        List<Vault.Entry> out = new ArrayList<>();
        for (Vault.Entry e : entries) if (selected.contains(e.id)) out.add(e);
        return out;
    }

    private void requestMoveOut(List<Vault.Entry> list) {
        if (list.isEmpty()) {
            Toast.makeText(this, "Select some videos first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingMoveOut = list;
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            moveOut(list);
        }
    }

    /** Bulk "Delete permanently" (1.4.0). Package-private + returns the dialog for tests (1.5.1). */
    androidx.appcompat.app.AlertDialog confirmDelete(List<Vault.Entry> list) {
        if (list.isEmpty()) {
            Toast.makeText(this, "Select some videos first", Toast.LENGTH_SHORT).show();
            return null;
        }
        String what = list.size() == 1 ? "\u201c" + list.get(0).name + "\u201d will" : list.size() + " videos will";
        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(list.size() == 1 ? "Delete permanently?" : "Delete " + list.size() + " videos permanently?")
                .setMessage(what + " be deleted from this phone. This can't be undone.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (d, w) -> {
                    final android.content.Context app = getApplicationContext();
                    setSelecting(false);
                    io.execute(() -> {
                        for (Vault.Entry e : list) Vault.delete(app, e);
                        handler.post(this::reload);
                    });
                })
                .show();
        return dlg;
    }

    /** Moves one or many videos out to Movies/UlfurVideo, one after the other, with one progress dialog. */
    private void moveOut(List<Vault.Entry> list) {
        final int n = list.size();
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_export_progress, null);
        TextView status = v.findViewById(R.id.export_status);
        status.setText("Copying to Movies/" + MediaSaver.FOLDER + "…");
        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(n == 1 ? "Moving out of Private" : "Moving " + n + " videos out of Private")
                .setView(v).setCancelable(false).show();
        setSelecting(false);
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            int ok = 0;
            String err = null;
            for (int i = 0; i < n; i++) {
                final int k = i;
                if (n > 1) handler.post(() -> status.setText("Copying " + (k + 1) + " of " + n + " to Movies/" + MediaSaver.FOLDER + "…"));
                try {
                    Vault.moveOut(app, list.get(i));
                    ok++;
                } catch (Exception ex) {
                    if (err == null) err = ex.getMessage() == null ? "Couldn't move the video" : ex.getMessage();
                }
            }
            final int moved = ok;
            final String error = err;
            handler.post(() -> {
                if (isDestroyed()) return;
                try { dlg.dismiss(); } catch (RuntimeException ignored) { }
                String msg;
                if (error == null) msg = (n == 1 ? "Moved" : "Moved " + moved + " videos") + " to Movies/" + MediaSaver.FOLDER;
                else if (moved == 0) msg = error;
                else msg = "Moved " + moved + " of " + n + " to Movies/" + MediaSaver.FOLDER + ". " + error;
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                reload();
            });
        });
    }

    // ------------------------------------------------------------------ list

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_grid, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Vault.Entry e = entries.get(position);
            h.title.setText(e.name);
            h.meta.setText(Fmt.size(e.size) + (e.height > 0 ? " · " + Math.min(e.width, e.height) + "p" : ""));
            h.duration.setText(Fmt.time(e.durationMs));
            h.duration.setVisibility(e.durationMs > 0 ? View.VISIBLE : View.GONE);
            boolean sel = selected.contains(e.id);
            if (h.overlay != null) h.overlay.setVisibility(sel ? View.VISIBLE : View.GONE);
            if (h.check != null) {
                h.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
                h.check.setBackgroundResource(sel ? R.drawable.bg_check_on : R.drawable.bg_check_off);
                h.check.setImageResource(sel ? R.drawable.ic_check : 0);
            }
            h.itemView.setOnClickListener(x -> {
                if (selecting) toggle(e); else play(e);
            });
            h.itemView.setOnLongClickListener(x -> {
                if (!selecting) setSelecting(true);
                toggle(e);
                x.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                return true;
            });
            h.boundId = e.id;
            Bitmap cached = thumbs.get(e.id);
            h.thumb.setImageBitmap(cached);
            if (cached == null) {
                final File f = Vault.thumbOf(VaultActivity.this, e);
                io.execute(() -> {
                    Bitmap b = f.isFile() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
                    if (b == null) return;
                    handler.post(() -> {
                        if (!VaultLock.isUnlocked()) return;
                        thumbs.put(e.id, b);
                        if (e.id.equals(h.boundId)) h.thumb.setImageBitmap(b);
                    });
                });
            }
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb, check;
        final View overlay;
        final TextView title, meta, duration;
        String boundId;

        Holder(View v) {
            super(v);
            check = v.findViewById(R.id.select_check);
            overlay = v.findViewById(R.id.selection_overlay);
            View frame = v.findViewById(R.id.thumb_frame);
            if (frame != null) frame.setClipToOutline(true);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            duration = v.findViewById(R.id.duration);
            if (thumb instanceof RatioImageView) ((RatioImageView) thumb).setRatio(9f / 16f);
        }
    }
}
