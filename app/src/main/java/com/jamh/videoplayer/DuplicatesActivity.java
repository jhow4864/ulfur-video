package com.jamh.videoplayer;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.9.0: ⋮ → Find duplicates. Scans in the background ({@link DuplicateScan}), shows groups with the
 * suggested keeper, and moves the ticked videos to the Trash with one "Deleted N · Undo" (library:
 * {@link DeleteVideosFlow}, Private: the encrypted Private trash). Nothing is removed without the
 * user ticking it and pressing Move to Trash, and every group must keep at least one video.
 */
public class DuplicatesActivity extends AppCompatActivity implements DuplicateScan.Listener {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Object> rows = new ArrayList<>();     // Group (header) or Row (video)
    private final Set<String> ticked = new HashSet<>();
    @Nullable private DuplicateScan.Result result;

    private TextView info, empty, progressText, selection;
    private MaterialSwitch includePrivate;
    private MaterialButton scan;
    private View progressBox, bottom;
    private LinearProgressIndicator progress;
    private Adapter adapter;
    private DeleteVideosFlow deleteFlow;
    private BiometricPrompt prompt;
    private boolean suppressSwitch;

    // Pending delete (library first, then Private once the library part went through).
    private List<DuplicateFinder.Item> pendingVault = new ArrayList<>();
    private List<DuplicateFinder.Item> pendingLibrary = new ArrayList<>();
    @Nullable private DuplicateScan.Result beforeDelete;
    @Nullable private Snackbar undoBar;

    /** One video row. */
    static final class Row {
        final DuplicateFinder.Group group;
        final DuplicateFinder.Item item;

        Row(DuplicateFinder.Group g, DuplicateFinder.Item it) {
            group = g;
            item = it;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duplicates);
        MaterialToolbar tb = findViewById(R.id.dupes_toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        tb.getMenu().add(0, 1, 0, "Tick all suggestions");
        tb.getMenu().add(0, 2, 0, "Untick all");
        tb.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) applySuggestions(null);
            else clearTicks();
            return true;
        });
        info = findViewById(R.id.dupes_info);
        empty = findViewById(R.id.dupes_empty);
        progressText = findViewById(R.id.dupes_progress_text);
        selection = findViewById(R.id.dupes_selection);
        includePrivate = findViewById(R.id.dupes_include_private);
        scan = findViewById(R.id.dupes_scan);
        progressBox = findViewById(R.id.dupes_progress_box);
        progress = findViewById(R.id.dupes_progress);
        bottom = findViewById(R.id.dupes_bottom);
        RecyclerView list = findViewById(R.id.dupes_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);

        scan.setOnClickListener(v -> startScan());
        findViewById(R.id.dupes_cancel).setOnClickListener(v -> DuplicateScan.get().cancel());
        findViewById(R.id.dupes_delete).setOnClickListener(v -> confirmDelete());
        includePrivate.setOnCheckedChangeListener((b, on) -> {
            if (suppressSwitch) return;
            if (on && !VaultLock.isUnlocked()) requestUnlock();
            updateSecure();
        });
        prompt = VaultAuth.create(this, new VaultAuth.Callback() {
            @Override public void onUnlocked() {
                updateSecure();
            }

            @Override public void onFailed(boolean noLock, CharSequence message) {
                setSwitch(false);
                Toast.makeText(DuplicatesActivity.this, noLock ? "Set a screen lock to use Private videos"
                        : "Private stays locked: scanning the library only", Toast.LENGTH_SHORT).show();
            }
        });
        deleteFlow = new DeleteVideosFlow(this, new DeleteVideosFlow.Callback() {
            @Override public void onDeleteFinished(List<VideoItem> deleted) {
                onLibraryDeleted(deleted);
            }

            @Override public void onRestored(List<VideoItem> restored) {
                restoreView();
            }
        });
        deleteFlow.setOwnUndoBar(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        DuplicateScan scanJob = DuplicateScan.get();
        scanJob.setListener(this);
        if (includePrivate.isChecked() && !VaultLock.isUnlocked()) setSwitch(false);
        if (scanJob.isRunning()) {
            showScanning(scanJob.phase(), scanJob.done(), scanJob.total());
        } else {
            DuplicateScan.Result r = scanJob.last();
            if (r != null && r.includedPrivate && !VaultLock.isUnlocked()) {
                r = r.withoutPrivate();      // locked meanwhile: Private videos disappear from here
                scanJob.setLast(r);
            }
            if (r != null && r.includedPrivate && !includePrivate.isChecked()) setSwitch(true);
            if (r != null) bindResult(r, result == null || result.finishedAtMs != r.finishedAtMs);
            else showIdle();
        }
        updateSecure();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (DuplicateScan.get() != null) DuplicateScan.get().setListener(null);   // the scan goes on
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ scan

    @VisibleForTesting
    void startScan() {
        if (DuplicateScan.get().isRunning()) return;
        boolean priv = includePrivate.isChecked() && VaultLock.isUnlocked();
        DuplicateScan.get().start(this, priv);
        showScanning("Listing videos", 0, 0);
    }

    private void showScanning(String phase, int done, int total) {
        progressBox.setVisibility(View.VISIBLE);
        scan.setVisibility(View.GONE);
        includePrivate.setEnabled(false);
        bottom.setVisibility(View.GONE);
        onProgress(phase, done, total);
    }

    @Override
    public void onProgress(String phase, int done, int total) {
        progressBox.setVisibility(View.VISIBLE);
        progressText.setText(progressLabel(phase, done, total));
        if (total > 0) {
            if (progress.isIndeterminate()) {
                progress.setVisibility(View.INVISIBLE);
                progress.setIndeterminate(false);
                progress.setVisibility(View.VISIBLE);
            }
            progress.setMax(total);
            progress.setProgressCompat(done, false);
        } else if (!progress.isIndeterminate()) {
            progress.setVisibility(View.INVISIBLE);
            progress.setIndeterminate(true);
            progress.setVisibility(View.VISIBLE);
        }
    }

    static String progressLabel(String phase, int done, int total) {
        if (total <= 0) return phase + "…";
        return String.format(Locale.UK, "%s · %d of %d", phase, Math.min(done + 1, total), total);
    }

    @Override
    public void onFinished(@Nullable DuplicateScan.Result r, @Nullable String error, boolean cancelled) {
        progressBox.setVisibility(View.GONE);
        scan.setVisibility(View.VISIBLE);
        includePrivate.setEnabled(true);
        if (cancelled) {
            Toast.makeText(this, "Scan cancelled. What was read so far is remembered", Toast.LENGTH_SHORT).show();
            if (result != null) bindResult(result, false); else showIdle();
            return;
        }
        if (error != null || r == null) {
            Toast.makeText(this, "Couldn't scan: " + error, Toast.LENGTH_LONG).show();
            showIdle();
            return;
        }
        bindResult(r, true);
    }

    private void showIdle() {
        progressBox.setVisibility(View.GONE);
        scan.setVisibility(View.VISIBLE);
        scan.setText(R.string.dupes_scan);
        info.setText(R.string.dupes_intro);
        empty.setVisibility(View.GONE);
        bottom.setVisibility(View.GONE);
    }

    /** Shows a result; {@code fresh} = tick the suggestions (every copy except the keeper). */
    @VisibleForTesting
    void bindResult(DuplicateScan.Result r, boolean fresh) {
        result = r;
        rows.clear();
        Set<String> live = new HashSet<>();
        for (DuplicateFinder.Group g : r.groups) {
            rows.add(g);
            for (DuplicateFinder.Item it : g.items) {
                rows.add(new Row(g, it));
                live.add(it.key);
            }
        }
        ticked.retainAll(live);
        if (fresh) {
            ticked.clear();
            for (DuplicateFinder.Group g : r.groups) suggest(g);
        }
        adapter.notifyDataSetChanged();
        scan.setText(R.string.dupes_scan_again);
        long free = 0;
        for (DuplicateFinder.Group g : r.groups) free += g.reclaimableBytes();
        info.setText(summary(r.scanned, r.groups.size(), free, r.includedPrivate));
        empty.setText(R.string.dupes_none);
        empty.setVisibility(r.groups.isEmpty() ? View.VISIBLE : View.GONE);
        updateSelection();
        updateSecure();
    }

    static String summary(int scanned, int groups, long freeBytes, boolean priv) {
        String s = "Scanned " + String.format(Locale.UK, "%,d", scanned) + (scanned == 1 ? " video" : " videos")
                + (priv ? " (incl. Private)" : "");
        if (groups == 0) return s + " · no duplicates";
        s += " · " + groups + (groups == 1 ? " group" : " groups");
        String f = Fmt.size(freeBytes);
        return f.isEmpty() ? s : s + " · up to " + f + " can be freed";
    }

    // ------------------------------------------------------------------ ticks

    private void suggest(DuplicateFinder.Group g) {
        for (DuplicateFinder.Item it : g.items) {
            if (it == g.keep) ticked.remove(it.key); else ticked.add(it.key);
        }
    }

    /** {@code g == null}: every group. */
    @VisibleForTesting
    void applySuggestions(@Nullable DuplicateFinder.Group g) {
        if (result == null) return;
        if (g != null) suggest(g);
        else for (DuplicateFinder.Group x : result.groups) suggest(x);
        adapter.notifyDataSetChanged();
        updateSelection();
    }

    @VisibleForTesting
    void clearTicks() {
        ticked.clear();
        adapter.notifyDataSetChanged();
        updateSelection();
    }

    @VisibleForTesting
    void toggle(DuplicateFinder.Item it) {
        if (!ticked.remove(it.key)) ticked.add(it.key);
        updateSelection();
    }

    Set<String> ticked() {
        return ticked;
    }

    List<DuplicateFinder.Item> tickedItems() {
        List<DuplicateFinder.Item> out = new ArrayList<>();
        if (result == null) return out;
        for (DuplicateFinder.Group g : result.groups)
            for (DuplicateFinder.Item it : g.items) if (ticked.contains(it.key)) out.add(it);
        return out;
    }

    /** Groups where every video is ticked (not allowed: keep at least one). */
    @VisibleForTesting
    List<DuplicateFinder.Group> fullyTickedGroups() {
        List<DuplicateFinder.Group> out = new ArrayList<>();
        if (result == null) return out;
        for (DuplicateFinder.Group g : result.groups) {
            boolean all = true;
            for (DuplicateFinder.Item it : g.items) if (!ticked.contains(it.key)) all = false;
            if (all) out.add(g);
        }
        return out;
    }

    private void updateSelection() {
        List<DuplicateFinder.Item> t = tickedItems();
        boolean show = result != null && !result.groups.isEmpty();
        bottom.setVisibility(show ? View.VISIBLE : View.GONE);
        long bytes = 0;
        for (DuplicateFinder.Item it : t) bytes += Math.max(0, it.size);
        String size = Fmt.size(bytes);
        selection.setText(t.isEmpty() ? "Tick the copies you want to remove"
                : t.size() + (t.size() == 1 ? " video ticked" : " videos ticked") + (size.isEmpty() ? "" : " · " + size));
        findViewById(R.id.dupes_delete).setEnabled(!t.isEmpty());
    }

    // ------------------------------------------------------------------ delete

    /** Confirms, then moves the ticked videos to the Trash. Returns the dialog (null if refused). */
    @VisibleForTesting
    @Nullable
    AlertDialog confirmDelete() {
        List<DuplicateFinder.Item> t = tickedItems();
        if (t.isEmpty()) {
            Toast.makeText(this, "Tick the copies you want to remove first", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (deleteFlow.isBusy()) return null;
        List<DuplicateFinder.Group> all = fullyTickedGroups();
        if (!all.isEmpty()) {
            return new MaterialAlertDialogBuilder(this)
                    .setTitle("Keep at least one copy")
                    .setMessage(all.size() == 1
                            ? "Every video in \u201c" + all.get(0).keep.name + "\u201d is ticked. Untick the one to keep."
                            : "Every video is ticked in " + all.size() + " groups. Untick the one to keep in each.")
                    .setPositiveButton("Use suggestions", (d, w) -> { for (DuplicateFinder.Group g : all) applySuggestions(g); })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
        long bytes = 0;
        int priv = 0;
        for (DuplicateFinder.Item it : t) {
            bytes += Math.max(0, it.size);
            if (it.isPrivate()) priv++;
        }
        boolean libraryTrash = DeleteVideosFlow.usesTrash();
        String msg = (t.size() == 1 ? "1 video" : t.size() + " videos")
                + (Fmt.size(bytes).isEmpty() ? "" : " (" + Fmt.size(bytes) + ")")
                + (libraryTrash || priv == t.size()
                    ? " will move to the Trash. You can undo, and they stay there for 30 days."
                    : ". Android " + (Build.VERSION.SDK_INT >= 29 ? "10" : "9 and older")
                        + " has no trash, so library videos are deleted permanently (Android asks once more); Private videos go to the Private trash.");
        AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(t.size() == 1 ? "Remove 1 duplicate?" : "Remove " + t.size() + " duplicates?")
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dupes_move_to_trash, (dlg, w) -> delete(t))
                .show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.danger));
        return d;
    }

    @VisibleForTesting
    void delete(List<DuplicateFinder.Item> items) {
        pendingLibrary = new ArrayList<>();
        pendingVault = new ArrayList<>();
        for (DuplicateFinder.Item it : items) (it.isPrivate() ? pendingVault : pendingLibrary).add(it);
        beforeDelete = result;
        if (!pendingLibrary.isEmpty()) {
            List<VideoItem> vids = new ArrayList<>();
            for (DuplicateFinder.Item it : pendingLibrary) vids.add(it.video);
            deleteFlow.start(vids);     // Android's trash request (auto-approved with media management)
        } else {
            onLibraryDeleted(new ArrayList<>());
        }
    }

    private void onLibraryDeleted(List<VideoItem> deleted) {
        if (!pendingLibrary.isEmpty() && deleted.isEmpty()) {
            // Declined or failed: don't touch the Private ones either.
            pendingVault = new ArrayList<>();
            pendingLibrary = new ArrayList<>();
            return;
        }
        final Set<String> gone = new HashSet<>();
        for (VideoItem v : deleted) gone.add("ms:" + v.id);
        final List<Vault.Entry> vault = new ArrayList<>();
        for (DuplicateFinder.Item it : pendingVault) {
            vault.add(it.vault);
            gone.add(it.key);
        }
        final List<VideoItem> trashedLibrary = DeleteVideosFlow.usesTrash() ? new ArrayList<>(deleted) : new ArrayList<>();
        pendingVault = new ArrayList<>();
        pendingLibrary = new ArrayList<>();
        final Context app = getApplicationContext();
        if (!vault.isEmpty()) {
            io.execute(() -> {
                try {
                    Vault.trash(app, vault, System.currentTimeMillis());
                } catch (java.io.IOException ignored) {
                }
            });
        }
        if (result != null) {
            DuplicateScan.Result left = result.without(gone);
            DuplicateScan.get().setLast(left);
            bindResult(left, false);
        }
        int undoable = trashedLibrary.size() + vault.size();
        if (undoable == 0) return;     // Android 10 permanent delete: DeleteVideosFlow already said so
        undoBar = Snackbar.make(findViewById(R.id.dupes_root), DeleteVideosFlow.trashedMessage(undoable), 6000)
                .setAction(R.string.undo, v -> undo(trashedLibrary, vault))
                .setActionTextColor(ContextCompat.getColor(this, R.color.accent_text))
                .setBackgroundTint(ContextCompat.getColor(this, R.color.surface3))
                .setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        undoBar.show();
    }

    @Nullable
    Snackbar undoBar() {
        return undoBar;
    }

    private void undo(List<VideoItem> library, List<Vault.Entry> vault) {
        final Context app = getApplicationContext();
        if (!vault.isEmpty()) {
            io.execute(() -> {
                try {
                    Vault.restore(app, vault);
                } catch (java.io.IOException ignored) {
                }
                if (library.isEmpty()) handler.post(this::restoreView);
            });
        }
        if (!library.isEmpty()) deleteFlow.untrash(library);   // onRestored → restoreView
    }

    private void restoreView() {
        if (isDestroyed() || beforeDelete == null) return;
        DuplicateScan.Result r = beforeDelete;
        beforeDelete = null;
        if (r.includedPrivate && !VaultLock.isUnlocked()) r = r.withoutPrivate();
        DuplicateScan.get().setLast(r);
        bindResult(r, false);
    }

    @VisibleForTesting
    DeleteVideosFlow deleteFlow() {
        return deleteFlow;
    }

    // ------------------------------------------------------------------ Private

    private void requestUnlock() {
        if (!VaultAuth.isAvailable(this)) {
            setSwitch(false);
            Toast.makeText(this, "Set a screen lock to use Private videos", Toast.LENGTH_SHORT).show();
            return;
        }
        handler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                VaultAuth.prompt(prompt);
            } catch (RuntimeException e) {
                setSwitch(false);
            }
        });
    }

    private void setSwitch(boolean on) {
        suppressSwitch = true;
        includePrivate.setChecked(on);
        suppressSwitch = false;
        updateSecure();
    }

    /** No screenshots / recents preview while Private videos may be on screen. */
    private void updateSecure() {
        boolean secure = includePrivate.isChecked() || (result != null && result.includedPrivate);
        if (secure) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!secure);
    }

    // ------------------------------------------------------------------ list

    List<Object> rows() {
        return rows;
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof DuplicateFinder.Group ? 0 : 1;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            return type == 0 ? new HeaderHolder(inf.inflate(R.layout.item_dupe_header, parent, false))
                    : new VideoHolder(inf.inflate(R.layout.item_dupe_video, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Object o = rows.get(position);
            if (h instanceof HeaderHolder) {
                DuplicateFinder.Group g = (DuplicateFinder.Group) o;
                HeaderHolder hh = (HeaderHolder) h;
                hh.title.setText(DuplicateFinder.groupTitle(g));
                hh.sub.setText(DuplicateFinder.keepReason(g));
                hh.suggest.setOnClickListener(v -> applySuggestions(g));
                return;
            }
            Row r = (Row) o;
            VideoHolder vh = (VideoHolder) h;
            DuplicateFinder.Item it = r.item;
            vh.title.setText(it.name);
            vh.meta.setText(DuplicateFinder.describe(it));
            vh.keep.setVisibility(it == r.group.keep ? View.VISIBLE : View.GONE);
            vh.duration.setText(Fmt.time(it.durationMs));
            vh.duration.setVisibility(it.durationMs > 0 ? View.VISIBLE : View.GONE);
            vh.check.setOnCheckedChangeListener(null);
            vh.check.setChecked(ticked.contains(it.key));
            vh.check.setOnCheckedChangeListener((b, on) -> {
                if (on != ticked.contains(it.key)) toggle(it);
            });
            vh.itemView.setOnClickListener(v -> vh.check.toggle());
            vh.thumb.setImageDrawable(null);
            vh.boundKey = it.key;
            if (it.video != null) {
                ThumbnailLoader.load(vh.thumb, it.video);
            } else {
                final Context app = getApplicationContext();
                io.execute(() -> {
                    Bitmap b = Vault.loadThumb(app, it.vault);
                    handler.post(() -> {
                        if (b != null && it.key.equals(vh.boundKey)) vh.thumb.setImageBitmap(b);
                    });
                });
            }
        }
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title, sub, suggest;

        HeaderHolder(View v) {
            super(v);
            title = v.findViewById(R.id.dupe_group_title);
            sub = v.findViewById(R.id.dupe_group_sub);
            suggest = v.findViewById(R.id.dupe_group_suggest);
        }
    }

    static final class VideoHolder extends RecyclerView.ViewHolder {
        final CheckBox check;
        final ImageView thumb;
        final TextView title, meta, keep, duration;
        String boundKey;

        VideoHolder(View v) {
            super(v);
            check = v.findViewById(R.id.dupe_check);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            keep = v.findViewById(R.id.dupe_keep_badge);
            duration = v.findViewById(R.id.duration);
        }
    }
}
