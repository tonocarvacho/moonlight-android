package com.limelight.hotkeys;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Fork (Docs/hotkeys.md): upstream's "Show on-screen controls" setting, which
 * the hotkey editor can switch during a stream. With the hotkey overlay on,
 * the controller is only shown in landscape and the hotkeys only in portrait;
 * with the controller off the hotkeys show in both.
 */
public final class LandscapeController {
    /** Upstream's key (PreferenceConfiguration), so Settings and the editor stay in sync. */
    public static final String PREF = "checkbox_show_onscreen_controls";
    private static final String DEFAULT_APPLIED = "fork_osc_default_on";

    private LandscapeController() {
    }

    /** Once per install: the fork's default is on (upstream's is off). */
    public static void applyDefault(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(DEFAULT_APPLIED, false)) {
            prefs.edit().putBoolean(PREF, true).putBoolean(DEFAULT_APPLIED, true).apply();
        }
    }

    static boolean isOn(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF, true);
    }

    static void set(Context context, boolean on) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF, on).apply();
    }
}
