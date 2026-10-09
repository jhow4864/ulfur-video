package com.jamh.videoplayer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;

/**
 * 1.4.1: "Media management apps" special access (android.permission.MANAGE_MEDIA, Android 12+). When the user
 * allows it, MediaStore delete requests for the originals are approved without the system's
 * "Allow Ulfur Video to delete N videos?" dialog. Android 11 and older have no equivalent.
 */
final class MediaAccess {
    private MediaAccess() {
    }

    /** True on Android 12+ (the only versions where the special access exists). */
    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 31;
    }

    /** Tests only: forces the answer of {@link #canManageMedia}. */
    static Boolean overrideForTest;

    static boolean canManageMedia(Context c) {
        if (!isSupported()) return false;
        if (overrideForTest != null) return overrideForTest;
        try {
            return MediaStore.canManageMedia(c);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Settings › Apps › Special app access › Media management apps › Ulfur Video. */
    static Intent settingsIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA);
        i.setData(Uri.fromParts("package", c.getPackageName(), null));
        return i;
    }

    /** The generic Special app access screen, for phones whose Settings app lacks the direct page. */
    static Intent fallbackIntent(Context c) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", c.getPackageName(), null));
    }
}
