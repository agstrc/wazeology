#!/usr/bin/env bash
# Install the patched split set (base + resigned splits) to a connected device via adb (in container).
# If Waze is already installed with a different signature, uninstall it first (see docs).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
cd "$REPO_ROOT"

if [ ! -f "build/gen/base.apk" ]; then
    echo "build/gen/base.apk missing — run scripts/build.sh first" >&2
    exit 1
fi

SER_ARGS=()
if [ -n "${DEVICE_SERIAL:-}" ]; then
    SER_ARGS=(-s "$DEVICE_SERIAL")
fi

log "adb devices (from container):"
run_tools_net adb "${SER_ARGS[@]}" devices -l || true

splits=(build/gen/splits/*.apk)
log "install-multiple base + ${#splits[@]} splits"
run_tools_net adb "${SER_ARGS[@]}" install-multiple -r "build/gen/base.apk" "${splits[@]}"

log "installed. Launch: adb shell am start -n com.waze/com.waze.wazeology.WazeologyActivity (or the Wazeology icon)"
