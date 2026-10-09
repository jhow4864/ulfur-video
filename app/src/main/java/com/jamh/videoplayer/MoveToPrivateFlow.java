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
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Library "Move to Private" for one or many videos: copy each into the vault, verify, then delete the
 * originals through MediaStore. Android 11+ shows ONE system delete confirmation for the whole batch
 * (createDeleteRequest), Android 10 asks per video via RecoverableSecurityException (platform limit), older
 * versions delete directly (WRITE_EXTERNAL_STORAGE). If the user declines a deletion, that video's private
 * copy is removed again so nothing is duplicated.
 *
 * <p>1.4.0: the uninstall warning is shown once per action (not per video) and has a "Don't ask me again"
 * box, stored in {@link Prefs#warnBeforePrivate}. The library overflow menu can turn it back on.
 */
final class MoveToPrivateFlow {

    private final AppCompatActivity activity;
    private final Runnable onLibraryChanged;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher;
    private final ActivityResultLauncher<String> writePermission;

    // In-flight batch
    private boolean busy;
    private final List<VideoItem> moved = new ArrayList<>();      // copied into the vault, original not yet deleted
    private final List<Vault.Entry> copies = new ArrayList<>();   // parallel to `moved`
    private int api29Index;                                       // API 29: next original to delete
    private int doneCount, failedCount, keptCount;
    private List<VideoItem> pendingPermission;
    @Nullable private Runnable pendingOnStarted;

    /** Construct from a field initializer or onCreate (registers activity-result launchers). */
    MoveToPrivateFlow(AppCompatActivity activity, Runnable onLibraryChanged) {
        this.activity = activity;
        this.onLibraryChanged = onLibraryChanged;
        deleteLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), r -> {
                    boolean ok = r.getResultCode() == Activity.RESULT_OK;
                    if (Build.VERSION.SDK_INT >= 30) {
                        // One request covered the whole batch: the system deleted all of them or none.
                        if (ok) {
                            for (VideoItem v : moved) afterMoved(v);
                            doneCount += moved.size();
                            moved.clear();
                            copies.clear();
                        } else {
                            keptCount += moved.size();
                            rollBackAll();
                        }
                        finishBatch();
                    } else if (ok) {
                        deleteNextApi29();          // permission granted for this item: retry it
                    } else {
                        rollBackOne(api29Index);   // keep this original, drop its private copy, go on
                        keptCount++;
                        api29Index++;
                        deleteNextApi29();
                    }
                });
        writePermission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            List<VideoItem> v = pendingPermission;
            Runnable started = pendingOnStarted;
            pendingPermission = null;
            pendingOnStarted = null;
            if (v == null) return;
            if (granted) begin(v, started);
            else toast("Storage permission is needed to remove the originals after copying.");
        });
    }

    boolean isBusy() {
        return busy;
    }

    void start(VideoItem v) {
        start(Collections.singletonList(v), null);
    }

    /**
     * Moves every video in {@code list} (ids &lt; 0 are skipped). {@code onStarted} runs once the user has
     * confirmed (or straight away when the warning is turned off), e.g. to leave selection mode.
     */
    void start(List<VideoItem> list, @Nullable Runnable onStarted) {
        List<VideoItem> vids = new ArrayList<>();
        for (VideoItem v : list) if (v != null && v.id >= 0) vids.add(v);
        if (vids.isEmpty() || busy) return;
        if (!VaultAuth.isAvailable(activity)) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.vault_no_lock_title)
                    .setMessage(R.string.vault_no_lock_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (Prefs.warnBeforePrivate(activity)) {
            showConfirm(vids, onStarted);
        } else {
            proceed(vids, onStarted);
        }
    }

    /** Single-video confirmation (kept for the preview tests). */
    AlertDialog showConfirm(VideoItem v) {
        return showConfirm(Collections.singletonList(v), null);
    }

    /** The confirmation with the uninstall warning, shown ONCE for the whole batch, with "Don't ask me again". */
    AlertDialog showConfirm(List<VideoItem> vids, @Nullable Runnable onStarted) {
        String what = vids.size() == 1 ? "\u201c" + vids.get(0).title + "\u201d will"
                : vids.size() + " videos will";
        View box = LayoutInflater.from(activity).inflate(R.layout.dialog_dont_ask, null);
        CheckBox dontAsk = box.findViewById(R.id.dont_ask_again);
        return new MaterialAlertDialogBuilder(activity)
                .setTitle(vids.size() == 1 ? "Move to Private?" : "Move " + vids.size() + " videos to Private?")
                .setMessage(what + " be copied into " + activity.getString(R.string.app_name)
                        + "'s private storage, checked, and then removed from your gallery and other apps. "
                        + "Only you can open them, with your fingerprint, face or screen lock.\n\n"
                        + activity.getString(R.string.vault_warning))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.move_to_private, (d, w) -> {
                    if (dontAsk.isChecked()) {
                        Prefs.setWarnBeforePrivate(activity, false);
                        toast("Got it. You can turn the warning back on from the \u22ee menu.");
                    }
                    proceed(vids, onStarted);
                })
                .show();
    }

    private void proceed(List<VideoItem> vids, @Nullable Runnable onStarted) {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = vids;
            pendingOnStarted = onStarted;
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            begin(vids, onStarted);
        }
    }

    private void begin(List<VideoItem> vids, @Nullable Runnable onStarted) {
        if (onStarted != null) onStarted.run();
        copyIntoVault(new ArrayList<>(vids));
    }

    private void copyIntoVault(List<VideoItem> vids) {
        busy = true;
        moved.clear();
        copies.clear();
        doneCount = failedCount = keptCount = 0;
        final int n = vids.size();
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_export_progress, null);
        LinearProgressIndicator bar = view.findViewById(R.id.export_progress);
        TextView status = view.findViewById(R.id.export_status);
        status.setText("Copying…");
        final boolean[] cancelled = {false};
        AlertDialog dlg = new MaterialAlertDialogBuilder(activity)
                .setTitle(n == 1 ? activity.getString(R.string.move_to_private) : "Moving " + n + " videos to Private")
                .setView(view)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled[0] = true)
                .show();
        long total = 0;
        for (VideoItem v : vids) total += Math.max(0, v.sizeBytes);
        final long totalBytes = total;
        final Context app = activity.getApplicationContext();
        io.execute(() -> {
            List<VideoItem> okItems = new ArrayList<>();
            List<Vault.Entry> okCopies = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            long before = 0;
            for (int i = 0; i < n && !cancelled[0]; i++) {
                final VideoItem v = vids.get(i);
                final int index = i;
                final long base = before;
                final Uri src = mediaStoreUri(v);
                try {
                    String mime = app.getContentResolver().getType(src);
                    Vault.Entry e = Vault.importFrom(app, src, v.title, mime, v.sizeBytes, new Vault.Progress() {
                        @Override public void onProgress(long done, long size) {
                            main.post(() -> {
                                String prefix = n == 1 ? "Copying… " : "Copying " + (index + 1) + " of " + n + "… ";
                                if (totalBytes > 0) {
                                    bar.setIndeterminate(false);
                                    bar.setProgressCompat((int) (100 * Math.min(totalBytes, base + done) / totalBytes), true);
                                    status.setText(prefix + Fmt.size(base + done) + " of " + Fmt.size(totalBytes));
                                } else {
                                    status.setText(prefix);
                                }
                            });
                        }

                        @Override public boolean isCancelled() {
                            return cancelled[0];
                        }
                    });
                    okItems.add(v);
                    okCopies.add(e);
                } catch (Exception ex) {
                    if (!cancelled[0]) errors.add(ex.getMessage() == null ? "Couldn't copy \u201c" + v.title + "\u201d" : ex.getMessage());
                }
                before += Math.max(0, v.sizeBytes);
            }
            final boolean wasCancelled = cancelled[0];
            main.post(() -> {
                if (dlg.isShowing()) {
                    try { dlg.dismiss(); } catch (RuntimeException ignored) { }
                }
                moved.addAll(okItems);
                copies.addAll(okCopies);
                failedCount = errors.size();
                if (wasCancelled) {
                    // Nothing has been deleted yet: drop every private copy made so far.
                    keptCount += moved.size();
                    rollBackAll();
                    busy = false;
                    toast("Cancelled. Nothing was moved.");
                    return;
                }
                if (moved.isEmpty()) {
                    busy = false;
                    toast((errors.isEmpty() ? "Couldn't copy the videos" : errors.get(0)) + ". Nothing was changed.");
                    return;
                }
                deleteOriginals();
            });
        });
    }

    /** The private copies are verified; now remove the originals from shared storage. */
    private void deleteOriginals() {
        if (Build.VERSION.SDK_INT >= 30) {
            List<Uri> uris = new ArrayList<>();
            for (VideoItem v : moved) uris.add(mediaStoreUri(v));
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(activity.getContentResolver(), uris);
                deleteLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
            } catch (RuntimeException e) {
                keptCount += moved.size();
                rollBackAll();
                toast("Couldn't remove the originals: " + e.getMessage());
                busy = false;
            }
            return;
        }
        api29Index = 0;
        deleteNextApi29();
    }

    /** Android 10 and older: delete one by one (Android 10 may ask per video). */
    private void deleteNextApi29() {
        if (api29Index >= moved.size()) {
            moved.clear();
            copies.clear();
            finishBatch();
            return;
        }
        final VideoItem item = moved.get(api29Index);
        final Uri uri = mediaStoreUri(item);
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
                    err = activity.getString(R.string.app_name) + " isn't allowed to delete the original";
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
                    return;
                }
                if (ok) {
                    afterMoved(item);
                    doneCount++;
                } else {
                    rollBackOne(api29Index);
                    failedCount++;
                    if (error != null && moved.size() == 1) toast("Couldn't remove the original: " + error);
                }
                api29Index++;
                deleteNextApi29();
            });
        });
    }

    private void afterMoved(VideoItem v) {
        Prefs.clearResume(activity, v.resumeKey());
        final Context app = activity.getApplicationContext();
        io.execute(() -> Shortcuts.removeContinueWatchingFor(app, v.id, v.uri));
    }

    private void finishBatch() {
        busy = false;
        StringBuilder sb = new StringBuilder();
        if (doneCount > 0) sb.append(doneCount == 1 ? "Moved to Private" : "Moved " + doneCount + " videos to Private");
        if (keptCount > 0) {
            if (sb.length() > 0) sb.append(". ");
            sb.append(keptCount == 1 ? "1 video stays in your library (the original wasn't deleted)"
                    : keptCount + " videos stay in your library (the originals weren't deleted)");
        }
        if (failedCount > 0) {
            if (sb.length() > 0) sb.append(". ");
            sb.append(failedCount == 1 ? "1 video couldn't be moved" : failedCount + " videos couldn't be moved");
        }
        if (sb.length() > 0) toast(sb.toString());
        onLibraryChanged.run();
    }

    private void rollBackOne(int index) {
        if (index < 0 || index >= copies.size()) return;
        final Vault.Entry e = copies.get(index);
        copies.set(index, null);
        if (e != null) {
            final Context app = activity.getApplicationContext();
            io.execute(() -> Vault.delete(app, e));
        }
    }

    private void rollBackAll() {
        final List<Vault.Entry> drop = new ArrayList<>(copies);
        moved.clear();
        copies.clear();
        final Context app = activity.getApplicationContext();
        io.execute(() -> {
            for (Vault.Entry e : drop) if (e != null) Vault.delete(app, e);
        });
    }

    private static Uri mediaStoreUri(VideoItem v) {
        return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id);
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }
}
