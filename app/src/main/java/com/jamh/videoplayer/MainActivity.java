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
import java.util.List;
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
    /** Set when we sent the user to Settings or the "Open with" dialog; re-checked in onResume. */
    private boolean pendingDefaultCheck;

    private final MoveToPrivateFlow moveToPrivate = new MoveToPrivateFlow(this, this::refresh);

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
        toolbar = findViewById(R.id.toolbar);
        tabs = findViewById(R.id.tabs);
        recycler = findViewById(R.id.recycler);
        emptyPanel = findViewById(R.id.empty_panel);
        emptyText = findViewById(R.id.empty_text);
        subtitle = findViewById(R.id.subtitle);
        permissionButton = findViewById(R.id.btn_permission);
        defaultBanner = findViewById(R.id.default_banner);
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

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, 0);
            recycler.setPadding(recycler.getPaddingLeft(), recycler.getPaddingTop(), recycler.getPaddingRight(),
                    i.bottom + dp(24));
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

        permissionButton.setOnClickListener(v -> requestOrOpenSettings());
        toolbar.setNavigationOnClickListener(v -> closeFolder());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (searchItem != null && searchItem.isActionViewExpanded()) {
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
        int filter = Prefs.clipsFilter(this);
        List<VideoItem> clips = new ArrayList<>();
        for (VideoItem v : allVideos) if (v.matchesClipFilter(filter)) clips.add(v);
        if (clips.isEmpty()) {
            Toast.makeText(this, "No clips yet: portrait or short videos show up here", Toast.LENGTH_LONG).show();
            return;
        }
        Sort.videos(clips, sort);
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        DefaultPlayerHelper.Status status = DefaultPlayerHelper.check(this);
        updateDefaultBanner(status);
        if (pendingDefaultCheck) {
            pendingDefaultCheck = false;
            reportDefaultResult(status);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
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
        toolbar.setNavigationIcon(inFolder ? ContextCompat.getDrawable(this, R.drawable.ic_arrow_back) : null);
        toolbar.setTitle(inFolder ? openFolderName : getString(R.string.app_name));
        String q = query.trim().toLowerCase(Locale.ROOT);

        List<Object> data = new ArrayList<>();
        String sub;
        findViewById(R.id.clips_filter_bar).setVisibility(tab == TAB_CLIPS && !inFolder ? View.VISIBLE : View.GONE);
        adapter.setClipsMode(tab == TAB_CLIPS && !inFolder);
        if (tab == TAB_CLIPS && !inFolder) {
            int filter = Prefs.clipsFilter(this);
            List<VideoItem> clips = new ArrayList<>();
            for (VideoItem v : allVideos) {
                if (!v.matchesClipFilter(filter)) continue;
                if (!q.isEmpty() && !v.title.toLowerCase(Locale.ROOT).contains(q)) continue;
                clips.add(v);
            }
            Sort.videos(clips, sort);
            data.addAll(clips);
            sub = clips.size() + (clips.size() == 1 ? " clip" : " clips") + "  ·  tap to open the swipe feed";
        } else if (tab == TAB_VIDEOS || inFolder) {
            List<VideoItem> vids = new ArrayList<>();
            for (VideoItem v : allVideos) {
                if (inFolder && !v.bucketId.equals(openFolderId)) continue;
                if (!q.isEmpty() && !v.title.toLowerCase(Locale.ROOT).contains(q)) continue;
                vids.add(v);
            }
            Sort.videos(vids, sort);
            data.addAll(vids);
            sub = vids.size() + (vids.size() == 1 ? " video" : " videos");
        } else {
            List<Folder> folders = VideoRepository.folders(allVideos);
            List<Folder> filtered = new ArrayList<>();
            for (Folder f : folders) {
                if (q.isEmpty() || f.name.toLowerCase(Locale.ROOT).contains(q)) filtered.add(f);
            }
            Sort.folders(filtered, sort);
            data.addAll(filtered);
            sub = filtered.size() + (filtered.size() == 1 ? " folder" : " folders");
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
            showEmpty(q.isEmpty() ? getString(R.string.no_videos) : "No results for \"" + query + "\"", false);
        } else {
            emptyPanel.setVisibility(View.GONE);
            recycler.setVisibility(View.VISIBLE);
        }
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

    // ---------------------------------------------------------------- clicks

    @Override
    public void onVideoClick(VideoItem item) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.setData(item.uri);
        i.putExtra(PlayerActivity.EXTRA_VIDEO_ID, item.id);
        i.putExtra(PlayerActivity.EXTRA_BUCKET_ID, item.bucketId);
        i.putExtra(PlayerActivity.EXTRA_SORT, sort);
        startActivity(i);
    }

    @Override
    public void onVideoLongClick(VideoItem item, View anchor) {
        if (item.id < 0) return;
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, R.string.move_to_private);
        pm.setOnMenuItemClickListener(mi -> {
            moveToPrivate.start(item);
            return true;
        });
        pm.show();
    }

    @Override
    public void onClipClick(VideoItem clip) {
        List<VideoItem> clips = new ArrayList<>();
        int filter = Prefs.clipsFilter(this);
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (VideoItem v : allVideos) {
            if (v.matchesClipFilter(filter) && (q.isEmpty() || v.title.toLowerCase(Locale.ROOT).contains(q))) clips.add(v);
        }
        Sort.videos(clips, sort);
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
        getMenuInflater().inflate(R.menu.main, menu);
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
        viewMode.setIcon(grid ? R.drawable.ic_list : R.drawable.ic_grid);
        viewMode.setTitle(grid ? "List view" : "Grid view");
        int[] ids = {R.id.sort_date, R.id.sort_name, R.id.sort_size, R.id.sort_duration};
        menu.findItem(ids[Math.max(0, Math.min(3, sort))]).setChecked(true);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateMenuState(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_view_mode) {
            grid = !grid;
            Prefs.setGrid(this, grid);
            applyLayoutManager();
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.sort_date || id == R.id.sort_name || id == R.id.sort_size || id == R.id.sort_duration) {
            sort = id == R.id.sort_name ? Sort.NAME : id == R.id.sort_size ? Sort.SIZE
                    : id == R.id.sort_duration ? Sort.DURATION : Sort.DATE;
            Prefs.setSort(this, sort);
            item.setChecked(true);
            render();
            return true;
        } else if (id == R.id.action_private) {
            startActivity(new Intent(this, VaultActivity.class));
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
                msg = "No default set. Pick Video Player, then tap Always.";
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
