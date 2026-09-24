#!/usr/bin/env bash
# Host-JVM gates (run in the toolchain image, no Android needed):
#   1. the build engine both targets share (core/: Graft + MiniAxml + Signing + BuildPipeline) against
#      the pristine apk/base.apk and the baked build/assets, then its signed output checked with the
#      platform tools (apksigner + zipalign);
#   2. the installer's own logic (installer/core/: ApkPure parser, install outcomes, screen state,
#      caches, resumable download), which needs no apk.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
require_apk
cd "$REPO_ROOT"

[ -d "$ASSETS_DIR/patches" ] \
    || { echo "$ASSETS_DIR missing — run scripts/build-assets.sh first" >&2; exit 1; }

log "engine gate: graft + sign against apk/base.apk (core classes + apksig)"
compile_java build/gen/test/engine-cls "$APKSIG_JAR" "$CORE_SRC" "$CORE_TEST_SRC"
run_tools java -cp "build/gen/test/engine-cls:$APKSIG_JAR" com.wazeology.core.test.GraftHostTest /work

log "verifying the engine gate's signed output with the platform tools"
run_tools bash -lc 'set -e
BT=/opt/android-sdk/build-tools/34.0.0
"$BT/apksigner" verify --print-certs build/gen/test/signed.apk
"$BT/zipalign" -c -p 4 build/gen/test/signed.apk \
  && echo "grafted base alignment OK (stored entries page-aligned)"
"$BT/apksigner" verify build/gen/test/split-signed.apk
"$BT/zipalign" -c -p 4 build/gen/test/split-signed.apk && echo "re-signed split alignment OK"'
verify_apks build/gen/test/build
log "build output (base + splits) verifies and is aligned"

log "installer gate: decisions, caches, resumable download (installer/core)"
compile_java build/gen/test/installer-cls "$APKSIG_JAR" "$CORE_SRC" "$INSTALLER_CORE_SRC" "$INSTALLER_TEST_SRC"
run_tools java -cp "build/gen/test/installer-cls:$APKSIG_JAR" com.wazeology.installer.test.StateHostTest /work

log "all gates passed: the shared build engine is proven against the pinned base"
