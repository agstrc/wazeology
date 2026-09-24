#!/usr/bin/env bash
# Build the pinned toolchain image (apktool, apkeep, Android build-tools, JDK, apksig, ...).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

log "building $IMAGE (first build downloads the Android SDK, ~1.5 GB)"
docker build -t "$IMAGE" "$REPO_ROOT/docker"

log "sanity-checking tools inside the image"
run_tools bash -lc 'apktool --version && apkeep --version && \
    d8 --version 2>/dev/null | head -1 && aapt2 version && javac -version && \
    ls /opt/apksig.jar && java -cp /opt/apksig.jar com.android.apksig.ApkSigner --version 2>/dev/null | head -1'
log "image ready: $IMAGE"
