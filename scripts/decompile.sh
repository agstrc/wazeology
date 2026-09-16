#!/usr/bin/env bash
# Decompile the pristine base.apk into build/base_apktool for smali + manifest editing.
# NOTE: the decoded resources here are only used to let apktool reassemble classes*.dex and the
# binary manifest at build time — the rebuilt resources are DISCARDED (see the golden rule).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
require_apk

mkdir -p "$BUILD_DIR"
if [ -d "$DECOMP_DIR" ]; then
    log "removing previous decompile ($DECOMP_DIR)"
    rm -rf "$DECOMP_DIR"
fi

log "apktool d apk/base.apk -> build/base_apktool"
run_tools apktool d --force "apk/base.apk" -o "build/base_apktool"
log "decompiled. Next: scripts/patch.sh"
