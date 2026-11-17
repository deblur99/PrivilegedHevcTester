# PrivilegedHevcTester

[한국어](README.ko.md)

Smoke-test HEVC and H.264 `<video>` playback on **Android System WebView 91 vs 134** using a small on-device app and a Mac `adb` host-bridge.

## Overview

- On-device app: choose **HEVC** or **H.264**, then tap **Test WebView 91** / **Test WebView 134**.
- Flow: environment setup (install the matching WebView APK via host) → load sample HTML in the system WebView.
- Same WebView major version already installed → skip host install and load HTML immediately.
- Version switch → Mac `host-bridge.py` runs `adb install`; the app then **restarts itself** so the new WebView provider is loaded (no manual force-stop).

## Requirements

- macOS with `adb` and Python 3
- Android emulator: API **29**, **arm64**, **userdebug** (e.g. `emulator-5554`)
- WebView APKs (not in git — place under `apks/`):
  - `webview-91.0.4472.120.apk`
  - `webview-134.0.6998.40.apk`
  - `trichrome-134.0.6998.40.apk` (required with 134)

Obtain APKs from [APKMirror — Android System WebView](https://www.apkmirror.com/apk/google-inc/android-system-webview/) (arm64, Android 10+ where applicable).

## Layout

| Path | Role |
|------|------|
| `PrivilegedHevcTester/` | Android Gradle app |
| `host-bridge.py` | Polls the app’s `files/cmd` and runs `adb install` |
| `start-host-bridge.sh` | Keep host-bridge running |
| `run-test.sh` | Host-side install + launch with `skipSetup` |
| `install-tester.sh` | Build/install the tester APK |
| `assets/` | Shared HTML + sample mp4s |
| `apks/` | Local WebView APKs (gitignored) |

## Setup

```bash
git clone https://github.com/deblur99/PrivilegedHevcTester.git
cd PrivilegedHevcTester

# Put WebView APKs into apks/ (see filenames above)

./start-host-bridge.sh

cd PrivilegedHevcTester
./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n kr.co.milkt.privilegedhevctester/.MainActivity
```

Or from the repo root: `./install-tester.sh`.

## Usage

1. Leave `./start-host-bridge.sh` running on the Mac when switching WebView versions.
2. In the app, select **HEVC** or **H.264**.
3. Tap **Test WebView 91** or **Test WebView 134**.
4. Watch the progress row and log; after a version change the app relaunches and continues the smoke load.

Host-only alternative (no button handshake):

```bash
./run-test.sh 91
./run-test.sh 134
```

## Notes

- **Host-bridge is required only to change** the system WebView package. Same-version smoke does not need it.
- Sample media is staged under the app’s private `files/` directory so WebView is not blocked by `/sdcard` `660` permissions.
- On WebView **91**, HEVC may report playback events with **video size 0×0** (no picture); **H.264** typically shows frames. WebView **134** can show HEVC frames on the emulator used in development.
- Do not open the HTML in Chrome — that uses Chrome’s own engine, not `com.google.android.webview`.

## Disclaimer

This is a **test harness**. WebView / Trichrome APKs are Google-signed packages; download them yourself and respect APKMirror / Google terms. Not affiliated with Google.
