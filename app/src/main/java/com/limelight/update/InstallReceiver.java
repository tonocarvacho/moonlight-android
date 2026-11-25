package com.limelight.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;

/** Status of an update install session (see Updater.install). */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // Android's "Update this app?" confirmation
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                break; // the app restarts as the new version
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                break; // user said no
            default:
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                LimeLog.warning("Update install failed: " + status + " " + message);
                Toast.makeText(context, context.getString(R.string.update_install_failed,
                        message != null ? message : String.valueOf(status)), Toast.LENGTH_LONG).show();
                break;
        }
    }
}
