#!/usr/bin/env bash
# Usage: ./run-test.sh 91|134
set -euo pipefail
SERIAL="${ADB_SERIAL:-emulator-5554}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="${1:?usage: $0 91|134}"
APKS="$ROOT/apks"

echo "[run-test] serial=$SERIAL version=$VERSION"

case "$VERSION" in
  91)
    adb -s "$SERIAL" install -r -g "$APKS/webview-91.0.4472.120.apk"
    ;;
  134)
    # Trichrome + WebView as separate packages (not install-multiple splits)
    adb -s "$SERIAL" install -r -g "$APKS/trichrome-134.0.6998.40.apk"
    adb -s "$SERIAL" install -r -d -g "$APKS/webview-134.0.6998.40.apk"
    ;;
  *)
    echo "unknown version: $VERSION" >&2
    exit 1
    ;;
esac

adb -s "$SERIAL" shell am force-stop com.google.android.webview
adb -s "$SERIAL" shell am force-stop kr.co.milkt.privilegedhevctester
sleep 1
adb -s "$SERIAL" shell dumpsys webviewupdate | head -15

adb -s "$SERIAL" shell am start -n kr.co.milkt.privilegedhevctester/.MainActivity \
  --es run "$VERSION" --ez skipSetup true

echo "[run-test] launched app run=$VERSION skipSetup=true"
echo "[run-test] watch: adb -s $SERIAL logcat -s HevcTester:I"
