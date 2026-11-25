package com.limelight.update;

import android.app.Activity;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import com.limelight.R;

/** Settings row: shows the installed version, tap to check for an update. */
public class CheckUpdatesPreference extends Preference {
    public CheckUpdatesPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setSummary(context.getString(R.string.summary_check_updates, Updater.installedVersionName(context)));
    }

    @Override
    protected void onClick() {
        if (getContext() instanceof Activity) {
            Updater.checkNow((Activity) getContext());
        }
    }
}
