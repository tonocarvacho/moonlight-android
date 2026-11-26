# In-app updates

Release builds update themselves from this repo's GitHub releases. You
don't need to uninstall or download anything in the browser, and settings
and paired PCs are kept. The update is an in-place install signed with the
same key.

## For the user

- When the PC list or app list opens, it checks for a newer build, at most
  every 10 minutes after a successful check. A failed check (no network
  yet) doesn't count, so the next screen tries again. If there is a newer
  build, it shows **Update available** with what changed.
- **Check for updates button** (download icon in the PC list header: top
  right in portrait, under ＋ in landscape): checks right now, even after
  *Later*. It turns **green** while a newer version is known.
  **Update** downloads it with a progress bar. Android then asks
  *"Do you want to update this app?"*: tap **Update**.
- The first time, Android asks you to allow "Install unknown apps" for
  Moonlight Fork. The app opens that setting; turn it on, come back, and the
  install continues.
- **Later** hides that version for 12 hours.
- Settings → **Updates**: *Check for updates* (shows the installed
  version) and *Check when the app opens* (on by default).
- After the update, Android closes the app; open it again.

Installing `moonlight.apk` by hand over the existing app also keeps pairing.
Only uninstalling loses it.

## How it works

1. CI (`.github/workflows/android.yml`) builds the signed APK and publishes
   release `v<versionName>` with two assets:
   - `moonlight.apk`
   - `update.json`:
     `{versionCode, versionName, apk, sha256, notes}`.
     `notes` are the commit subjects since the previous release tag.
2. The app reads
   `https://github.com/tonocarvacho/moonlight-android/releases/latest/download/update.json`
   (`BuildConfig.UPDATE_URL`, set for release builds in `app/fork.gradle`;
   empty in debug builds, so they never update).
3. If `versionCode` is higher than the installed one, it offers the update,
   downloads the APK to the cache, checks its SHA-256, and installs it with a
   `PackageInstaller` session. Android itself refuses an APK signed with
   another key.

## Code

- `app/src/main/java/com/limelight/update/`
  - `Updater`: check, dialog, download, install.
  - `InstallReceiver`: install session status. It starts Android's
    confirmation screen and reports failures.
  - `CheckUpdatesPreference`: the settings row.
  - `Updater.setupButton()`: the header button. A successful check stores
    `last_check` and `latest_code` in the `updater` preferences; the button
    is green while `latest_code` is above the installed versionCode.
- `app/src/release/AndroidManifest.xml`: `REQUEST_INSTALL_PACKAGES` and the
  receiver (release builds only).
- Upstream files touched: `PcView.onResume()` and `AppView.onResume()` call
  `Updater.checkOnLaunch(this)`. Coming back from a stream lands on the app
  list, so PcView alone missed new releases. `PcView.onCreate()` calls
  `Updater.setupButton()`. The `checkUpdatesButton` is in both
  `activity_pc_view.xml` layouts (icon `drawable/ic_check_updates.xml`).
  `res/xml/preferences.xml` has the Updates category. Strings are in `res/values/strings_fork.xml`.

## Testing an update locally

Build a signed release with a low build number, install it on an emulator
or the phone, and open the app. It offers the latest GitHub release:

```sh
KEYSTORE_FILE=~/.android-keys/moonlight/release.jks \
KEYSTORE_PASSWORD="$(cat ~/.android-keys/moonlight/password.txt)" \
KEY_ALIAS=moonlight APP_VERSION_CODE=1 ./gradlew assembleNonRootRelease
adb install -r app/build/outputs/apk/nonRoot/release/app-nonRoot-release.apk
```
