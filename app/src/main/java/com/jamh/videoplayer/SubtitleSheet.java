package com.jamh.videoplayer;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTrackNameProvider;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/** 1.6: the player's subtitles sheet (replaces the plain track list for subtitles). */
@OptIn(markerClass = UnstableApi.class)
final class SubtitleSheet {

    interface Host {
        @Nullable Player player();

        void setSubtitlesOn(boolean on);

        void setSubtitleSize(int index);

        void pickSubtitleFile();

        @Nullable String externalSubtitleName();
    }

    static final class Track {
        final TrackGroup group;
        final int index;
        final String label;
        final boolean selected;

        Track(TrackGroup group, int index, String label, boolean selected) {
            this.group = group;
            this.index = index;
            this.label = label;
            this.selected = selected;
        }
    }

    private SubtitleSheet() {
    }

    static List<Track> textTracks(@Nullable Player p, DefaultTrackNameProvider names) {
        List<Track> out = new ArrayList<>();
        if (p == null) return out;
        boolean disabled = p.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i, true)) continue;
                Format f = g.getTrackFormat(i);
                out.add(new Track(g.getMediaTrackGroup(), i, names.getTrackName(f), !disabled && g.isTrackSelected(i)));
            }
        }
        return out;
    }

    static void bind(View v, @Nullable Dialog dialog, Host host) {
        android.content.Context ctx = v.getContext();
        Player p = host.player();
        List<Track> tracks = textTracks(p, new DefaultTrackNameProvider(ctx.getResources()));
        boolean on = PlaybackMemory.subtitlesOn(ctx);

        MaterialSwitch sw = v.findViewById(R.id.switch_subs_on);
        TextView onValue = v.findViewById(R.id.subs_on_value);
        sw.setChecked(on);
        String selected = null;
        for (Track t : tracks) if (t.selected) selected = t.label;
        if (tracks.isEmpty()) onValue.setText("No subtitles in this video yet. Load a file below");
        else if (!on) onValue.setText("Off · " + tracks.size() + (tracks.size() == 1 ? " track" : " tracks"));
        else onValue.setText(selected != null ? selected : "On · no track selected");
        sw.setOnCheckedChangeListener((b, checked) -> {
            host.setSubtitlesOn(checked);
            if (dialog != null) dialog.dismiss();
        });
        v.findViewById(R.id.row_subs_on).setOnClickListener(x -> sw.toggle());

        // Track rows (only when there is a choice to make).
        LinearLayout box = v.findViewById(R.id.subs_tracks);
        box.removeAllViews();
        if (tracks.size() > 1 || (tracks.size() == 1 && !tracks.get(0).selected && on)) {
            for (Track t : tracks) box.addView(trackRow(ctx, t, on, x -> {
                if (p != null) {
                    p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(t.group, t.index))
                            .build());
                }
                host.setSubtitlesOn(true);
                if (dialog != null) dialog.dismiss();
            }));
        }

        // Size chips.
        ChipGroup group = v.findViewById(R.id.subs_size_group);
        group.removeAllViews();
        int size = PlaybackMemory.subtitleSize(ctx);
        for (int i = 0; i < PlaybackMemory.SUB_SIZE_LABELS.length; i++) {
            Chip c = new Chip(ctx);
            c.setId(View.generateViewId());
            c.setText(PlaybackMemory.SUB_SIZE_LABELS[i]);
            c.setCheckable(true);
            c.setChecked(i == size);
            c.setTag(i);
            group.addView(c);
        }
        group.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            View c = g.findViewById(ids.get(0));
            if (c != null && c.getTag() instanceof Integer) host.setSubtitleSize((Integer) c.getTag());
        });

        String ext = host.externalSubtitleName();
        if (ext != null) ((TextView) v.findViewById(R.id.subs_load_value)).setText("Playing " + ext);
        v.findViewById(R.id.row_subs_load).setOnClickListener(x -> {
            if (dialog != null) dialog.dismiss();
            host.pickSubtitleFile();
        });

        MaterialSwitch swAuto = v.findViewById(R.id.switch_subs_auto);
        swAuto.setChecked(PlaybackMemory.autoLoadSubtitles(ctx));
        swAuto.setOnCheckedChangeListener((b, checked) -> PlaybackMemory.setAutoLoadSubtitles(ctx, checked));
        v.findViewById(R.id.row_subs_auto).setOnClickListener(x -> swAuto.toggle());
    }

    private static View trackRow(android.content.Context ctx, Track t, boolean on, View.OnClickListener click) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx, null, 0, R.style.SubsTrackRow);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(ctx, null, 0, R.style.MenuRowTitle);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tv.setText(t.label);
        boolean sel = t.selected && on;
        tv.setTextColor(ContextCompat.getColor(ctx, sel ? R.color.accent_text : R.color.text_primary));
        row.addView(tv);
        ImageView check = new ImageView(ctx);
        check.setImageResource(R.drawable.ic_check);
        check.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent)));
        check.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams((int) (22 * d), (int) (22 * d)));
        row.setOnClickListener(click);
        row.setContentDescription(t.label + (sel ? ", selected" : ""));
        return row;
    }
}
