package com.jamh.videoplayer;

import android.content.Context;
import android.view.View;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

/**
 * 1.7.0: the chip row under the tabs: [sort ↓] [Largest first] [Filters · n] then one closable
 * chip per active filter, so what's hiding videos is always visible and one tap away.
 */
final class LibraryBar {

    interface Callbacks {
        void openSheet();

        void onViewChanged(LibraryView v);
    }

    private LibraryBar() {
    }

    static void bind(ChipGroup group, LibraryView v, boolean folders, Callbacks cb) {
        Context ctx = group.getContext();
        group.removeAllViews();

        Chip sort = chip(ctx, v.chipLabel(folders), R.drawable.ic_sort);
        sort.setContentDescription("Sort: " + LibraryView.sortName(v.effectiveSort(), folders) + ", "
                + LibraryView.directionName(v.effectiveSort(), v.effectiveDesc(), folders) + ". Opens sort and filter");
        sort.setOnClickListener(x -> cb.openSheet());
        group.addView(sort);

        Chip storage = chip(ctx, ctx.getString(R.string.lib_storage_title), R.drawable.ic_storage);
        storage.setCheckable(true);
        storage.setChecked(v.storage);
        storage.setChipIconVisible(!v.storage);
        storage.setTag("storage");
        storage.setOnClickListener(x -> cb.onViewChanged(v.withStorage(!v.storage)));
        group.addView(storage);

        if (folders) return;
        int n = v.filterCount();
        Chip filters = chip(ctx, n == 0 ? ctx.getString(R.string.lib_filters)
                : ctx.getString(R.string.lib_filters) + " · " + n, R.drawable.ic_filter);
        filters.setTag("filters");
        filters.setOnClickListener(x -> cb.openSheet());
        group.addView(filters);

        for (int bit : LibraryView.FILTER_BITS) {
            if (!v.has(bit)) continue;
            Chip c = chip(ctx, LibraryView.filterName(bit), 0);
            c.setCheckable(true);
            c.setChecked(true);
            c.setCheckedIconVisible(false);
            c.setCloseIconVisible(true);
            c.setCloseIconContentDescription("Remove filter " + LibraryView.filterName(bit));
            c.setTag(bit);
            View.OnClickListener remove = x -> cb.onViewChanged(v.withFilter(bit, false));
            c.setOnClickListener(remove);
            c.setOnCloseIconClickListener(remove);
            group.addView(c);
        }
    }

    private static Chip chip(Context ctx, String text, int icon) {
        Chip c = new Chip(ctx);
        c.setId(View.generateViewId());
        c.setText(text);
        c.setCheckable(false);
        if (icon != 0) {
            c.setChipIconResource(icon);
            c.setChipIconTintResource(R.color.chip_text);
            c.setChipIconSize(18 * ctx.getResources().getDisplayMetrics().density);
            c.setChipIconVisible(true);
        }
        return c;
    }
}
