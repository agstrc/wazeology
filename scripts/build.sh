#!/usr/bin/env bash
# Build the artifacts: build.sh [installer|waze|all] (default all).
#   installer  dist/wazeology-installer.apk, the app that builds Waze with Wazeology on the phone
#   waze       dist/waze/*.apk, Waze with Wazeology built directly on the host
# Both bake the same assets first (build-assets.sh) and run the same build engine (core/).
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TARGET="${1:-all}"
case "$TARGET" in
    installer|waze|all) ;;
    *) echo "usage: $0 [installer|waze|all]" >&2; exit 1 ;;
esac

"$D/build-assets.sh"
if [ "$TARGET" = installer ] || [ "$TARGET" = all ]; then
    "$D/build-installer.sh"
fi
if [ "$TARGET" = waze ] || [ "$TARGET" = all ]; then
    "$D/build-waze.sh"
fi
