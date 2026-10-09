package com.jamh.videoplayer;

import android.view.Menu;

import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity;

/**
 * 1.7.0: full-screen remote for what's on the TV (opened after a cast starts, from the mini
 * controller and from the notification). Seek, play / pause, ±skip, subtitles, stop casting.
 */
public class CastControllerActivity extends ExpandedControllerActivity {
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.cast_expanded, menu);
        CastSupport.setUpMenuButton(this, menu, R.id.cast_expanded_route);
        return true;
    }
}
