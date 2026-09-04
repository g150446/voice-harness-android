#!/usr/bin/env bash
set -euo pipefail

# Build, install the phone app, and push all on-device models.
# Usage: ./scripts/install-and-push.sh

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if ! adb get-state >/dev/null 2>&1; then
  echo "No adb device connected. Connect your device and enable USB debugging." >&2
  exit 1
fi

echo "Installing app via USB helper..."
"$ROOT_DIR/scripts/install-usb.sh" --no-launch

echo "Pushing models..."
"$ROOT_DIR/scripts/push-all-models.sh"

echo ""
echo "Launching app..."
SERIAL="$(adb devices -l | awk '/device usb:/{print $1; exit}')"
if [[ -n "${SERIAL:-}" ]]; then
  adb -s "$SERIAL" shell am start -n com.g150446.voiceharness/.MainActivity
else
  adb shell am start -n com.g150446.voiceharness/.MainActivity
fi

echo ""
echo "Done. Open the app, then navigate to モデル設定 to check model status."