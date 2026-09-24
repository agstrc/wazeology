#!/usr/bin/env bash
# Bake the patch assets both build targets read into build/assets/: the patched hook dexes
# (classes5/6.dex), the rebuilt binary AndroidManifest.xml, the compiled launcher icon, the payload dex
# (classes11.dex) and the signing key (PKCS12). The installer app packs this dir as its assets;
# BuildWaze reads it on the host. Needs apk/ (fetch-apk.sh) and the patched tree (decompile.sh +
# patch.sh). Nothing here produces a Waze apk; build-installer.sh and build-waze.sh do.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
require_apk
cd "$REPO_ROOT"

if [ ! -d "$DECOMP_DIR" ]; then
    echo "build/base_apktool missing — run scripts/decompile.sh && scripts/patch.sh first" >&2
    exit 1
fi

# 0. Gates: payload frame checks, pin consistency, and the dex layout of the pinned base.
log "gate: frame byte-layout checks (payload)"
"$SCRIPT_DIR/framecheck.sh"

log "gate: pins sync (lib.sh <-> Pins.java <-> apk/base.apk)"
grep -q "WAZE_PACKAGE = \"$WAZE_PACKAGE\"" "$PINS" \
    || { echo "FATAL: Pins.WAZE_PACKAGE != lib.sh WAZE_PACKAGE" >&2; exit 1; }
grep -q "WAZE_VERSION_CODE = $WAZE_VERSION_CODE" "$PINS" \
    || { echo "FATAL: Pins.WAZE_VERSION_CODE != lib.sh WAZE_VERSION_CODE" >&2; exit 1; }
grep -q "WAZE_VERSION_NAME = \"$WAZE_VERSION\"" "$PINS" \
    || { echo "FATAL: Pins.WAZE_VERSION_NAME != lib.sh WAZE_VERSION" >&2; exit 1; }
read -r BASE_DEXES PAYLOAD_NAME < <(run_tools python3 scripts/dex_info.py apk/base.apk)
PINS_BASE_DEXES="$(grep -o 'BASE_DEX_COUNT = [0-9]*' "$PINS" | grep -o '[0-9]*')"
PINS_PAYLOAD="$(grep -o 'PAYLOAD_DEX = "[^"]*"' "$PINS" | cut -d'"' -f2)"
[ "$BASE_DEXES" = "$PINS_BASE_DEXES" ] \
    || { echo "FATAL: base.apk has $BASE_DEXES dexes, Pins.BASE_DEX_COUNT = $PINS_BASE_DEXES" >&2; exit 1; }
[ "$PAYLOAD_NAME" = "$PINS_PAYLOAD" ] \
    || { echo "FATAL: next free dex is $PAYLOAD_NAME, Pins.PAYLOAD_DEX = $PINS_PAYLOAD" >&2; exit 1; }

# 1. Reassemble the patched tree once (apktool b) and extract the hook dexes + binary manifest.
#    The rebuilt resources are discarded as always; only code + manifest come out of this build.
log "apktool b (patched dexes + binary manifest for the build assets)"
run_tools bash -lc 'set -e
rm -rf build/gen/apktool_out.apk build/gen/patches
mkdir -p build/gen/patches
apktool b build/base_apktool -o build/gen/apktool_out.apk'

# The hook dex set is derived from the injected markers, exactly as the old graft did; a hook
# added to a new dex is picked up automatically and must match Pins.HOOK_DEXES.
REBUILT=()
while read -r sd; do
    case "$sd" in
        smali)          REBUILT+=(classes.dex) ;;
        smali_classes*) REBUILT+=("classes${sd#smali_classes}.dex") ;;
    esac
done < <(grep -rl "Lcom/waze/debug/InstructionReporter;->" "$DECOMP_DIR"/smali*/ \
    | sed -E 's#.*/(smali(_classes[0-9]+)?)/.*#\1#' | sort -u)
[ "${#REBUILT[@]}" -gt 0 ] \
    || { echo "FATAL: no injected hook markers found in $DECOMP_DIR: patches not applied?" >&2; exit 1; }
PINS_HOOKS="$(grep -o 'HOOK_DEXES = {[^}]*}' "$PINS" \
    | sed -E 's/.*\{//; s/\}.*//; s/[",]//g' | xargs -n1 | sort | tr '\n' ' ' | xargs)"
SORTED_REBUILT="$(printf '%s\n' "${REBUILT[@]}" | sort | tr '\n' ' ' | xargs)"
[ "$SORTED_REBUILT" = "$PINS_HOOKS" ] \
    || { echo "FATAL: hook markers -> $SORTED_REBUILT, Pins.HOOK_DEXES -> $PINS_HOOKS" >&2; exit 1; }
log "hook dexes: ${REBUILT[*]}"

EXTRACT_ARGS=()
for d in "${REBUILT[@]}"; do EXTRACT_ARGS+=("$d"); done
EXTRACT_ARGS+=("AndroidManifest.xml=manifest.bin")
run_tools python3 scripts/extract_zip.py build/gen/apktool_out.apk build/gen/patches \
    "${EXTRACT_ARGS[@]}"

# 2. Compile the Wazeology payload (com.waze.wazeology + com.waze.debug) -> classes11.dex.
log "compiling the Wazeology payload -> $PINS_PAYLOAD"
run_tools bash -lc 'set -e
rm -rf build/gen/cls build/gen/dexout
mkdir -p build/gen/cls build/gen/dexout
javac -source 8 -target 8 -cp /opt/android-sdk/platforms/android-34/android.jar -nowarn \
  -d build/gen/cls $(find payload/java -name "*.java")
d8 --min-api 32 --output build/gen/dexout $(find build/gen/cls -name "*.class")
mv build/gen/dexout/classes.dex build/gen/patches/classes11.dex'

# 3. Compile the self-contained launcher icon (adaptive) to binary XML and resolve the orphan
#    resource whose bytes it overwrites (mipmap/launch_icon_round, AndResGuard-shortened path).
#    The resolved path must match Pins.ICON_PATH - the build asserts it at build time too.
log "compiling the Wazeology launcher icon -> binary xml"
run_tools bash -lc 'set -e
AJ=/opt/android-sdk/platforms/android-34/android.jar
rm -rf build/gen/icon
mkdir -p build/gen/icon/res/mipmap-anydpi-v26 build/gen/icon/out
cp payload/icon.xml build/gen/icon/res/mipmap-anydpi-v26/launch_icon_round.xml
cat > build/gen/icon/AndroidManifest.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.wz.iconbuild"><application/></manifest>
EOF
aapt2 compile build/gen/icon/res/mipmap-anydpi-v26/launch_icon_round.xml -o build/gen/icon/out/
aapt2 link -o build/gen/icon/out/icon.apk --manifest build/gen/icon/AndroidManifest.xml \
  -I "$AJ" --min-sdk-version 32 build/gen/icon/out/*.flat'
run_tools python3 scripts/extract_zip.py build/gen/icon/out/icon.apk build/gen/patches \
    --match "launch_icon_round=icon.bin"
ICON_TARGET="$(run_tools bash -lc 'aapt2 dump resources apk/base.apk 2>/dev/null \
    | grep -A8 launch_icon_round | grep -m1 anydpi | grep -oE "res/[^ ]+\.xml"')"
[ -n "$ICON_TARGET" ] || { echo "could not resolve launch_icon_round (anydpi) path" >&2; exit 1; }
PINS_ICON="$(grep -o 'ICON_PATH = "[^"]*"' "$PINS" | cut -d'"' -f2)"
[ "$ICON_TARGET" = "$PINS_ICON" ] \
    || { echo "FATAL: icon target is $ICON_TARGET, Pins.ICON_PATH = $PINS_ICON" >&2; exit 1; }
log "orphan launch_icon_round (anydpi) -> $ICON_TARGET"

# 4. Assemble build/assets: the patch bytes and the signing key, under the names Pins expects.
PINS_P12="$(grep -o 'P12_ASSET = "[^"]*"' "$PINS" | cut -d'"' -f2)"
rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR/patches" "$(dirname "$ASSETS_DIR/$PINS_P12")"
for f in "${REBUILT[@]}" "$PINS_PAYLOAD" manifest.bin icon.bin; do
    cp "build/gen/patches/$f" "$ASSETS_DIR/patches/$f"
done
ensure_signing_key
cp "$SIGNING_KEY" "$ASSETS_DIR/$PINS_P12"
log "assets: $(ls "$ASSETS_DIR/patches" | xargs) + $PINS_P12"

