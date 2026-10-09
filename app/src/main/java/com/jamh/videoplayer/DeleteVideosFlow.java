package com.jamh.videoplayer;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.5.1: delete one or many gallery videos (Videos, folders, Clips) from selection mode.
 *
 * <p>Exactly one confirmation per batch:
 * <ul>
 *   <li>Media management allowed (Android 12+, 1.4.1's MANAGE_MEDIA) or Android 10 and older: our own
 *       "Delete N videos?" dialog, then a direct MediaStore delete with no system prompt (anything MediaStore still
 *       refuses goes through createDeleteRequest, which the system auto-approves with media management).</li>
 *   <li>Android 11+ without media management: Android's own "Allow Ulfur Video to delete N videos?" is the one
 *       confirmation (we don't add a second dialog before it). One request covers the whole batch.</li>
 *   <li>Android 10: our dialog, then the platform may ask per video (RecoverableSecurityException).</li>
 * </ul>
 * The callback gets the videos that were really deleted, so the library can update immediately.
 */
final class DeleteVideosFlow {

    interface Callback {
        /** @param deleted videos removed from the phone (empty if the user cancelled / nothing was deleted). */
        void onDeleteFinished(List<VideoItem> deleted);
    }

    private final AppCompatActivity activity;
    private final Callback callback;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher;
    private final ActivityResultLauncher<String> writePermission;

    private boolean busy;
    private final List<VideoItem> pending = new ArrayList<>();   // waiting for the system request
    private final List<VideoItem> deleted = new ArrayList<>();
    private int api29Index;
    private int failedCount;
    @Nullable private List<VideoItem> pendingPermission;

    /** Construct from a field initializer or onCreate (registers activity-result launchers). */
    DeleteVideosFlow(AppCompatActivity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        deleteLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), r -> onSystemResult(r.getResultCode() == Activity.RESULT_OK));
        writePermission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            List<VideoItem> v = pendingPermission;
            pendingPermission = null;
            if (v == null) return;
            if (granted) begin(v);
            else toast("Storage permission is needed to delete videos.");
        });
    }

    boolean isBusy() {
        return busy;
    }

    /** Android shows its own confirmation for this batch (Android 11+ without media management). */
    static boolean systemWillAsk(Context ctx) {
        return Build.VERSION.SDK_INT >= 30 && !MediaAccess.canManageMedia(ctx);
    }

    /** Deletes every video in {@code list} (ids &lt; 0 skipped) after one confirmation. */
    void start(List<VideoItem> list) {
        List<VideoItem> vids = new ArrayList<>();
        for (VideoItem v : list) if (v != null && v.id >= 0) vids.add(v);
        if (vids.isEmpty()) {
            toast("Select some videos first");
            return;
        }
        if (busy) return;
        if (systemWillAsk(activity)) proceed(vids);   // Android's dialog is the one confirmation
        else showConfirm(vids);
    }

    static String confirmTitle(int n) {
        return n == 1 ? "Delete video?" : "Delete " + n + " videos?";
    }

    static String confirmMessage(List<VideoItem> vids) {
        long bytes = 0;
        for (VideoItem v : vids) bytes += Math.max(0, v.sizeBytes);
        String what = vids.size() == 1 ? "\u201c" + vids.get(0).title + "\u201d" : vids.size() + " videos";
        String size = bytes > 0 ? " (" + Fmt.size(bytes) + ")" : "";
        return what + size + " will be"
                + " permanently deleted from this phone, including your gallery and other apps. This can't be undone.";
    }

    /** Our confirmation, ONCE for the whole batch. */
    @VisibleForTesting
    AlertDialog showConfirm(List<VideoItem> vids) {
        AlertDialog d = new MaterialAlertDialogBuilder(activity)
                .setTitle(confirmTitle(vids.size()))
                .setMessage(confirmMessage(vids))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dlg, w) -> proceed(vids))
                .show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(activity, R.color.danger));
        return d;
    }

    private void proceed(List<VideoItem> vids) {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = vids;
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            begin(vids);
        }
    }

    private void begin(List<VideoItem> vids) {
        busy = true;
        pending.clear();
        deleted.clear();
        failedCount = 0;
        if (MediaAccess.canManageMedia(activity)) {
            deleteDirectlyThenRequest(vids);
        } else if (Build.VERSION.SDK_INT >= 30) {
            pending.addAll(vids);
            requestDelete(vids);
        } else {
            pending.addAll(vids);
            api29Index = 0;
            deleteNextApi29();
        }
    }

    /** Media management granted: delete directly (no dialog); leftovers go through the auto-approved request. */
    private void deleteDirectlyThenRequest(List<VideoItem> batch) {
        final Context app = activity.getApplicationContext();
        io.execute(() -> {
            List<VideoItem> ok = new ArrayList<>();
            List<VideoItem> refused = new ArrayList<>();
            for (VideoItem v : batch) {
                boolean done = false;
                try {
                    done = app.getContentResolver().delete(mediaStoreUri(v), null, null) > 0;
                } catch (RuntimeException ignored) {
                    // RecoverableSecurityException etc.: fall back to the request below
                }
                (done ? ok : refused).add(v);
            }
            main.post(() -> {
                deleted.addAll(ok);
                if (refused.isEmpty() || Build.VERSION.SDK_INT < 30) {
                    failedCount += refused.size();
                    finish();
                } else {
                    pending.addAll(refused);
                    requestDelete(refused);
                }
            });
        });
    }

    /** Android 11+: one system delete request for the whole batch. */
    private void requestDelete(List<VideoItem> batch) {
        if (Build.VERSION.SDK_INT < 30) return;
        List<Uri> uris = new ArrayList<>();
        for (VideoItem v : batch) uris.add(mediaStoreUri(v));
        try {
            PendingIntent pi = MediaStore.createDeleteRequest(activity.getContentResolver(), uris);
            deleteLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
        } catch (RuntimeException e) {
            toast("Couldn't delete: " + e.getMessage());
            failedCount += pending.size();
            pending.clear();
            finish();
        }
    }

    @VisibleForTesting
    void onSystemResult(boolean ok) {
        if (Build.VERSION.SDK_INT >= 30) {
            // One request covered the batch: the system deleted all of them or none.
            if (ok) deleted.addAll(pending);
            pending.clear();
            finish();
        } else if (ok) {
            deleteNextApi29();          // permission granted for this item: retry it
        } else {
            api29Index++;               // keep this one, go on
            deleteNextApi29();
        }
    }

    /** Android 10 and older: one by one (Android 10 may ask per video, a platform limit). */
    private void deleteNextApi29() {
        if (api29Index >= pending.size()) {
            pending.clear();
            finish();
            return;
        }
        final VideoItem item = pending.get(api29Index);
        final Context app = activity.getApplicationContext();
        io.execute(() -> {
            IntentSender ask = null;
            boolean ok = false;
            try {
                ok = app.getContentResolver().delete(mediaStoreUri(item), null, null) > 0;
            } catch (SecurityException se) {
                if (Build.VERSION.SDK_INT == 29 && se instanceof RecoverableSecurityException) {
                    ask = ((RecoverableSecurityException) se).getUserAction().getActionIntent().getIntentSender();
                }
            } catch (RuntimeException ignored) {
            }
            final IntentSender sender = ask;
            final boolean done = ok;
            main.post(() -> {
                if (sender != null) {
                    deleteLauncher.launch(new IntentSenderRequest.Builder(sender).build());
                    return;
                }
                if (done) deleted.add(item); else failedCount++;
                api29Index++;
                deleteNextApi29();
            });
        });
    }

    private void finish() {
        busy = false;
        final List<VideoItem> result = Collections.unmodifiableList(new ArrayList<>(deleted));
        deleted.clear();
        final Context app = activity.getApplicationContext();
        for (VideoItem v : result) Prefs.clearResume(app, v.resumeKey());
        if (!result.isEmpty()) {
            io.execute(() -> {
                for (VideoItem v : result) Shortcuts.removeContinueWatchingFor(app, v.id, v.uri);
            });
        }
        StringBuilder sb = new StringBuilder();
        if (!result.isEmpty()) sb.append(result.size() == 1 ? "Deleted 1 video" : "Deleted " + result.size() + " videos");
        if (failedCount > 0) {
            if (sb.length() > 0) sb.append(". ");
            sb.append(failedCount == 1 ? "1 video couldn't be deleted" : failedCount + " videos couldn't be deleted");
        }
        if (sb.length() == 0) sb.append("Nothing was deleted");
        toast(sb.toString());
        callback.onDeleteFinished(result);
    }

    static Uri mediaStoreUri(VideoItem v) {
        return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id);
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }
}
