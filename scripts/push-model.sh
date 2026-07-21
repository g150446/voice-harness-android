#!/usr/bin/env bash
set -euo pipefail

# Push a model file into the app-private models directory on a connected device.
# Usage: ./scripts/push-model.sh [path-to-model] [optional-dest-name]
#
# Examples:
#   ./scripts/push-model.sh models/gemma-4-E2B-it.litertlm
#   ./scripts/push-model.sh models/qwen35_mm_q8_ekv2048.litertlm
#   ./scripts/push-model.sh models/Qwen3-ASR-0.6B-Q8_0.gguf
#   ./scripts/push-model.sh models/mmproj-Qwen3-ASR-0.6B-Q8_0.gguf

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODEL_SRC="${1:-$ROOT_DIR/models/gemma-4-E2B-it.litertlm}"
DEST_NAME="${2:-$(basename "$MODEL_SRC")}"
PKG="com.g150446.voiceharness"
CHUNK_BYTES=$((128 * 1024 * 1024))

if [[ ! -f "$MODEL_SRC" ]]; then
  echo "Model not found: $MODEL_SRC" >&2
  exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "No adb device connected" >&2
  exit 1
fi

SIZE="$(wc -c < "$MODEL_SRC" | tr -d ' ')"
echo "Source: $MODEL_SRC ($SIZE bytes)"
echo "Dest:   files/models/$DEST_NAME"

adb shell "run-as $PKG mkdir -p files/models"
adb shell "run-as $PKG rm -f files/models/$DEST_NAME"
adb shell "run-as $PKG touch files/models/$DEST_NAME"

python3 - "$MODEL_SRC" "$PKG" "$DEST_NAME" "$CHUNK_BYTES" "$SIZE" <<'PY'
import os, subprocess, sys, tempfile

src_path, pkg, dest_name, chunk_size_s, total_s = sys.argv[1:6]
chunk_size = int(chunk_size_s)
total = int(total_s)
offset = 0
idx = 0
base_tmp = f"/data/local/tmp/model_{dest_name.replace('.', '_')}_chunk"

def app_size() -> int:
    out = subprocess.check_output(
        ["adb", "shell", "run-as", pkg, "stat", "-c%s", f"files/models/{dest_name}"],
        text=True,
    ).strip().splitlines()[-1]
    return int(out)

with open(src_path, "rb") as src:
    while offset < total:
        data = src.read(chunk_size)
        if not data:
            break
        with tempfile.NamedTemporaryFile(delete=False) as tmp:
            tmp.write(data)
            tmp_host = tmp.name
        remote_tmp = f"{base_tmp}_{idx}.bin"
        subprocess.check_call(["adb", "push", tmp_host, remote_tmp])
        os.unlink(tmp_host)
        cmd = (
            f"cat {remote_tmp} | run-as {pkg} "
            f"sh -c 'cat >> files/models/{dest_name}'"
        )
        r = subprocess.run(["adb", "shell", cmd], capture_output=True, text=True)
        subprocess.call(["adb", "shell", f"rm -f {remote_tmp}"], stdout=subprocess.DEVNULL)
        if r.returncode != 0:
            print(r.stderr, file=sys.stderr)
            sys.exit(1)
        offset += len(data)
        idx += 1
        actual = app_size()
        print(f"chunk={idx} expected={offset} actual={actual}", flush=True)
        if actual != offset:
            print("SIZE MISMATCH", file=sys.stderr)
            sys.exit(2)

print(f"OK: files/models/{dest_name} ({app_size()} bytes)")
PY

echo "Also copying to public Download for SAF picker fallback..."
adb push "$MODEL_SRC" "/sdcard/Download/$DEST_NAME" >/dev/null
adb shell "ls -lh /sdcard/Download/$DEST_NAME" || true
echo "Done. Open app → モデル設定 → 再スキャン"
