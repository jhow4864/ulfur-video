package com.jamh.videoplayer;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 1.7.0: "Sort &amp; filter" bottom sheet. Changes apply live (the list behind updates and the
 * header shows how many videos match); the host remembers the view for its screen.
 */
final class LibrarySheet {

    interface Host {
        void onViewChanged(LibraryView v);

        /** e.g. "8 of 40 videos" for the given view. */
        String summary(LibraryView v);
    }

    static final int[] VIDEO_SORTS = {LibraryView.DATE, LibraryView.NAME, LibraryView.LENGTH,
            LibraryView.SIZE, LibraryView.RESOLUTION, LibraryView.WATCHED};
    static final int[] FOLDER_SORTS = {LibraryView.DATE, LibraryView.NAME, LibraryView.SIZE, LibraryView.LENGTH};

    private LibrarySheet() {
    }

    static BottomSheetDialog show(Activity a, LibraryView initial, boolean folders, Host host) {
        BottomSheetDialog sheet = new BottomSheetDialog(a);
        View v = LayoutInflater.from(a).inflate(R.layout.sheet_library, null);
        sheet.setContentView(v);
        bind(v, initial, folders, host);
        sheet.setOnShowListener(d -> {
            BottomSheetBehavior<?> b = sheet.getBehavior();
            b.setState(BottomSheetBehavior.STATE_EXPANDED);
            b.setSkipCollapsed(true);
        });
        sheet.show();
        return sheet;
    }

    @VisibleForTesting
    static void bind(View root, LibraryView initial, boolean folders, Host host) {
        Context ctx = root.getContext();
        final LibraryView[] cur = {initial};
        final boolean[] updating = {false};
        ChipGroup sortGroup = root.findViewById(R.id.lib_sort);
        MaterialButtonToggleGroup dir = root.findViewById(R.id.lib_dir);
        MaterialButton desc = root.findViewById(R.id.lib_dir_desc), asc = root.findViewById(R.id.lib_dir_asc);
        MaterialSwitch storage = root.findViewById(R.id.lib_storage);
        TextView count = root.findViewById(R.id.lib_count);
        root.findViewById(R.id.lib_filters).setVisibility(folders ? View.GONE : View.VISIBLE);
        if (folders) {
            ((TextView) root.findViewById(R.id.lib_storage_subtitle)).setText(
                    "Storage view: biggest folders on top with their total size");
        }

        int[] keys = folders ? FOLDER_SORTS : VIDEO_SORTS;
        Chip[] sortChips = new Chip[keys.length];
        for (int i = 0; i < keys.length; i++) {
            Chip c = new Chip(ctx);
            c.setId(View.generateViewId());
            c.setText(LibraryView.sortName(keys[i], folders));
            c.setCheckable(true);
            c.setTag(keys[i]);
            sortGroup.addView(c);
            sortChips[i] = c;
        }
        int[][] groups = {
                {LibraryView.F_SHORT, LibraryView.F_MEDIUM, LibraryView.F_LONG},
                {LibraryView.F_SD, LibraryView.F_HD, LibraryView.F_FHD, LibraryView.F_4K},
                {LibraryView.F_LANDSCAPE, LibraryView.F_PORTRAIT},
                {LibraryView.F_UNWATCHED, LibraryView.F_IN_PROGRESS}};
        int[] groupIds = {R.id.lib_f_duration, R.id.lib_f_res, R.id.lib_f_orient, R.id.lib_f_watch};
        java.util.List<Chip> filterChips = new java.util.ArrayList<>();
        for (int g = 0; g < groups.length; g++) {
            ChipGroup cg = root.findViewById(groupIds[g]);
            for (int bit : groups[g]) {
                Chip c = new Chip(ctx);
                c.setId(View.generateViewId());
                String hint = LibraryView.filterHint(bit);
                c.setText(hint == null || bit == LibraryView.F_4K ? LibraryView.filterName(bit)
                        : LibraryView.filterName(bit) + " · " + hint);
                c.setCheckable(true);
                c.setTag(bit);
                cg.addView(c);
                filterChips.add(c);
            }
        }

        Runnable refresh = () -> {
            updating[0] = true;
            LibraryView v = cur[0];
            int key = v.effectiveSort();
            for (Chip c : sortChips) c.setChecked((Integer) c.getTag() == key);
            desc.setText(LibraryView.directionName(key, true, folders));
            asc.setText(LibraryView.directionName(key, false, folders));
            dir.check(v.effectiveDesc() ? R.id.lib_dir_desc : R.id.lib_dir_asc);
            storage.setChecked(v.storage);
            for (Chip c : filterChips) c.setChecked(v.has((Integer) c.getTag()));
            count.setText(host.summary(v));
            updating[0] = false;
        };
        java.util.function.Consumer<LibraryView> change = v -> {
            if (v.equals(cur[0])) return;
            cur[0] = v;
            host.onViewChanged(v);
            refresh.run();
        };

        for (Chip c : sortChips) {
            c.setOnClickListener(x -> {
                if (updating[0]) return;
                int key = (Integer) c.getTag();
                LibraryView v = cur[0];
                // Leaving the storage view keeps the chosen key's natural direction.
                change.accept(v.storage || key != v.sort ? v.withStorage(false).withSort(key)
                        .withDesc(LibraryView.defaultDesc(key)) : v);
            });
        }
        dir.addOnButtonCheckedListener((group, id, checked) -> {
            if (updating[0] || !checked) return;
            LibraryView v = cur[0];
            boolean d = id == R.id.lib_dir_desc;
            if (v.storage) {
                // Size, ascending: leave the storage view for a plain size sort.
                change.accept(new LibraryView(LibraryView.SIZE, d, false, v.filters));
            } else {
                change.accept(v.withDesc(d));
            }
        });
        storage.setOnCheckedChangeListener((b, on) -> {
            if (!updating[0]) change.accept(cur[0].withStorage(on));
        });
        root.findViewById(R.id.lib_row_storage).setOnClickListener(x -> storage.toggle());
        for (Chip c : filterChips) {
            c.setOnCheckedChangeListener((b, on) -> {
                if (!updating[0]) change.accept(cur[0].withFilter((Integer) c.getTag(), on));
            });
        }
        root.findViewById(R.id.lib_reset).setOnClickListener(x -> change.accept(LibraryView.defaults()));
        refresh.run();
    }
}
