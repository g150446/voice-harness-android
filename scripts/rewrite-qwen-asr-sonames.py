#!/usr/bin/env python3
"""Rewrite Qwen ASR ELF DT_NEEDED strings to unique same-length names.

LEAP also ships libllama.so / libggml*.so / libmtmd.so. Same-length replacements
keep ELF offsets valid so both sets can live in nativeLibraryDir.
"""
from __future__ import annotations

import pathlib
import sys

REPLACEMENTS = (
    (b"libggml-", b"libqasr-"),
    (b"libggml.so", b"libqasr.so"),
    (b"libllama.so", b"libqwnlm.so"),
    (b"libmtmd.so", b"libqmtm.so"),
)


def rewrite_dir(directory: pathlib.Path) -> int:
    changed = 0
    for path in sorted(directory.glob("*.so")):
        data = path.read_bytes()
        updated = data
        for old, new in REPLACEMENTS:
            if len(old) != len(new):
                raise SystemExit(f"replacement length mismatch: {old!r} -> {new!r}")
            updated = updated.replace(old, new)
        if updated != data:
            path.write_bytes(updated)
            changed += 1
            print(f"rewrote sonames in {path.name}")
    return changed


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} <jni-abi-dir>")
    target = pathlib.Path(sys.argv[1])
    if not target.is_dir():
        raise SystemExit(f"not a directory: {target}")
    rewrite_dir(target)
