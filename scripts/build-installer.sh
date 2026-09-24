#!/usr/bin/env bash
# Build target "installer": the Wazeology Installer app (dist/wazeology-installer.apk), which builds
# Waze with Wazeology on the phone from the assets in build/assets (run build-assets.sh first).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
cd "$REPO_ROOT"

[ -f "$PINS" ] && [ -d "$ASSETS_DIR/patches" ] \
    || { echo "$ASSETS_DIR missing — run scripts/build-assets.sh first" >&2; exit 1; }

# 1. Compile the installer (engine + installer logic + app) and dex apksig.jar into it. apksig runs
#    ON THE PHONE to sign Waze with Wazeology; the engine classes are the ones the host gates test.
log "compiling the installer app (core + installer + apksig) -> classes.dex"
compile_java build/gen/installer/cls "$ANDROID_JAR:$APKSIG_JAR" "$CORE_SRC" "$INSTALLER_CORE_SRC" "$APP_SRC"
run_tools bash -lc 'set -e
rm -rf build/gen/installer/dexout && mkdir -p build/gen/installer/dexout
d8 --min-api 26 --lib "$1" --output build/gen/installer/dexout \
  $(find build/gen/installer/cls -name "*.class") "$2"' _ "$ANDROID_JAR" "$APKSIG_JAR"

# 2. Link the installer resources + manifest (aapt2) with build/assets as its assets, then add the dex.
run_tools bash -lc 'set -e
rm -rf build/gen/installer/res
mkdir -p build/gen/installer/res
aapt2 compile installer/app/res/mipmap-anydpi-v26/icon.xml -o build/gen/installer/res/
aapt2 link -o build/gen/installer/installer-unsigned.apk \
  --manifest installer/app/AndroidManifest.xml \
  -I "$1" \
  -A "$2" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  build/gen/installer/res/*.flat
for d in build/gen/installer/dexout/classes*.dex; do
    python3 scripts/zip_add.py build/gen/installer/installer-unsigned.apk "$(basename "$d")" "$d"
done' _ "$ANDROID_JAR" "$ASSETS_DIR"

# 3. Align + sign the installer apk into dist/wazeology-installer.apk.
ensure_keystore
OUT="$DIST_DIR/wazeology-installer.apk"
log "zipalign + apksigner (installer app -> $OUT)"
mkdir -p "$DIST_DIR"
run_tools bash -lc 'set -e
BT=/opt/android-sdk/build-tools/34.0.0
rm -f "$1" "$1.idsig"
"$BT/zipalign" -f 4 build/gen/installer/installer-unsigned.apk build/gen/installer/installer-aligned.apk
"$BT/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "$1" build/gen/installer/installer-aligned.apk
"$BT/apksigner" verify "$1" && echo "installer-apk signature OK"' _ "$OUT"

log "identity: $(run_tools aapt2 dump badging "$OUT" | grep -E "^package: name='" | head -1)"
log "built $OUT (Wazeology Installer): sideload it to set up Waze on the phone"
