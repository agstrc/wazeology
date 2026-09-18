#!/usr/bin/env bash
# Install the bundled apk (build/gen/wazeology.apk from scripts/build.sh) to a connected device via
# adb (in container). If Waze is already installed with a different signature (e.g. the Play build),
# uninstall it first: adb uninstall com.waze.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
cd "$REPO_ROOT"

if [ ! -f "build/gen/wazeology.apk" ]; then
    echo "build/gen/wazeology.apk missing. Run scripts/build.sh first" >&2
    exit 1
fi

SER_ARGS=()
if [ -n "${DEVICE_SERIAL:-}" ]; then
    SER_ARGS=(-s "$DEVICE_SERIAL")
fi

log "adb devices (from container):"
run_tools_net adb "${SER_ARGS[@]}" devices -l || true

log "install build/gen/wazeology.apk"
run_tools_net adb "${SER_ARGS[@]}" install -r "build/gen/wazeology.apk"

log "installed. Launch: adb shell am start -n com.waze/com.waze.wazeology.WazeologyActivity (or the Wazeology icon)"
