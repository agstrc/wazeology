#!/usr/bin/env bash
# Build target "waze": Waze with Wazeology built directly on the host (dist/waze/base.apk + every
# split), by the same build pipeline the installer runs on the phone, over the pinned Waze in apk/
# and the assets in build/assets (run build-assets.sh first). Signed with the same key, so it and an
# installer-made build can update each other. ABIS picks the native splits to require
# (default arm64-v8a, comma-separated).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
require_apk
cd "$REPO_ROOT"

[ -f "$PINS" ] && [ -d "$ASSETS_DIR/patches" ] \
    || { echo "$ASSETS_DIR missing — run scripts/build-assets.sh first" >&2; exit 1; }

OUT="$DIST_DIR/waze"
log "compiling BuildWaze (core + cli + apksig) for the host JVM"
compile_java build/gen/cli "$APKSIG_JAR" "$CORE_SRC" "$CLI_SRC"

log "building Waze with Wazeology -> $OUT"
run_tools java -cp "build/gen/cli:$APKSIG_JAR" com.wazeology.cli.BuildWaze /work \
    --in apk --out "$OUT" --abi "${ABIS:-arm64-v8a}"

log "verifying $OUT with the platform tools"
verify_apks "$OUT"

log "built $OUT: install with  adb install-multiple $OUT/*.apk"
