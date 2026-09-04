#!/usr/bin/env bash
# Reliable USB install for the large debug APK.
# Prefers a physical USB device over Tailscale wireless (which often times out).
#
# Usage:
#   ./scripts/install-usb.sh
#   ./scripts/install-usb.sh --no-launch
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LAUNCH=1
for arg in "$@"; do
  case "$arg" in
    --no-launch) LAUNCH=0 ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
  esac
done

pick_usb_serial() {
  adb devices -l | awk '
    /device usb:/ { print $1; exit }
    /device product:/ && $1 !~ /:/ { print $1; exit }
  '
}

SERIAL="$(pick_usb_serial || true)"
if [[ -z "${SERIAL:-}" ]]; then
  echo "ERROR: no USB adb device. Plug in USB and enable USB debugging." >&2
  echo "Tip: wireless install of the ~180MB debug APK is unreliable; use USB." >&2
  exit 1
fi

echo "USB device: $SERIAL"
echo "Building..."
"$ROOT_DIR/gradlew" :app:assembleDebug --quiet

APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "ERROR: missing $APK" >&2
  exit 1
fi

echo "Force-stopping old process..."
adb -s "$SERIAL" shell am force-stop com.g150446.voiceharness >/dev/null 2>&1 || true

echo "Installing $(du -h "$APK" | awk '{print $1}') APK over USB..."
BEFORE="$(adb -s "$SERIAL" shell dumpsys package com.g150446.voiceharness 2>/dev/null | awk -F= '/lastUpdateTime=/{print $2; exit}' || true)"
adb -s "$SERIAL" install -r -g "$APK"
AFTER="$(adb -s "$SERIAL" shell dumpsys package com.g150446.voiceharness 2>/dev/null | awk -F= '/lastUpdateTime=/{print $2; exit}' || true)"

if [[ -z "${AFTER:-}" ]]; then
  echo "ERROR: package missing after install" >&2
  exit 1
fi
if [[ -n "${BEFORE:-}" && "$BEFORE" == "$AFTER" ]]; then
  echo "WARNING: lastUpdateTime unchanged ($AFTER). Install may not have replaced the APK." >&2
else
  echo "Installed OK (lastUpdateTime=$AFTER)"
fi

if [[ "$LAUNCH" -eq 1 ]]; then
  echo "Launching..."
  adb -s "$SERIAL" shell am start -n com.g150446.voiceharness/.MainActivity >/dev/null
fi

# Keep wireless adb available after USB sessions (may drop the USB transport briefly).
adb -s "$SERIAL" tcpip 5555 >/dev/null 2>&1 || true

echo "Done."
echo "Later (wireless): ./scripts/adb-tailscale.sh connect"
echo "Prefer USB for installs: ./scripts/install-usb.sh"