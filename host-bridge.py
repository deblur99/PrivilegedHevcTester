#!/usr/bin/env python3
"""Poll app files/cmd on emulator and run adb WebView installs for button tests."""
from __future__ import annotations

import os
import subprocess
import tempfile
import time

SERIAL = os.environ.get("ADB_SERIAL", "emulator-5554")
ROOT = os.path.dirname(os.path.abspath(__file__))
APKS = os.path.join(ROOT, "apks")
APP_FILES = "/sdcard/Android/data/kr.co.milkt.privilegedhevctester/files"
CMD = f"{APP_FILES}/cmd"
DONE = f"{APP_FILES}/cmd.done"
ERR = f"{APP_FILES}/cmd.err"
STATUS = f"{APP_FILES}/cmd.status"


def adb(*args: str) -> tuple[int, str]:
    p = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def push_text(remote: str, text: str) -> None:
    with tempfile.NamedTemporaryFile("w", delete=False) as f:
        f.write(text)
        path = f.name
    try:
        adb("push", path, remote)
    finally:
        os.unlink(path)


def set_status(msg: str) -> None:
    print(f"status: {msg}", flush=True)
    push_text(STATUS, msg)


def setup(version: str) -> tuple[bool, str]:
    if version == "91":
        set_status("WebView 91 설치 중… (downgrade)")
        code, out = adb(
            "install", "-r", "-d", "-g",
            os.path.join(APKS, "webview-91.0.4472.120.apk"),
        )
        adb("shell", "am", "force-stop", "com.google.android.webview")
        return code == 0, out
    if version == "134":
        set_status("Trichrome 134 설치 중…")
        tri = os.path.join(APKS, "trichrome-134.0.6998.40.apk")
        wv = os.path.join(APKS, "webview-134.0.6998.40.apk")
        c1, o1 = adb("install", "-r", "-g", tri)
        set_status("WebView 134 설치 중…")
        c2, o2 = adb("install", "-r", "-d", "-g", wv)
        adb("shell", "am", "force-stop", "com.google.android.webview")
        return (c1 == 0 and c2 == 0), o1 + "\n" + o2
    return False, f"unknown version {version}"


def read_cmd() -> str:
    code, out = adb("exec-out", "cat", CMD)
    if code != 0:
        return ""
    return out.strip().replace("\r", "")


def main() -> None:
    print(f"host-bridge polling {CMD} on {SERIAL}", flush=True)
    while True:
        cmd = read_cmd()
        if cmd in ("91", "134"):
            print(f"got cmd={cmd!r}", flush=True)
            adb("shell", "rm", "-f", DONE, ERR, STATUS)
            set_status(f"수신됨 → WebView {cmd} 설치 시작")
            ok, out = setup(cmd)
            print(out, flush=True)
            push_text(DONE, "ok\n" if ok else "fail\n")
            push_text(ERR, out[-1500:])
            set_status("완료" if ok else "실패")
            adb("shell", "rm", "-f", CMD)
            print(f"finished cmd={cmd} ok={ok}", flush=True)
            time.sleep(1.0)
        time.sleep(0.4)


if __name__ == "__main__":
    main()
