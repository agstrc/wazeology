#!/usr/bin/env bash
# Compile the Wazeology package, reassemble via apktool, graft onto pristine base.apk, align + sign.
# Output: build/gen/base.apk + build/gen/splits/*.apk (all signed with the same debug key).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
require_apk

if [ ! -d "$DECOMP_DIR" ]; then
    echo "build/base_apktool missing — run scripts/decompile.sh && scripts/patch.sh first" >&2
    exit 1
fi

# 1. Gate on the frame byte-layout checks.
"$SCRIPT_DIR/framecheck.sh"

ensure_keystore

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

# 3. Reassemble the decompiled tree (gives us the binary manifest + patched classes6.dex).
log "apktool b (for binary manifest + patched classes6.dex)"
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
log "grafting onto pristine base.apk"
run_tools python3 scripts/graft.py \
    --pristine "apk/base.apk" \
    --apktool "build/gen/apktool_out.apk" \
    --pkgdex "build/gen/pkg.dex" \
    --res-sub "$ICON_TARGET=build/gen/icon/compiled.xml" \
    --out "build/gen/base.apk"

# 5. Align + sign base and re-sign every split with the SAME key.
log "zipalign + apksigner (base + splits)"
run_tools bash -lc '
set -e
BT=/opt/android-sdk/build-tools/34.0.0
"$BT/zipalign" -p -f 4 build/gen/base.apk build/gen/base-aligned.apk
mv build/gen/base-aligned.apk build/gen/base.apk
"$BT/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android build/gen/base.apk
rm -f build/gen/base.apk.idsig
mkdir -p build/gen/splits
for s in apk/split_*.apk; do
  out="build/gen/splits/$(basename "$s")"
  "$BT/zipalign" -p -f 4 "$s" "$out"
  "$BT/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android "$out"
  rm -f "$out.idsig"
done
"$BT/apksigner" verify build/gen/base.apk && echo "base signature OK"
'

log "built: build/gen/base.apk + build/gen/splits/ — next: scripts/install.sh"
