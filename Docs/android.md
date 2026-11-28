# Building and releasing the fork

Fork of [moonlight-stream/moonlight-android](https://github.com/moonlight-stream/moonlight-android)
(remote `upstream`). Our repo: `tonocarvacho/moonlight-android` (remote `origin`).

- Install link (newest build, open on the phone):
  https://github.com/tonocarvacho/moonlight-android/releases/latest/download/moonlight.apk
- App id: `com.tonycgames.moonlight.unofficial`, label **Moonlight Fork**.
  It installs next to the official Moonlight (separate settings and pairings).
- Debug builds: `com.tonycgames.moonlight.debug`, label "Moonlight (Debug)",
  so a dev build and the release can live on the same phone.

Fork features: [hotkey overlay](hotkeys.md) (desktop hotkeys beside the stream, editable in-stream, portrait mode; portrait shows hotkeys, landscape the on-screen controller),
[in-app updates](updates.md) (the app updates itself from GitHub releases;
check-for-updates button in the PC list header),
[stream zoom](zoom.md) (pinch to zoom the stream, two-finger drag to move it).

## Fork changes live in `app/fork.gradle`

`app/build.gradle` stays upstream's file plus one `apply from: "fork.gradle"`
line, so `git merge upstream/master` rarely conflicts. `fork.gradle` sets:
- the application id (upstream asks forks not to ship `com.limelight`),
- release signing from env vars (unsigned when `KEYSTORE_FILE` is unset),
- versionCode = upstream code x 1000 + CI run number (always increasing),
  versionName = `<upstream>-fork.<run>`,
- the release app label.

## CI (`.github/workflows/android.yml`)

On every push to `master` (docs-only changes skipped) or by hand
(Actions → Android → Run workflow):
1. Checkout with submodules (`moonlight-common-c`, `enet`, `nanors`).
2. Java 21, NDK `29.0.14206865` (keep in sync with `ndkVersion`).
3. `./gradlew assembleNonRootRelease`, signed with the release key.
4. Checks the signature, then creates release `v<version>` with
   `moonlight.apk` and `update.json` (for the [in-app updater](updates.md)),
   marked latest. The release notes list the commits since the last release.

| Secret / variable | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` (secret) | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` (secret) | keystore password (= key password, PKCS12) |
| `ANDROID_KEY_ALIAS` (variable) | `moonlight` |

## Signing key

`~/.android-keys/moonlight/` (release.jks, password.txt, README.txt), RSA 4096,
outside the repo (`*.jks` never committed). **Back it up.** Losing it means
the installed app can't be updated: uninstall + reinstall, re-pair the PC.

## Build locally

Needs JDK 17+ (21 used), Android SDK platform 37 and NDK 29.0.14206865
(`local.properties` has `sdk.dir`, git-ignored).

```sh
export JAVA_HOME=~/.jdks/jdk-21.0.13.1+2 ANDROID_HOME=~/Android/Sdk
./gradlew assembleNonRootDebug            # dev build
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
```

Install over USB or Wi-Fi: phone → Developer options → USB debugging /
Wireless debugging (`adb pair <ip:port>` then `adb connect <ip:port>`).
The `root` flavor is for Android ≤ 7.1 only; ignore it.

## Pulling upstream updates

```sh
git fetch upstream && git merge upstream/master
git submodule update --init --recursive
```

Check `ndkVersion` in `app/build.gradle` and update `NDK` in the workflow if it
changed.

## Troubleshooting

| Symptom | Cause → fix |
| --- | --- |
| "App not installed" on update | Signed with another key (e.g. a debug build with the same id) or lower versionCode. |
| PC offline / "failed to connect to 192.168.1.88" on mobile data, works on Wi-Fi | The fork has its own PC list (separate app id); mDNS only saved the LAN address. Add the PC by its Tailscale IP (`tailscale ip -4` on the PC, e.g. `100.64.187.25`) with the **+** button: same UUID, so it is stored as the manual address of the existing PC and polled alongside the LAN one. |
| Android warns about unknown apps | Normal for sideloaded APKs: allow installs from the browser/Files app once. |
| `NDK not configured` / native build fails | Install the NDK version in `ndkVersion` with `sdkmanager`. |
| Empty `moonlight-common-c` folder | Cloned without `--recursive`: `git submodule update --init --recursive`. |
