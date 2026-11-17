#!/usr/bin/env bash
# Keep host-bridge running for PrivilegedHevcTester button installs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
LOG="${HOST_BRIDGE_LOG:-/tmp/host-bridge.log}"
PID_FILE="${HOST_BRIDGE_PID:-/tmp/host-bridge.pid}"

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "host-bridge already running pid=$(cat "$PID_FILE")"
  exit 0
fi

pkill -f 'webview-hevc-test/host-bridge.py' 2>/dev/null || true
nohup python3 "$ROOT/host-bridge.py" >>"$LOG" 2>&1 &
echo $! >"$PID_FILE"
echo "host-bridge started pid=$(cat "$PID_FILE") log=$LOG"
echo "Leave this terminal alone, or: tail -f $LOG"
