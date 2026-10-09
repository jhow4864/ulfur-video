package com.jamh.videoplayer;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements LibraryAdapter.Listener {

    private static final int TAB_VIDEOS = 0, TAB_FOLDERS = 1, TAB_CLIPS = 2;

    private MaterialToolbar toolbar;
    private TabLayout tabs;
    private RecyclerView recycler;
    private View emptyPanel;
    private TextView emptyText, subtitle;
    private MaterialButton permissionButton;
    private LibraryAdapter adapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private List<VideoItem> allVideos = new ArrayList<>();
    private boolean openFeedWhenLoaded;
    private int tab = TAB_VIDEOS;
    private String openFolderId;     // non-null when browsing inside a folder
    private String openFolderName;
    private String query = "";
    private boolean grid;
    private int sort;
    private boolean permissionAskedOnce;
    private MenuItem searchItem;
    private View defaultBanner;
    private View selectionBar;
    private TextView selectionMoveButton, selectionSelectAll;
    private View selectionDeleteButton;
    /** Set when we sent the user to Settings or the "Open with" dialog; re-checked in onResume. */
    private boolean pendingDefaultCheck;
    /** 1.4.1: last known media-management state, re-checked in onResume. */
    private Boolean lastCanManageMedia;

    private final MoveToPrivateFlow moveToPrivate = new MoveToPrivateFlow(this, this::refresh);
    /** 1.5.1: bulk / single delete from selection mode (Videos, folders, Clips). */
    private final DeleteVideosFlow deleteFlow = new DeleteVideosFlow(this, new DeleteVideosFlow.Callback() {
        @Override public void onDeleteFinished(List<VideoItem> deleted) {
            onVideosDeleted(deleted);
        }

        @Override public void onRestored(List<VideoItem> restored) {
            if (!isDestroyed()) refresh(); // Undo: back from the trash
        }
    });

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                permissionAskedOnce = true;
                refresh();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Edge-to-edge with light (white) system-bar icons over our dark surfaces.
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        setContentView(R.layout.activity_main);
        // 1.9.2: show last launch's crash report straight away (not only from onResume, which a
        // crash in onStart never reaches).
        try {
            maybeShowCrashReport();
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "crash report dialog failed", t);
        }
        toolbar = findViewById(R.id.toolbar);
        tabs = findViewById(R.id.tabs);
        recycler = findViewById(R.id.recycler);
        emptyPanel = findViewById(R.id.empty_panel);
        emptyText = findViewById(R.id.empty_text);
        subtitle = findViewById(R.id.subtitle);
        permissionButton = findViewById(R.id.btn_permission);
        defaultBanner = findViewById(R.id.default_banner);
        selectionBar = findViewById(R.id.selection_bar);
        selectionMoveButton = findViewById(R.id.selection_move_private);
        selectionSelectAll = findViewById(R.id.selection_select_all);
        selectionMoveButton.setOnClickListener(v -> moveSelectedToPrivate());
        selectionDeleteButton = findViewById(R.id.selection_delete);
        selectionDeleteButton.setOnClickListener(v -> deleteSelected());
        selectionSelectAll.setOnClickListener(v -> toggleSelectAll());
        findViewById(R.id.banner_setup).setOnClickListener(v -> {
            Prefs.setDefaultBannerDismissed(this);
            defaultBanner.setVisibility(View.GONE);
            showDefaultPlayerDialog();
        });
        findViewById(R.id.banner_dismiss).setOnClickListener(v -> {
            Prefs.setDefaultBannerDismissed(this);
            defaultBanner.setVisibility(View.GONE);
        });
        setSupportActionBar(toolbar);
        // 1.9.2: our own Cast mini bar (no Cast fragment; inflated only while casting).
        castBar = CastMiniBar.attach(this, findViewById(R.id.cast_mini_controller));
        findViewById(R.id.safe_mode_banner).setVisibility(CrashGuard.safeMode() ? View.VISIBLE : View.GONE);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, 0);
            navBarInset = i.bottom;
            updateRecyclerBottomPadding();
            ((android.view.ViewGroup.MarginLayoutParams) selectionBar.getLayoutParams()).bottomMargin = i.bottom + dp(16);
            selectionBar.requestLayout();
            return insets;
        });

        grid = Prefs.isGrid(this);
        sort = Prefs.sort(this);
        if (savedInstanceState != null) {
            tab = savedInstanceState.getInt("tab", TAB_VIDEOS);
            openFolderId = savedInstanceState.getString("folderId");
            openFolderName = savedInstanceState.getString("folderName");
            query = savedInstanceState.getString("query", "");
            pendingDefaultCheck = savedInstanceState.getBoolean("pendingDefaultCheck", false);
        } else if (Shortcuts.ACTION_OPEN_CLIPS.equals(getIntent().getAction())) {
            // Static "Clips" launcher shortcut: Clips tab, then straight into the swipe feed.
            tab = TAB_CLIPS;
            openFeedWhenLoaded = true;
        }

        tabs.addTab(tabs.newTab().setText(R.string.tab_videos), tab == TAB_VIDEOS);
        tabs.addTab(tabs.newTab().setText(R.string.tab_folders), tab == TAB_FOLDERS);
        tabs.addTab(tabs.newTab().setText(R.string.tab_clips), tab == TAB_CLIPS);
        com.google.android.material.chip.ChipGroup clipsFilter = findViewById(R.id.clips_filter);
        int[] chipIds = {R.id.chip_clips_both, R.id.chip_clips_portrait, R.id.chip_clips_short};
        clipsFilter.check(chipIds[Math.max(0, Math.min(2, Prefs.clipsFilter(this)))]);
        clipsFilter.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            Prefs.setClipsFilter(this, id == R.id.chip_clips_portrait ? 1 : id == R.id.chip_clips_short ? 2 : 0);
            render();
        });
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab t) {
                tab = t.getPosition();
                if (adapter != null) adapter.setSelectionMode(false);
                openFolderId = null;
                applyLayoutManager();
                render();
            }
            @Override public void onTabUnselected(TabLayout.Tab t) {}
            @Override public void onTabReselected(TabLayout.Tab t) {
                if (openFolderId != null) { openFolderId = null; render(); }
            }
        });

        adapter = new LibraryAdapter(this);
        recycler.setAdapter(adapter);
        applyLayoutManager();
        if (savedInstanceState != null && savedInstanceState.getBoolean("selecting", false)) {
            adapter.restoreSelection(savedInstanceState.getLongArray("selectedIds"));
        }

        permissionButton.setOnClickListener(v -> requestOrOpenSettings());
        toolbar.setNavigationOnClickListener(v -> {
            if (adapter.isSelectionMode()) exitSelection(); else closeFolder();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (adapter.isSelectionMode()) {
                    exitSelection();
                } else if (searchItem != null && searchItem.isActionViewExpanded()) {
                    searchItem.collapseActionView();
                } else if (openFolderId != null) {
                    closeFolder();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Shortcuts.ACTION_OPEN_CLIPS.equals(intent.getAction())) {
            openFolderId = null;
            TabLayout.Tab t = tabs.getTabAt(TAB_CLIPS);
            if (t != null) t.select();
            if (!allVideos.isEmpty()) openFirstClip();
            else openFeedWhenLoaded = true;
        }
    }

    /** Opens the swipe feed at the first clip matching the current Clips filter (if any). */
    private void openFirstClip() {
        String savedQuery = query;
        query = "";
        List<VideoItem> clips = shownClips();
        query = savedQuery;
        if (clips.isEmpty()) {
            Toast.makeText(this, "No clips yet: portrait or short videos show up here", Toast.LENGTH_LONG).show();
            return;
        }
        onClipClick(clips.get(0));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putString("folderId", openFolderId);
        out.putString("folderName", openFolderName);
        out.putString("query", query);
        out.putBoolean("pendingDefaultCheck", pendingDefaultCheck);
        if (adapter != null && adapter.isSelectionMode()) {
            out.putBoolean("selecting", true);
            Set<Long> ids = adapter.selectedIds();
            long[] arr = new long[ids.size()];
            int k = 0;
            for (long id : ids) arr[k++] = id;
            out.putLongArray("selectedIds", arr);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        boolean canManage = MediaAccess.canManageMedia(this);
        if (lastCanManageMedia != null && lastCanManageMedia != canManage && MediaAccess.isSupported()) {
            Snackbar.make(findViewById(R.id.root), canManage
                    ? "Media management allowed: no more delete pop-ups when moving to Private"
                    : "Media management is off: Android will ask before deleting originals", Snackbar.LENGTH_LONG).show();
        }
        lastCanManageMedia = canManage;
        invalidateOptionsMenu();
        try {
            DefaultPlayerHelper.Status status = DefaultPlayerHelper.check(this);
            updateDefaultBanner(status);
            if (pendingDefaultCheck) {
                pendingDefaultCheck = false;
                reportDefaultResult(status);
            }
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "default player check failed", t);
        }
        maybeShowCrashReport();
    }

    // ---------------------------------------------------------------- 1.9.1 crash report

    private androidx.appcompat.app.AlertDialog crashDialog;

    /**
     * Shows the last crash (filesDir/last_crash.txt) at most once: 1.9.3 deletes the file as soon as
     * it is read, and reports from other versions are dropped. Copy crash log / Share / Dismiss,
     * cancelable; the log area is height-capped so the buttons are always on screen.
     */
    androidx.appcompat.app.AlertDialog maybeShowCrashReport() {
        if (crashDialog != null && crashDialog.isShowing()) return crashDialog;
        final String report = CrashGuard.takeReport(this);
        if (report == null) return null;
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tv.setPadding(dp(20), dp(8), dp(20), dp(8));
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.4f);
        android.widget.ScrollView sv = new android.widget.ScrollView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            }
        };
        sv.addView(tv);
        crashDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Ulfur Video crashed last time")
                .setMessage("Sorry about that. Please send this crash log so it can be fixed.")
                .setView(sv)
                .setCancelable(true)
                .setPositiveButton("Copy crash log", null)
                .setNeutralButton("Share", null)
                .setNegativeButton("Dismiss", (d, w) -> d.dismiss())
                .create();
        crashDialog.setCanceledOnTouchOutside(true);
        crashDialog.show();
        crashDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Ulfur Video crash log", report));
            Toast.makeText(this, "Crash log copied", Toast.LENGTH_SHORT).show();
            crashDialog.dismiss();
        });
        crashDialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Ulfur Video crash log")
                    .putExtra(Intent.EXTRA_TEXT, report);
            try {
                startActivity(Intent.createChooser(send, "Share crash log"));
                crashDialog.dismiss();
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show();
            }
        });
        return crashDialog;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (castBar != null) castBar.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (castBar != null) castBar.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @androidx.annotation.Nullable private CastMiniBar castBar;

    @androidx.annotation.VisibleForTesting
    @androidx.annotation.Nullable
    CastMiniBar castBar() {
        return castBar;
    }

    // ---------------------------------------------------------------- permissions

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED};
        } else if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasFullAccess() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPartialAccess() {
        return Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestOrOpenSettings() {
        String p = requiredPermissions()[0];
        if (permissionAskedOnce && !shouldShowRequestPermissionRationale(p) && !hasPartialAccess()) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        } else {
            permissionLauncher.launch(requiredPermissions());
        }
    }

    // ---------------------------------------------------------------- data

    private void refresh() {
        if (!hasFullAccess() && !hasPartialAccess()) {
            allVideos = new ArrayList<>();
            showEmpty(getString(R.string.permission_needed), true);
            subtitle.setText("");
            if (!permissionAskedOnce) {
                permissionAskedOnce = true;
                permissionLauncher.launch(requiredPermissions());
            }
            return;
        }
        io.execute(() -> {
            List<VideoItem> list = VideoRepository.all(getApplicationContext());
            main.post(() -> {
                if (isDestroyed()) return;
                allVideos = list;
                Set<Long> present = new HashSet<>();
                for (VideoItem v : list) present.add(v.id);
                adapter.retainSelection(present);
                render();
                if (openFeedWhenLoaded) {
                    openFeedWhenLoaded = false;
                    openFirstClip();
                }
            });
        });
    }

    private void render() {
        if (!hasFullAccess() && !hasPartialAccess()) return;
        boolean inFolder = openFolderId != null;
        updateToolbar();
        String q = query.trim().toLowerCase(Locale.ROOT);

        List<Object> data = new ArrayList<>();
        String sub;
        findViewById(R.id.clips_filter_bar).setVisibility(tab == TAB_CLIPS && !inFolder ? View.VISIBLE : View.GONE);
        adapter.setClipsMode(tab == TAB_CLIPS && !inFolder);
        // 1.7.0: per-screen sort, filters and the Largest first storage view.
        LibraryView view = currentView();
        sort = view.toLegacy();
        boolean foldersList = tab == TAB_FOLDERS && !inFolder;
        LibraryBar.bind(findViewById(R.id.library_chips), view, foldersList, libraryBarCallbacks);
        findViewById(R.id.library_bar).setVisibility(View.VISIBLE);
        long shownBytes = 0;
        int unfiltered;
        if (tab == TAB_CLIPS && !inFolder) {
            List<VideoItem> clips = shownClips();
            unfiltered = candidateVideos().size();
            data.addAll(clips);
            shownBytes = LibrarySort.totalSize(clips);
            sub = countLabel(clips.size(), unfiltered, view, " clip", " clips") + "  ·  tap to open the swipe feed";
        } else if (tab == TAB_VIDEOS || inFolder) {
            List<VideoItem> vids = shownVideos();
            unfiltered = candidateVideos().size();
            data.addAll(vids);
            shownBytes = LibrarySort.totalSize(vids);
            sub = countLabel(vids.size(), unfiltered, view, " video", " videos");
        } else {
            List<Folder> folders = VideoRepository.folders(allVideos);
            List<Folder> filtered = new ArrayList<>();
            for (Folder f : folders) {
                if (q.isEmpty() || f.name.toLowerCase(Locale.ROOT).contains(q)) {
                    filtered.add(f);
                    shownBytes += f.totalSize;
                }
            }
            filtered = LibrarySort.folders(filtered, view);
            data.addAll(filtered);
            unfiltered = filtered.size();
            sub = filtered.size() + (filtered.size() == 1 ? " folder" : " folders");
        }
        adapter.setStorageTotal(view.storage ? shownBytes : 0);
        shownTotalBytes = shownBytes;
        updateStorageCard(view, data.size(), foldersList);
        if (tab != TAB_FOLDERS || inFolder) {
            if (!data.isEmpty() && !adapter.isSelectionMode()) sub += "  ·  " + getString(R.string.library_hint);
        }
        if (!hasFullAccess() && hasPartialAccess()) {
            sub += "  ·  Limited access (tap to allow all)";
            subtitle.setOnClickListener(v -> permissionLauncher.launch(requiredPermissions()));
        } else {
            subtitle.setOnClickListener(null);
        }
        subtitle.setText(sub);
        adapter.submit(data);
        if (data.isEmpty()) {
            showEmpty(!q.isEmpty() ? "No results for \"" + query + "\""
                    : view.filterCount() > 0 && unfiltered > 0 && !foldersList ? getString(R.string.lib_no_matches)
                    : getString(R.string.no_videos), false);
        } else {
            emptyPanel.setVisibility(View.GONE);
            recycler.setVisibility(View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------- 1.7.0 sort & filter

    private long shownTotalBytes;

    /** The remembered view of the screen on show: Videos, a folder's contents, Clips or the Folders list. */
    String screenKey() {
        if (openFolderId != null) return LibraryView.FOLDER;
        if (tab == TAB_CLIPS) return LibraryView.CLIPS;
        if (tab == TAB_FOLDERS) return LibraryView.FOLDERS;
        return LibraryView.VIDEOS;
    }

    LibraryView currentView() {
        return LibraryView.load(this, screenKey());
    }

    void setView(LibraryView v) {
        LibraryView.save(this, screenKey(), v);
        render();
    }

    private final LibraryBar.Callbacks libraryBarCallbacks = new LibraryBar.Callbacks() {
        @Override public void openSheet() {
            openSortSheet();
        }

        @Override public void onViewChanged(LibraryView v) {
            setView(v);
            recycler.scrollToPosition(0);
        }
    };

    com.google.android.material.bottomsheet.BottomSheetDialog openSortSheet() {
        boolean folders = tab == TAB_FOLDERS && openFolderId == null;
        return LibrarySheet.show(this, currentView(), folders, new LibrarySheet.Host() {
            @Override public void onViewChanged(LibraryView v) {
                setView(v);
                recycler.scrollToPosition(0);
            }

            @Override public String summary(LibraryView v) {
                if (folders) {
                    int n = VideoRepository.folders(allVideos).size();
                    return n + (n == 1 ? " folder" : " folders");
                }
                int all = candidateVideos().size();
                int shown = LibrarySort.apply(candidateVideos(), v, LibrarySort.videos(WatchHistory.gallery(MainActivity.this))).size();
                String noun = tab == TAB_CLIPS && openFolderId == null ? " clips" : " videos";
                return shown == all ? all + noun : shown + " of " + all + noun;
            }
        });
    }

    /** Videos of this screen before the sort / filter chips (search and folder / clip type applied). */
    private List<VideoItem> candidateVideos() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        boolean clips = tab == TAB_CLIPS && openFolderId == null;
        int clipFilter = Prefs.clipsFilter(this);
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : allVideos) {
            if (openFolderId != null && !v.bucketId.equals(openFolderId)) continue;
            if (clips && !v.matchesClipFilter(clipFilter)) continue;
            if (!q.isEmpty() && !v.title.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(v);
        }
        return out;
    }

    /** Videos (or folder contents) as shown: filtered and sorted. */
    List<VideoItem> shownVideos() {
        return LibrarySort.apply(candidateVideos(), currentView(), LibrarySort.videos(WatchHistory.gallery(this)));
    }

    /** Clips as shown (the swipe feed plays them in this order). */
    private List<VideoItem> shownClips() {
        return LibrarySort.apply(candidateVideos(), LibraryView.load(this, LibraryView.CLIPS),
                LibrarySort.videos(WatchHistory.gallery(this)));
    }

    private static String countLabel(int shown, int all, LibraryView v, String one, String many) {
        if (v.filterCount() > 0 && shown != all) return shown + " of " + all + (all == 1 ? one : many);
        return shown + (shown == 1 ? one : many);
    }

    private void updateStorageCard(LibraryView v, int count, boolean folders) {
        View card = findViewById(R.id.storage_card);
        card.setVisibility(v.storage && count > 0 ? View.VISIBLE : View.GONE);
        if (!v.storage) return;
        ((TextView) findViewById(R.id.storage_total)).setText(
                (shownTotalBytes > 0 ? Fmt.size(shownTotalBytes) : "0 MB") + " in this view");
        String noun = folders ? (count == 1 ? " folder" : " folders") : (count == 1 ? " video" : " videos");
        ((TextView) findViewById(R.id.storage_detail)).setText(count + noun + ", largest first"
                + (folders ? "" : (adapter.isSelectionMode() ? "" : "  ·  long-press to select")));
        updateStorageSelection();
    }

    private void updateStorageSelection() {
        TextView sel = findViewById(R.id.storage_selected);
        int n = adapter == null ? 0 : adapter.selectedCount();
        boolean show = adapter != null && adapter.isSelectionMode() && n > 0;
        sel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) sel.setText(Fmt.size(selectedBytes()) + " selected");
    }

    long selectedBytes() {
        return LibrarySort.totalSize(selectedVideos());
    }

    private void showEmpty(String text, boolean showButton) {
        emptyText.setText(text);
        permissionButton.setVisibility(showButton ? View.VISIBLE : View.GONE);
        emptyPanel.setVisibility(View.VISIBLE);
        recycler.setVisibility(View.GONE);
        adapter.submit(new ArrayList<>());
    }

    private void applyLayoutManager() {
        if (tab == TAB_CLIPS && openFolderId == null) {
            int widthDp = getResources().getConfiguration().screenWidthDp;
            recycler.setLayoutManager(new GridLayoutManager(this, Math.max(3, widthDp / 130)));
        } else if (grid) {
            int widthDp = getResources().getConfiguration().screenWidthDp;
            int span = Math.max(2, widthDp / 170);
            recycler.setLayoutManager(new GridLayoutManager(this, span));
        } else {
            recycler.setLayoutManager(new LinearLayoutManager(this));
        }
        adapter.setGrid(grid);
    }

    private void closeFolder() {
        openFolderId = null;
        render();
    }

    // ---------------------------------------------------------------- multi-select (1.4.0)

    private int navBarInset;

    private void updateRecyclerBottomPadding() {
        boolean bar = adapter != null && adapter.isSelectionMode();
        recycler.setPadding(recycler.getPaddingLeft(), recycler.getPaddingTop(), recycler.getPaddingRight(),
                navBarInset + dp(bar ? 96 : 24));
    }

    /** Toolbar: app mark + "Ulfur Video" at the top level, folder name inside a folder, "N selected" while selecting. */
    private void updateToolbar() {
        boolean selecting = adapter != null && adapter.isSelectionMode();
        boolean inFolder = openFolderId != null;
        if (selecting) {
            toolbar.setLogo(null);
            toolbar.setNavigationIcon(R.drawable.ic_close);
            toolbar.setNavigationContentDescription(R.string.clear_selection);
            toolbar.setTitle(getString(R.string.selection_count, adapter.selectedCount()));
            // 1.7.0: total size of the selection (what deleting would free).
            long bytes = adapter.selectedCount() > 0 ? selectedBytes() : 0;
            toolbar.setSubtitle(bytes > 0 ? Fmt.size(bytes) : null);
        } else {
            toolbar.setLogo(inFolder ? null : ContextCompat.getDrawable(this, R.drawable.ic_app_mark));
            toolbar.setNavigationIcon(inFolder ? ContextCompat.getDrawable(this, R.drawable.ic_arrow_back) : null);
            toolbar.setNavigationContentDescription(null);
            toolbar.setTitle(inFolder ? openFolderName : getString(R.string.app_name));
            toolbar.setSubtitle(null);
        }
        if (toolbar.getNavigationIcon() != null) toolbar.getNavigationIcon().setTint(0xFFF2F2F7);
    }

    private void enterSelection(VideoItem first) {
        if (searchItem != null && searchItem.isActionViewExpanded()) {
            // keep the filtered list: just hide the keyboard
            View f = getCurrentFocus();
            if (f != null) f.clearFocus();
        }
        adapter.setSelectionMode(true);
        if (first != null) adapter.toggle(first);
        render();
    }

    void exitSelection() {
        adapter.setSelectionMode(false);
        render();
    }

    @Override
    public void onSelectionChanged(int count) {
        boolean selecting = adapter != null && adapter.isSelectionMode();
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionMoveButton.setEnabled(count > 0);
        selectionMoveButton.setAlpha(count > 0 ? 1f : 0.45f);
        selectionMoveButton.setText(count > 1 ? "Move " + count + " to Private" : getString(R.string.move_selected_to_private));
        selectionDeleteButton.setEnabled(count > 0);
        selectionDeleteButton.setAlpha(count > 0 ? 1f : 0.45f);
        selectionDeleteButton.setContentDescription(count == 1 ? "Delete 1 video" : "Delete " + count + " videos");
        boolean all = adapter != null && count > 0 && count >= adapter.visibleVideoCount();
        selectionSelectAll.setText(all ? R.string.clear_selection : R.string.select_all);
        updateRecyclerBottomPadding();
        updateToolbar();
        updateStorageSelection();
        invalidateOptionsMenu();
    }

    private void toggleSelectAll() {
        if (adapter.selectedCount() > 0 && adapter.selectedCount() >= adapter.visibleVideoCount()) {
            adapter.setSelectionMode(false);
            adapter.setSelectionMode(true);   // clear, stay in selection mode
        } else {
            adapter.selectAllVisible();
        }
    }

    List<VideoItem> selectedVideos() {
        Set<Long> ids = adapter.selectedIds();
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : allVideos) if (ids.contains(v.id)) out.add(v);
        return out;
    }

    private void moveSelectedToPrivate() {
        List<VideoItem> vids = selectedVideos();
        if (vids.isEmpty()) {
            Toast.makeText(this, "Select some videos first", Toast.LENGTH_SHORT).show();
            return;
        }
        moveToPrivate.start(vids, this::exitSelection);
    }

    /** 1.5.1: one confirmation for the whole selection, then delete (no system prompt with media management). */
    void deleteSelected() {
        deleteFlow.start(selectedVideos());
    }

    /** Removes deleted videos from the list right away, leaves selection mode, then re-queries MediaStore. */
    void onVideosDeleted(List<VideoItem> deleted) {
        if (deleted.isEmpty() || isDestroyed()) return;   // cancelled: keep the selection
        removeDeleted(deleted);
        refresh();
    }

    /** Drops {@code deleted} from the shown list immediately and exits selection mode. */
    void removeDeleted(List<VideoItem> deleted) {
        Set<Long> gone = new HashSet<>();
        for (VideoItem v : deleted) gone.add(v.id);
        List<VideoItem> left = new ArrayList<>();
        for (VideoItem v : allVideos) if (!gone.contains(v.id)) left.add(v);
        allVideos = left;
        if (adapter.isSelectionMode()) exitSelection(); else render();
    }

    // ---------------------------------------------------------------- clicks

    @Override
    public void onVideoClick(VideoItem item) {
        // 1.7.0: while a Cast session is connected, tapping a video plays it on the TV.
        if (Caster.get().isConnected(this)) {
            Caster.get().castGalleryVideo(this, item);
            return;
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.setData(item.uri);
        i.putExtra(PlayerActivity.EXTRA_VIDEO_ID, item.id);
        i.putExtra(PlayerActivity.EXTRA_BUCKET_ID, item.bucketId);
        i.putExtra(PlayerActivity.EXTRA_SORT, sort);
        i.putExtra(PlayerActivity.EXTRA_SORT_SPEC, currentView().encode());
        startActivity(i);
    }

    @Override
    public void onVideoLongClick(VideoItem item, View anchor) {
        if (item.id < 0) return;
        // 1.4.0: long-press starts multi-select (tap more videos, then "Move to Private" once for all of them).
        if (adapter.isSelectionMode()) adapter.toggle(item);
        else enterSelection(item);
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    @Override
    public void onClipClick(VideoItem clip) {
        List<VideoItem> clips = shownClips();
        if (clips.isEmpty()) clips.add(clip);
        int start = 0;
        for (int i = 0; i < clips.size(); i++) if (clips.get(i).id == clip.id) start = i;
        ClipsFeedActivity.pendingClips = clips;
        Intent i = new Intent(this, ClipsFeedActivity.class);
        i.putExtra(ClipsFeedActivity.EXTRA_START, start);
        long[] ids = new long[clips.size()];
        for (int k = 0; k < ids.length; k++) ids[k] = clips.get(k).id;
        i.putExtra(ClipsFeedActivity.EXTRA_IDS, ids);
        startActivity(i);
    }

    @Override
    public void onFolderClick(Folder folder) {
        openFolderId = folder.bucketId;
        openFolderName = folder.name;
        if (searchItem != null && searchItem.isActionViewExpanded()) searchItem.collapseActionView();
        render();
        recycler.scrollToPosition(0);
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (adapter != null && adapter.isSelectionMode()) {
            getMenuInflater().inflate(R.menu.selection, menu);
            searchItem = null;
            return true;
        }
        getMenuInflater().inflate(R.menu.main, menu);
        try {
            CastSupport.setUpMenuButton(this, menu, R.id.media_route_menu_item);
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "Cast button unavailable", t);
            MenuItem castItem = menu.findItem(R.id.media_route_menu_item);
            if (castItem != null) castItem.setVisible(false);
        }
        searchItem = menu.findItem(R.id.action_search);
        SearchView sv = (SearchView) searchItem.getActionView();
        if (sv != null) {
            sv.setQueryHint(getString(R.string.search_hint));
            sv.setMaxWidth(Integer.MAX_VALUE);
            if (!query.isEmpty()) {
                searchItem.expandActionView();
                sv.setQuery(query, false);
            }
            sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override public boolean onQueryTextSubmit(String s) { sv.clearFocus(); return true; }
                @Override public boolean onQueryTextChange(String s) { query = s; render(); return true; }
            });
            searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override public boolean onMenuItemActionExpand(MenuItem item) { return true; }
                @Override public boolean onMenuItemActionCollapse(MenuItem item) { query = ""; render(); return true; }
            });
        }
        updateMenuState(menu);
        return true;
    }

    private void updateMenuState(Menu menu) {
        MenuItem viewMode = menu.findItem(R.id.action_view_mode);
        if (viewMode == null) return;   // selection menu
        MenuItem warn = menu.findItem(R.id.action_warn_private);
        if (warn != null) warn.setChecked(Prefs.warnBeforePrivate(this));
        MenuItem mm = menu.findItem(R.id.action_media_manage);
        if (mm != null) {
            mm.setVisible(MediaAccess.isSupported());
            mm.setChecked(MediaAccess.canManageMedia(this));
        }
        MenuItem trash = menu.findItem(R.id.action_trash);
        if (trash != null) trash.setVisible(DeleteVideosFlow.usesTrash()); // Android 10: no trash
        MenuItem select = menu.findItem(R.id.action_select);
        if (select != null) select.setVisible(tab != TAB_FOLDERS || openFolderId != null);
        viewMode.setIcon(grid ? R.drawable.ic_list : R.drawable.ic_grid);
        viewMode.setTitle(grid ? "List view" : "Grid view");
        MenuItem gestures = menu.findItem(R.id.action_gestures);
        if (gestures != null) gestures.setChecked(Prefs.gesturesEnabled(this));
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateMenuState(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_select_all) {
            toggleSelectAll();
            return true;
        } else if (id == R.id.action_move_selected_private) {
            moveSelectedToPrivate();
            return true;
        } else if (id == R.id.action_delete_selected) {
            deleteSelected();
            return true;
        } else if (id == R.id.action_select) {
            enterSelection(null);
            return true;
        } else if (id == R.id.action_media_manage) {
            if (MediaAccess.canManageMedia(this)) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.media_manage_menu)
                        .setMessage("Allowed. Moving videos to Private deletes the originals without Android asking.\n\n"
                                + "To turn it off: Open settings, then switch off \u201cAllow app to manage media\u201d.")
                        .setNegativeButton(android.R.string.ok, null)
                        .setPositiveButton(R.string.open_settings, (d, w) -> moveToPrivate.openManageMediaSettings())
                        .show();
            } else {
                Prefs.setMediaManagePromptOff(this, false);   // user is asking for it: forget "Don't ask again"
                moveToPrivate.showMediaManagementPrompt(null);
            }
            return true;
        } else if (id == R.id.action_warn_private) {
            boolean on = !Prefs.warnBeforePrivate(this);
            Prefs.setWarnBeforePrivate(this, on);
            item.setChecked(on);
            Toast.makeText(this, on ? "You'll be warned before videos move to Private"
                    : "No warning before moving to Private", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_view_mode) {
            grid = !grid;
            Prefs.setGrid(this, grid);
            applyLayoutManager();
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.action_sort) {
            openSortSheet();
            return true;
        } else if (id == R.id.action_gestures) {
            boolean on = !Prefs.gesturesEnabled(this);
            Prefs.setGesturesEnabled(this, on);
            item.setChecked(on);
            Toast.makeText(this, on ? "Player gestures on" : "Player gestures off: tap to show controls only",
                    Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_private) {
            startActivity(new Intent(this, VaultActivity.class));
            return true;
        } else if (id == R.id.action_trash) {
            startActivity(TrashActivity.intent(this, false));
            return true;
        } else if (id == R.id.action_duplicates) {
            try {
                startActivity(new Intent(this, DuplicatesActivity.class));
            } catch (Throwable t) {
                Toast.makeText(this, "Couldn't open Find duplicates", Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.action_refresh) {
            refresh();
            return true;
        } else if (id == R.id.action_default_player) {
            showDefaultPlayerDialog();
            return true;
        } else if (id == R.id.action_open_url) {
            showOpenUrlDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showOpenUrlDialog() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/video.m3u8");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Open network stream")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Play", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    Intent i = new Intent(this, PlayerActivity.class);
                    i.setData(Uri.parse(url));
                    startActivity(i);
                })
                .show();
    }

    // ---------------------------------------------------------------- default player

    private void updateDefaultBanner(DefaultPlayerHelper.Status status) {
        boolean show = !Prefs.defaultBannerDismissed(this)
                && status.state != DefaultPlayerHelper.State.THIS_APP;
        defaultBanner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void reportDefaultResult(DefaultPlayerHelper.Status status) {
        String msg;
        switch (status.state) {
            case THIS_APP:
                msg = getString(R.string.app_name) + " is your default video player";
                break;
            case OTHER_APP:
                msg = "Default is still " + status.appLabel + ". Clear its defaults first.";
                break;
            default:
                msg = "No default set. Pick " + getString(R.string.app_name) + ", then tap Always.";
                break;
        }
        String mkv = status.mkvNote(this);
        if (mkv != null) msg += " " + mkv;
        Snackbar sb = Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_LONG);
        if (status.state != DefaultPlayerHelper.State.THIS_APP) {
            sb.setAction("Try again", v -> showDefaultPlayerDialog());
        } else if (status.mkvState != DefaultPlayerHelper.State.THIS_APP) {
            sb.setAction("Set for MKV", v -> showDefaultPlayerDialog());
        }
        sb.show();
    }

    void showDefaultPlayerDialog() {
        DefaultPlayerHelper.Status status = DefaultPlayerHelper.check(this);
        String app = getString(R.string.app_name);
        String ownSettingsTip = "\n\nOptional: in " + app + " settings, open “Open by default” > "
                + "“Add links” / supported links.";

        // Android stores "Always" per MIME type: handle MP4 first, then MKV.
        boolean mkvRound = status.state == DefaultPlayerHelper.State.THIS_APP
                && status.mkvState != DefaultPlayerHelper.State.THIS_APP;
        DefaultPlayerHelper.State state = mkvRound ? status.mkvState : status.state;
        String otherPkg = mkvRound ? status.mkvPackage : status.packageName;
        String otherLabel = mkvRound ? status.mkvLabel : status.appLabel;
        String mime = mkvRound ? "video/x-matroska" : "video/mp4";
        String header = status.summary(this) + (mkvRound ? " for MP4 videos.\nMKV files: "
                + (state == DefaultPlayerHelper.State.OTHER_APP ? "default is " + otherLabel : "no default set")
                : "");
        if (!mkvRound && status.mkvNote(this) != null) header += "\n" + status.mkvNote(this);

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.default_dialog_title);
        switch (state) {
            case THIS_APP:
                b.setMessage("✓ " + status.summary(this) + " (MP4 and MKV).\n\nVideos you open from Files "
                        + "and other apps will play here. Note: Google Photos and Files by Google may still "
                        + "play videos in their own built-in viewer. Use their “Open with” option there."
                        + ownSettingsTip)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(app + " settings", (d, w) -> openOwnSettings());
                break;
            case OTHER_APP:
                b.setMessage(header + "\n\nAndroid doesn’t let an app make itself the default. Clear "
                        + otherLabel + "’s default first:\n\n"
                        + "1. Tap “Open " + otherLabel + " settings”\n"
                        + "2. Open by default > Clear defaults\n"
                        + "3. Come back and tap “Set as default player” again, then pick " + app
                        + " and tap Always." + ownSettingsTip)
                        .setPositiveButton("Open " + otherLabel + " settings", (d, w) -> {
                            pendingDefaultCheck = true;
                            if (!DefaultPlayerHelper.openAppDetails(this, otherPkg)) {
                                pendingDefaultCheck = false;
                                Toast.makeText(this, "Couldn’t open settings", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNeutralButton(app + " settings", (d, w) -> openOwnSettings())
                        .setNegativeButton(android.R.string.cancel, null);
                break;
            default:
                b.setMessage(header + "\n\nTap “Choose now” and Android will ask which app should open "
                        + (mkvRound ? "MKV files" : "videos") + ":\n\n"
                        + "1. Pick " + app + "\n"
                        + "2. Tap Always" + ownSettingsTip)
                        .setPositiveButton("Choose now", (d, w) -> launchDefaultChooser(mime))
                        .setNeutralButton(app + " settings", (d, w) -> openOwnSettings())
                        .setNegativeButton(android.R.string.cancel, null);
                break;
        }
        b.show();
    }

    private void openOwnSettings() {
        pendingDefaultCheck = true;
        if (!DefaultPlayerHelper.openAppDetails(this, getPackageName())) {
            pendingDefaultCheck = false;
            Toast.makeText(this, "Couldn’t open settings", Toast.LENGTH_SHORT).show();
        }
    }

    /** Real ACTION_VIEW (no createChooser) so the system dialog offers "Always". */
    private void launchDefaultChooser(String mime) {
        Uri video = allVideos.isEmpty() ? null : allVideos.get(0).uri;
        try {
            Intent i = DefaultPlayerHelper.buildSetupIntent(this, video, mime);
            pendingDefaultCheck = true;
            startActivity(i);
        } catch (ActivityNotFoundException | java.io.IOException | SecurityException e) {
            pendingDefaultCheck = false;
            Toast.makeText(this, "Couldn’t open the app picker: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
