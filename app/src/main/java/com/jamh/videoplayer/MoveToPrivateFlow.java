package com.jamh.videoplayer;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Library "Move to Private": copy into the vault, verify, then delete the original through
 * MediaStore. Android 11+ shows the system delete confirmation (createDeleteRequest), Android 10
 * asks via RecoverableSecurityException, older versions delete directly (WRITE_EXTERNAL_STORAGE).
 * If the user declines the deletion, the private copy is removed again so nothing is duplicated.
 */
final class MoveToPrivateFlow {

    private final AppCompatActivity activity;
    private final Runnable onLibraryChanged;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher;
    private final ActivityResultLauncher<String> writePermission;

    // In-flight move
    private VideoItem item;
    private Vault.Entry copy;
    private VideoItem pendingPermissionItem;

    /** Construct from a field initializer or onCreate (registers activity-result launchers). */
    MoveToPrivateFlow(AppCompatActivity activity, Runnable onLibraryChanged) {
        this.activity = activity;
        this.onLibraryChanged = onLibraryChanged;
        deleteLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), r -> {
                    if (r.getResultCode() == Activity.RESULT_OK) {
                        if (Build.VERSION.SDK_INT >= 30) finishMove();   // system already deleted it
                        else deleteOriginal();                           // API 29: retry now that we may
                    } else {
                        rollBack("Not moved: the original wasn't deleted, so it stays in your library.");
                    }
                });
        writePermission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            VideoItem v = pendingPermissionItem;
            pendingPermissionItem = null;
            if (v == null) return;
            if (granted) copyIntoVault(v);
            else toast("Storage permission is needed to remove the original after copying.");
        });
    }

    void start(VideoItem v) {
        if (v.id < 0 || copy != null) return;
        if (!VaultAuth.isAvailable(activity)) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.vault_no_lock_title)
                    .setMessage(R.string.vault_no_lock_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        showConfirm(v);
    }

    /** The confirmation (with the uninstall warning) shown before anything is copied. */
    AlertDialog showConfirm(VideoItem v) {
        return new MaterialAlertDialogBuilder(activity)
                .setTitle("Move to Private?")
                .setMessage("\u201c" + v.title + "\u201d will be copied into Video Player's private storage, "
                        + "checked, and then removed from your gallery and other apps. Only you can open it, "
                        + "with your fingerprint, face or screen lock.\n\n"
                        + activity.getString(R.string.vault_warning))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.move_to_private, (d, w) -> {
                    if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(activity,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        pendingPermissionItem = v;
                        writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    } else {
                        copyIntoVault(v);
                    }
                })
                .show();
    }

    private void copyIntoVault(VideoItem v) {
        item = v;
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_export_progress, null);
        LinearProgressIndicator bar = view.findViewById(R.id.export_progress);
        TextView status = view.findViewById(R.id.export_status);
        status.setText("Copying…");
        final boolean[] cancelled = {false};
        AlertDialog dlg = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.move_to_private)
                .setView(view)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled[0] = true)
                .show();
        final Context app = activity.getApplicationContext();
        final Uri src = mediaStoreUri(v);
        io.execute(() -> {
            Vault.Entry e = null;
            String err = null;
            try {
                String mime = app.getContentResolver().getType(src);
                e = Vault.importFrom(app, src, v.title, mime, v.sizeBytes, new Vault.Progress() {
                    @Override public void onProgress(long done, long total) {
                        main.post(() -> {
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setProgressCompat((int) (100 * done / total), true);
                                status.setText("Copying… " + Fmt.size(done) + " of " + Fmt.size(total));
                            }
                        });
                    }

                    @Override public boolean isCancelled() {
                        return cancelled[0];
                    }
                });
            } catch (Exception ex) {
                err = cancelled[0] ? null : (ex.getMessage() == null ? "Couldn't copy the video" : ex.getMessage());
            }
            final Vault.Entry result = e;
            final String error = err;
            main.post(() -> {
                if (dlg.isShowing()) {
                    try { dlg.dismiss(); } catch (RuntimeException ignored) { }
                }
                if (result == null) {
                    item = null;
                    if (error != null) toast(error + ". Nothing was changed.");
                    return;
                }
                copy = result;
                deleteOriginal();
            });
        });
    }

    /** The private copy is verified; now remove the original from shared storage. */
    private void deleteOriginal() {
        final Uri uri = mediaStoreUri(item);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(activity.getContentResolver(),
                        Collections.singletonList(uri));
                deleteLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
            } catch (RuntimeException e) {
                rollBack("Couldn't remove the original: " + e.getMessage());
            }
            return;
        }
        final Context app = activity.getApplicationContext();
        io.execute(() -> {
            IntentSender ask = null;
            String err = null;
            boolean deleted = false;
            try {
                deleted = app.getContentResolver().delete(uri, null, null) > 0;
            } catch (SecurityException se) {
                if (Build.VERSION.SDK_INT == 29 && se instanceof RecoverableSecurityException) {
                    ask = ((RecoverableSecurityException) se).getUserAction().getActionIntent().getIntentSender();
                } else {
                    err = "Video Player isn't allowed to delete the original";
                }
            } catch (RuntimeException re) {
                err = re.getMessage();
            }
            final IntentSender sender = ask;
            final boolean ok = deleted;
            final String error = err;
            main.post(() -> {
                if (sender != null) {
                    deleteLauncher.launch(new IntentSenderRequest.Builder(sender).build());
                } else if (ok) {
                    finishMove();
                } else {
                    rollBack("Couldn't remove the original" + (error == null ? "" : ": " + error) + ". Nothing was changed.");
                }
            });
        });
    }

    private void finishMove() {
        VideoItem v = item;
        item = null;
        copy = null;
        if (v != null) {
            Prefs.clearResume(activity, v.resumeKey());
            final Context app = activity.getApplicationContext();
            io.execute(() -> Shortcuts.removeContinueWatchingFor(app, v.id, v.uri));
        }
        toast("Moved to Private");
        onLibraryChanged.run();
    }

    private void rollBack(String message) {
        final Vault.Entry e = copy;
        copy = null;
        item = null;
        if (e != null) {
            final Context app = activity.getApplicationContext();
            io.execute(() -> Vault.delete(app, e));
        }
        toast(message);
    }

    private static Uri mediaStoreUri(VideoItem v) {
        return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id);
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }
}
