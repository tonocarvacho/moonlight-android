package com.limelight.hotkeys;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.preference.PreferenceManager;

/**
 * Screen rotation while streaming (fork, Docs/hotkeys.md). Upstream always
 * locks phones to landscape; with the hotkey overlay on, portrait is allowed
 * too: the stream goes in one half of the screen and the keys in the other.
 */
public final class StreamRotation {
    public static final String PREF = "list_stream_rotation";
    private static final String LANDSCAPE = "landscape";
    private static final String FOLLOW_PHONE = "auto";
    private static final String SENSOR = "sensor";

    private StreamRotation() {
    }

    /**
     * The orientation Game should request on a regular (non-square) screen.
     * The on-screen controller doesn't support portrait; with the overlay on
     * it is hidden there and the hotkeys show instead (Game, LandscapeController).
     */
    public static int requested(Context context) {
        String mode = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF, SENSOR);
        if (!HotkeyOverlay.isEnabled(context) || LANDSCAPE.equals(mode)) {
            return ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
        }
        // USER honours the phone's auto-rotate switch; SENSOR turns with the phone regardless
        return FOLLOW_PHONE.equals(mode) ? ActivityInfo.SCREEN_ORIENTATION_USER : ActivityInfo.SCREEN_ORIENTATION_SENSOR;
    }
}
