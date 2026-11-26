package com.limelight.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * In-app updates for the fork (Docs/updates.md). CI publishes update.json
 * next to moonlight.apk on the latest GitHub release; this checks it,
 * downloads the APK, verifies its SHA-256 and installs it over the running
 * app with PackageInstaller, so settings and PC pairings are kept.
 *
 * Only release builds have an update URL; debug builds never update.
 */
public final class Updater {
    public static final String PREF_AUTO_CHECK = "checkbox_auto_update_check";
    private static final String STATE_PREFS = "updater";
    // Only successful checks count, so a launch without network (or before CI
    // has published the release) doesn't hide a new version for long.
    private static final long AUTO_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long LATER_SNOOZE_MS = TimeUnit.HOURS.toMillis(12);

    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final int UPDATE_AVAILABLE_COLOR = 0xFF50FA7B;

    private static boolean busy;

    private Updater() {
    }

    /** Info from update.json. */
    static final class Release {
        final long versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final String notes;

        Release(JSONObject json) throws JSONException {
            versionCode = json.getLong("versionCode");
            versionName = json.getString("versionName");
            apkUrl = json.getString("apk");
            sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
            notes = json.optString("notes", "");
        }
    }

    public static boolean isEnabled() {
        return !BuildConfig.UPDATE_URL.isEmpty();
    }

    /**
     * PC list / app list shown: quiet check, at most every 10 minutes after
     * a successful one, unless turned off in settings.
     */
    public static void checkOnLaunch(Activity activity) {
        if (!isEnabled() || !PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_AUTO_CHECK, true)) {
            return;
        }
        long lastCheck = activity.getSharedPreferences(STATE_PREFS, 0).getLong("last_check", 0);
        if (System.currentTimeMillis() - lastCheck < AUTO_CHECK_INTERVAL_MS) {
            return;
        }
        check(activity, false);
    }

    /**
     * The "check for updates" button in the PC list header: checks now, and
     * turns green while a newer version is known.
     */
    public static void setupButton(Activity activity, ImageButton button) {
        button.setOnClickListener(v -> checkNow(activity));
        showAvailable(activity, button);
    }

    private static void showAvailable(Activity activity, ImageButton button) {
        boolean available = isEnabled() &&
                activity.getSharedPreferences(STATE_PREFS, 0).getLong("latest_code", -1) > installedVersionCode(activity);
        if (available) {
            button.setColorFilter(UPDATE_AVAILABLE_COLOR);
        }
        else {
            button.clearColorFilter();
        }
        button.setContentDescription(activity.getString(available ?
                R.string.update_available_title : R.string.title_check_updates));
    }

    private static void showAvailable(Activity activity) {
        View button = activity.findViewById(R.id.checkUpdatesButton);
        if (button instanceof ImageButton) {
            showAvailable(activity, (ImageButton) button);
        }
    }

    /** "Check for updates" in settings: always checks and reports the result. */
    public static void checkNow(Activity activity) {
        if (!isEnabled()) {
            Toast.makeText(activity, R.string.update_disabled_debug, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        check(activity, true);
    }

    public static String installedVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    private static long installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void check(Activity activity, boolean manual) {
        if (busy) {
            return;
        }
        busy = true;
        new Thread(() -> {
            Release release = null;
            String error = null;
            try {
                release = fetchRelease();
            } catch (IOException | JSONException e) {
                error = e.getMessage();
                LimeLog.warning("Update check failed: " + e);
            }
            final Release r = release;
            final String err = error;
            activity.runOnUiThread(() -> {
                busy = false;
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (r == null) {
                    if (manual) {
                        Toast.makeText(activity, activity.getString(R.string.update_check_failed, err), Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                SharedPreferences state = activity.getSharedPreferences(STATE_PREFS, 0);
                state.edit().putLong("last_check", System.currentTimeMillis())
                        .putLong("latest_code", r.versionCode).apply();
                showAvailable(activity);
                if (r.versionCode <= installedVersionCode(activity)) {
                    if (manual) {
                        Toast.makeText(activity, activity.getString(R.string.update_up_to_date,
                                installedVersionName(activity)), Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                if (!manual && state.getLong("snooze_code", -1) == r.versionCode &&
                        System.currentTimeMillis() < state.getLong("snooze_until", 0)) {
                    return;
                }
                offer(activity, r);
            });
        }, "UpdateCheck").start();
    }

    private static Release fetchRelease() throws IOException, JSONException {
        Request request = new Request.Builder().url(BuildConfig.UPDATE_URL)
                .header("Cache-Control", "no-cache").build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            return new Release(new JSONObject(body.string()));
        }
    }

    private static void offer(Activity activity, Release r) {
        StringBuilder message = new StringBuilder(activity.getString(R.string.update_available_message,
                r.versionName, installedVersionName(activity)));
        if (!r.notes.isEmpty()) {
            message.append("\n\n").append(r.notes);
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_install, (d, w) -> download(activity, r))
                .setNegativeButton(R.string.update_later, (d, w) -> activity.getSharedPreferences(STATE_PREFS, 0).edit()
                        .putLong("snooze_code", r.versionCode)
                        .putLong("snooze_until", System.currentTimeMillis() + LATER_SNOOZE_MS)
                        .apply())
                .show();
    }

    private static void download(Activity activity, Release r) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * dp);
        layout.setPadding(pad, Math.round(16 * dp), pad, 0);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView text = new TextView(activity);
        text.setGravity(Gravity.END);
        layout.addView(bar);
        layout.addView(text);

        final Call[] call = new Call[1];
        final boolean[] cancelled = {false};
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_downloading, r.versionName))
                .setView(layout)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    cancelled[0] = true;
                    if (call[0] != null) {
                        call[0].cancel();
                    }
                })
                .show();

        File apk = new File(new File(activity.getCacheDir(), "update"), "moonlight.apk");
        new Thread(() -> {
            String error = null;
            try {
                File dir = apk.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("can't create " + dir);
                }
                call[0] = http.newCall(new Request.Builder().url(r.apkUrl).build());
                try (Response response = call[0].execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("HTTP " + response.code());
                    }
                    ResponseBody body = response.body();
                    long total = body.contentLength();
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(apk)) {
                        byte[] buf = new byte[64 * 1024];
                        long done = 0;
                        int lastPercent = -1;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            sha.update(buf, 0, n);
                            done += n;
                            int percent = total > 0 ? (int) (done * 100 / total) : 0;
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                final String mb = String.format(Locale.ROOT, "%.1f / %.1f MB", done / 1e6, total / 1e6);
                                activity.runOnUiThread(() -> {
                                    bar.setProgress(percent);
                                    text.setText(mb);
                                });
                            }
                        }
                    }
                    if (!toHex(sha.digest()).equals(r.sha256)) {
                        throw new IOException(activity.getString(R.string.update_bad_checksum));
                    }
                }
            } catch (IOException | NoSuchAlgorithmException e) {
                error = e.getMessage();
                LimeLog.warning("Update download failed: " + e);
            }
            final String err = error;
            activity.runOnUiThread(() -> {
                dialog.dismiss();
                if (cancelled[0] || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (err != null) {
                    Toast.makeText(activity, activity.getString(R.string.update_download_failed, err), Toast.LENGTH_LONG).show();
                    return;
                }
                install(activity, apk);
            });
        }, "UpdateDownload").start();
    }

    /** Continues the install once the user is back from the "Install unknown apps" screen. */
    private static void installWhenResumed(Activity activity, File apk) {
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity resumed) {
                if (resumed != activity) {
                    return;
                }
                activity.getApplication().unregisterActivityLifecycleCallbacks(this);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        activity.getPackageManager().canRequestPackageInstalls()) {
                    install(activity, apk);
                }
            }

            @Override
            public void onActivityDestroyed(Activity destroyed) {
                if (destroyed == activity) {
                    activity.getApplication().unregisterActivityLifecycleCallbacks(this);
                }
            }

            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        });
    }

    /** Hands the APK to Android's installer; InstallReceiver shows its confirmation. */
    static void install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            // One-time: let this app install updates ("Install unknown apps")
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_message)
                    .setPositiveButton(R.string.update_open_settings, (d, w) -> {
                        installWhenResumed(activity, apk);
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName())));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Self-updates may skip the confirmation where Android allows it
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }

        try {
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    session.fsync(out);
                }
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_MUTABLE; // the installer adds its status extras
                }
                PendingIntent status = PendingIntent.getBroadcast(activity, sessionId,
                        new Intent(activity, InstallReceiver.class), flags);
                session.commit(status.getIntentSender());
            }
            Toast.makeText(activity, R.string.update_installing, Toast.LENGTH_SHORT).show();
        } catch (IOException | RuntimeException e) {
            LimeLog.warning("Update install failed: " + e);
            Toast.makeText(activity, activity.getString(R.string.update_install_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
