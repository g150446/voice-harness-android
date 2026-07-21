#!/usr/bin/env bash
set -euo pipefail

# Build, install the phone app, and push all on-device models.
# Usage: ./scripts/install-and-push.sh

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if ! adb get-state >/dev/null 2>&1; then
  echo "No adb device connected. Connect your device and enable USB debugging." >&2
  exit 1
fi

echo "Building app..."
"$ROOT_DIR/gradlew" :app:assembleDebug --no-daemon

echo "Installing app..."
adb install -r "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"

echo "Pushing models..."
"$ROOT_DIR/scripts/push-all-models.sh"

echo ""
echo "Launching app..."
adb shell am start -n com.g150446.voiceharness/.MainActivity

echo ""
echo "Done. Open the app, then navigate to モデル設定 to check model status."
