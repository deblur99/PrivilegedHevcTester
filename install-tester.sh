#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}/PrivilegedHevcTester"
ADB_SERIAL="${ADB_SERIAL:-emulator-5554}"
PKG="kr.co.milkt.privilegedhevctester"
APK="${PROJECT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
PRIV_APP_DIR="/system/priv-app/PrivilegedHevcTester"
PRIV_PERM_XML="/system/etc/permissions/privapp-permissions-${PKG}.xml"

cd "${PROJECT_DIR}"
./gradlew assembleDebug

echo "Installing debug APK on ${ADB_SERIAL}…"
adb -s "${ADB_SERIAL}" install -r "${APK}"

echo "Attempting priv-app install (userdebug emulator)…"
if adb -s "${ADB_SERIAL}" root 2>/dev/null; then
  if adb -s "${ADB_SERIAL}" remount 2>/dev/null; then
    adb -s "${ADB_SERIAL}" shell "mkdir -p ${PRIV_APP_DIR}"
    adb -s "${ADB_SERIAL}" push "${APK}" "${PRIV_APP_DIR}/PrivilegedHevcTester.apk"
    adb -s "${ADB_SERIAL}" shell "chmod 644 ${PRIV_APP_DIR}/PrivilegedHevcTester.apk"

    TMP_PERM="$(mktemp)"
    cat >"${TMP_PERM}" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="${PKG}">
        <permission name="android.permission.INSTALL_PACKAGES"/>
    </privapp-permissions>
</permissions>
EOF
    adb -s "${ADB_SERIAL}" push "${TMP_PERM}" "${PRIV_PERM_XML}"
    adb -s "${ADB_SERIAL}" shell "chmod 644 ${PRIV_PERM_XML}"
    rm -f "${TMP_PERM}"

    adb -s "${ADB_SERIAL}" shell "pm install -r -d ${PRIV_APP_DIR}/PrivilegedHevcTester.apk" 2>/dev/null || true
    echo "priv-app push complete; reboot may be required for priv-app to take effect."
  else
    echo "adb remount failed — debug adb install is sufficient (app uses su pm install)."
  fi
else
  echo "adb root failed — debug adb install only."
fi

echo "Done. Launch: adb -s ${ADB_SERIAL} shell am start -n ${PKG}/.MainActivity"