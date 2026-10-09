package com.jamh.videoplayer;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import com.google.android.gms.cast.framework.media.CastMediaOptions;
import com.google.android.gms.cast.framework.media.MediaIntentReceiver;
import com.google.android.gms.cast.framework.media.NotificationOptions;

import java.util.Arrays;
import java.util.List;

/**
 * 1.7.0: Cast setup. Google's Default Media Receiver (no receiver app of our own), a media
 * notification with rewind / play-pause / forward / stop, and our expanded controller.
 */
public class CastOptionsProvider implements OptionsProvider {

    @Override
    public CastOptions getCastOptions(Context context) {
        long step = Prefs.skipSeconds(context) * 1000L;
        NotificationOptions notification = new NotificationOptions.Builder()
                .setActions(Arrays.asList(
                        MediaIntentReceiver.ACTION_REWIND,
                        MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                        MediaIntentReceiver.ACTION_FORWARD,
                        MediaIntentReceiver.ACTION_STOP_CASTING), new int[]{1, 3})
                .setSkipStepMs(step)
                .setTargetActivityClassName(CastControllerActivity.class.getName())
                .build();
        CastMediaOptions media = new CastMediaOptions.Builder()
                .setNotificationOptions(notification)
                .setExpandedControllerActivityClassName(CastControllerActivity.class.getName())
                .build();
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .setCastMediaOptions(media)
                .setStopReceiverApplicationWhenEndingSession(true)
                .setResumeSavedSession(true)
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}
