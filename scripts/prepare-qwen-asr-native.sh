#!/usr/bin/env bash
set -euo pipefail

# Pin the official llama.cpp Android arm64 runtime used for Qwen3-ASR.
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="b9637"
DEST="$ROOT_DIR/.qwen-asr-native"
ARCHIVE="$DEST/llama-$VERSION-bin-android-arm64.tar.gz"

mkdir -p "$DEST"
if [[ ! -f "$ARCHIVE" ]]; then
  curl -L --fail --retry 3 \
    "https://github.com/ggml-org/llama.cpp/releases/download/$VERSION/llama-$VERSION-bin-android-arm64.tar.gz" \
    -o "$ARCHIVE"
fi
tar -xzf "$ARCHIVE" -C "$DEST"
test -x "$DEST/llama-$VERSION/llama-mtmd-cli"
echo "Prepared llama.cpp $VERSION in $DEST/llama-$VERSION"
