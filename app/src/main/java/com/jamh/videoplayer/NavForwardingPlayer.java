package com.jamh.videoplayer;

import androidx.annotation.OptIn;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

/**
 * Wraps the ExoPlayer for the MediaSession so the notification / lock screen / headset
 * next & previous buttons drive PlayerActivity's folder playlist.
 */
@OptIn(markerClass = UnstableApi.class)
final class NavForwardingPlayer extends ForwardingPlayer {

    interface Navigator {
        void next();
        void previous();
    }

    private final Navigator navigator;

    NavForwardingPlayer(Player player, Navigator navigator) {
        super(player);
        this.navigator = navigator;
    }

    @Override
    public Commands getAvailableCommands() {
        return super.getAvailableCommands().buildUpon()
                .addAll(COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD)
                .build();
    }

    @Override
    public boolean isCommandAvailable(int command) {
        switch (command) {
            case COMMAND_SEEK_TO_NEXT:
            case COMMAND_SEEK_TO_NEXT_MEDIA_ITEM:
            case COMMAND_SEEK_TO_PREVIOUS:
            case COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM:
                return true;
            default:
                return super.isCommandAvailable(command);
        }
    }

    @Override public void seekToNext() { navigator.next(); }
    @Override public void seekToNextMediaItem() { navigator.next(); }
    @Override public void seekToPrevious() { navigator.previous(); }
    @Override public void seekToPreviousMediaItem() { navigator.previous(); }
    @Override public boolean hasNextMediaItem() { return true; }
    @Override public boolean hasPreviousMediaItem() { return true; }
}
