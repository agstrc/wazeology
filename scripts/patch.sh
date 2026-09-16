#!/usr/bin/env bash
# Inject the 4 smali hooks + the launcher <activity> into build/base_apktool. Idempotent.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

if [ ! -d "$DECOMP_DIR" ]; then
    echo "build/base_apktool missing — run scripts/decompile.sh first" >&2
    exit 1
fi

log "applying patches (smali hooks + manifest activity)"
run_tools python3 patches/apply_patches.py "build/base_apktool"
