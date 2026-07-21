#!/usr/bin/env bash
set -euo pipefail

# Push all on-device models to a connected Android device.
# Usage: ./scripts/push-all-models.sh

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

MODELS=(
  "$ROOT_DIR/models/gemma-4-E2B-it.litertlm"
  "$ROOT_DIR/models/qwen35_mm_q8_ekv2048.litertlm"
  "$ROOT_DIR/models/Qwen3-ASR-0.6B-Q8_0.gguf"
  "$ROOT_DIR/models/mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"
)

MISSING=0
for m in "${MODELS[@]}"; do
  if [[ ! -f "$m" ]]; then
    echo "Missing: $m" >&2
    MISSING=1
  fi
done
if [[ $MISSING -ne 0 ]]; then
  echo "Some models are missing. Download them first or place them under models/." >&2
  exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "No adb device connected" >&2
  exit 1
fi

for m in "${MODELS[@]}"; do
  echo ""
  echo "============================================================"
  echo "Pushing $(basename "$m")"
  echo "============================================================"
  "$ROOT_DIR/scripts/push-model.sh" "$m"
done

echo ""
echo "All models pushed."
