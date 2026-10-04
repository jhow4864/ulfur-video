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
import android.widget.PopupMenu;
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
import java.util.List;
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
    private Vault.Entry pendingMoveOut;
    private ActivityResultLauncher<String> writePermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        setContentView(R.layout.activity_vault);
        com.google.android.material.appbar.MaterialToolbar tb = findViewById(R.id.vault_toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        tb.inflateMenu(R.menu.vault);
        tb.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_lock_now) {
                VaultLock.lock();
                finish();
                return true;
            }
            return false;
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
            Vault.Entry e = pendingMoveOut;
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
        adapter.notifyDataSetChanged();
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

    private void showOptions(Vault.Entry e, View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, R.string.move_out_of_private);
        pm.getMenu().add(0, 2, 1, R.string.delete_permanently);
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingMoveOut = e;
                    writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                } else {
                    moveOut(e);
                }
            } else {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Delete permanently?")
                        .setMessage("\u201c" + e.name + "\u201d will be deleted from this phone. This can't be undone.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Delete", (d, w) -> {
                            final android.content.Context app = getApplicationContext();
                            io.execute(() -> {
                                Vault.delete(app, e);
                                handler.post(this::reload);
                            });
                        })
                        .show();
            }
            return true;
        });
        pm.show();
    }

    private void moveOut(Vault.Entry e) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_export_progress, null);
        ((TextView) v.findViewById(R.id.export_status)).setText("Copying to Movies/" + MediaSaver.FOLDER + "…");
        AlertDialog dlg = new MaterialAlertDialogBuilder(this).setTitle("Moving out of Private")
                .setView(v).setCancelable(false).show();
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            String err = null;
            try {
                Vault.moveOut(app, e);
            } catch (Exception ex) {
                err = ex.getMessage() == null ? "Couldn't move the video" : ex.getMessage();
            }
            final String error = err;
            handler.post(() -> {
                if (isDestroyed()) return;
                dlg.dismiss();
                Toast.makeText(this, error == null ? "Moved to Movies/" + MediaSaver.FOLDER : error,
                        Toast.LENGTH_LONG).show();
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
            h.itemView.setOnClickListener(x -> play(e));
            h.itemView.setOnLongClickListener(x -> {
                showOptions(e, x);
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
        final ImageView thumb;
        final TextView title, meta, duration;
        String boundId;

        Holder(View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            duration = v.findViewById(R.id.duration);
            if (thumb instanceof RatioImageView) ((RatioImageView) thumb).setRatio(9f / 16f);
        }
    }
}
