#!/usr/bin/env bash
# Compile the Wazeology package, reassemble via apktool, graft onto the pristine base.apk, then bundle
# the grafted base with the config splits into one signed standalone apk: ./wazeology.apk (repo root),
# the repository's only build artifact. build/gen/base.apk is an internal intermediate.
#
# The bundle is a BINARY resource-table merge (APKEditor): it combines the base and split tables and
# copies every res/* entry verbatim, with no aapt2 recompile. The golden rule (DEVELOPMENT.md §3) holds
# the whole way through, because nothing recompiles the resource XML that apktool would corrupt. The
# final resources.arsc is a merged table, which is expected; a proof gate (verify_merge.py) asserts no
# resource FILE changed beyond the graft. LANGS picks which language splits to bundle (default "all";
# e.g. LANGS="pt en"); the ABI and density splits are always bundled.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
require_apk
cd "$REPO_ROOT"

if [ ! -d "$DECOMP_DIR" ]; then
    echo "build/base_apktool missing — run scripts/decompile.sh && scripts/patch.sh first" >&2
    exit 1
fi

# 1. Gate on the frame byte-layout checks.
"$SCRIPT_DIR/framecheck.sh"

# 2. Compile the Wazeology package (com.waze.wazeology + com.waze.debug) against android.jar -> a dex.
log "compiling Wazeology package -> dex"
run_tools bash -lc '
set -e
rm -rf build/gen/cls build/gen/dexout build/gen/pkg.dex
mkdir -p build/gen/cls build/gen/dexout
javac -source 8 -target 8 -cp /opt/android-sdk/platforms/android-34/android.jar -nowarn \
  -d build/gen/cls $(find src/com -name "*.java")
d8 --min-api 32 --output build/gen/dexout $(find build/gen/cls -name "*.class")
mv build/gen/dexout/classes.dex build/gen/pkg.dex
'

# 3. Reassemble the decompiled tree (gives us the binary manifest + every patched dex).
log "apktool b (for binary manifest + patched dexes)"
run_tools apktool b "build/base_apktool" -o "build/gen/apktool_out.apk"

# 3b. Compile the self-contained Wazeology launcher icon (adaptive) to binary XML, and resolve the
#     ORPHAN resource whose bytes it overwrites: mipmap/launch_icon_round's (anydpi) file. Waze sets
#     only android:icon=@mipmap/launch_icon (never android:roundIcon), so launch_icon_round is unused
#     by Waze — we repurpose it for Wazeology. patches/wazeology-icon.xml references no app resource
#     ids, so aapt2 links it standalone and graft can drop the bytes without touching resources.arsc.
log "compiling Wazeology launcher icon (adaptive) -> binary xml"
run_tools bash -lc '
set -e
AJ=/opt/android-sdk/platforms/android-34/android.jar
rm -rf build/gen/icon
mkdir -p build/gen/icon/res/mipmap-anydpi-v26 build/gen/icon/out
cp patches/wazeology-icon.xml build/gen/icon/res/mipmap-anydpi-v26/launch_icon_round.xml
cat > build/gen/icon/AndroidManifest.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.wz.iconbuild"><application/></manifest>
EOF
aapt2 compile build/gen/icon/res/mipmap-anydpi-v26/launch_icon_round.xml -o build/gen/icon/out/
aapt2 link -o build/gen/icon/out/icon.apk --manifest build/gen/icon/AndroidManifest.xml \
  -I "$AJ" --min-sdk-version 32 build/gen/icon/out/*.flat
python3 - <<PY
import zipfile
z = zipfile.ZipFile("build/gen/icon/out/icon.apk")
d = [z.read(n) for n in z.namelist() if n.endswith(".xml") and "launch_icon_round" in n][0]
open("build/gen/icon/compiled.xml", "wb").write(d)
PY
aapt2 dump resources apk/base.apk 2>/dev/null \
  | grep -A8 "launch_icon_round" | grep -m1 anydpi | grep -oE "res/[^ ]+\.xml" \
  > build/gen/icon/target_path.txt
echo "orphan launch_icon_round (anydpi) -> $(cat build/gen/icon/target_path.txt)"
'
ICON_TARGET="$(cat build/gen/icon/target_path.txt)"
[ -s build/gen/icon/compiled.xml ] || { echo "icon compile produced no output" >&2; exit 1; }
[ -n "$ICON_TARGET" ] || { echo "could not resolve launch_icon_round (anydpi) path" >&2; exit 1; }

# 4. Graft onto the pristine base.apk (resources.arsc stays byte-identical; --res-sub only overwrites
#    the CONTENT of the existing orphan icon entry).
# The graft ships the pristine dexes verbatim and swaps in ONLY the patched hook dexes (the ones whose
# smali a hook touched). Derive that set from the injected hook markers so a hook added to any dex is
# picked up automatically: the four nav hooks land in classes6, the FreeMapAppActivity startup hook in
# classes5. Missing one here is the trap that once left the startup hook in an un-swapped
# classes5.dex: the pristine one shipped instead, so init() never ran.
REBUILT_DEX_ARGS=()
while read -r sd; do
    case "$sd" in
        smali)          REBUILT_DEX_ARGS+=(--rebuilt-dex "classes.dex") ;;
        smali_classes*) REBUILT_DEX_ARGS+=(--rebuilt-dex "classes${sd#smali_classes}.dex") ;;
    esac
done < <(grep -rl "Lcom/waze/debug/InstructionReporter;->" "$DECOMP_DIR"/smali*/ \
    | sed -E 's#.*/(smali(_classes[0-9]+)?)/.*#\1#' | sort -u)
if [ "${#REBUILT_DEX_ARGS[@]}" -eq 0 ]; then
    echo "FATAL: no injected hook markers found in $DECOMP_DIR: patches not applied?" >&2
    exit 1
fi

log "grafting onto pristine base.apk"
run_tools python3 scripts/graft.py \
    --pristine "apk/base.apk" \
    --apktool "build/gen/apktool_out.apk" \
    --pkgdex "build/gen/pkg.dex" \
    "${REBUILT_DEX_ARGS[@]}" \
    --res-sub "$ICON_TARGET=build/gen/icon/compiled.xml" \
    --out "build/gen/base.apk"

log "grafted intermediate base.apk"

# 5. Bundle the grafted base with the config splits into the final apk. want_split TOKEN -> 0 if the
#    split should be bundled. ABI and density splits always are; a language split is bundled only when
#    LANGS is "all" or lists it. Language tokens are the short locale codes (pt, en, ...); ABI/density
#    tokens (arm64_v8a, xxxhdpi, ...) never match the locale shape.
ensure_keystore
langs_lc=" $(printf '%s' "$LANGS" | tr 'A-Z' 'a-z') "
want_split() {
    case "$1" in
        *dpi|arm64_v8a|armeabi_v7a|x86|x86_64|mips|mips64) return 0 ;;
    esac
    [ "$LANGS" = "all" ] && return 0
    case "$langs_lc" in *" $1 "*) return 0 ;; esac
    return 1
}

# 5a. Assemble the merge input: the grafted base + the selected config splits.
rm -rf build/gen/merge_in build/gen/merged.apk
rm -f wazeology.apk wazeology.apk.idsig
mkdir -p build/gen/merge_in
cp build/gen/base.apk build/gen/merge_in/base.apk
kept=0
for s in apk/split_*.apk; do
    tok="$(basename "$s" .apk)"; tok="${tok#split_config.}"
    if want_split "$tok"; then cp "$s" build/gen/merge_in/; kept=$((kept + 1)); fi
done
log "merge input: base + $kept splits (LANGS=$LANGS)"

# 5b. Binary merge -> single apk (merges resource tables, sanitizes the split manifest, embeds libs).
log "apkeditor m (binary split merge -> single apk)"
run_tools apkeditor m -i build/gen/merge_in -o build/gen/merged.apk

# 5c. Align + sign the final apk, written to the repo root as ./wazeology.apk.
log "zipalign + apksigner (bundled apk -> ./wazeology.apk)"
run_tools bash -lc '
set -e
BT=/opt/android-sdk/build-tools/34.0.0
"$BT/zipalign" -p -f 4 build/gen/merged.apk wazeology.apk
"$BT/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android wazeology.apk
rm -f wazeology.apk.idsig
"$BT/apksigner" verify wazeology.apk && echo "bundled-apk signature OK"
'

# 6. GATE: prove the merge corrupted nothing. Every pristine-base res/* file must be byte-identical in
#    the final apk except the ones the graft intentionally patched; the only allowed removal is the
#    obsolete split descriptor. The .so files must be stored uncompressed (extractNativeLibs=false).
#    (A script file, not an inline heredoc: run_tools has no `docker run -i`, so a `python3 - <<PY`
#    would read empty stdin and silently no-op.)
log "verifying resource integrity (golden rule preserved: no resource XML recompiled)"
run_tools python3 scripts/verify_merge.py apk/base.apk build/gen/base.apk wazeology.apk

log "built the bundled apk: ./wazeology.apk"
log "install it by sideloading ./wazeology.apk onto the device (see DEVELOPMENT.md §8)"
