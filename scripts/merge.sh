#!/usr/bin/env bash
# Bundle the grafted base and the config splits into one standalone apk: build/gen/wazeology.apk.
# This is the repository's only build artifact.
#
# The merge is a BINARY resource-table merge (APKEditor): it combines the base and split tables and
# copies every res/* entry verbatim, with no aapt2 recompile. The golden rule (DEVELOPMENT.md §3) holds
# the whole way through, because nothing recompiles the resource XML that apktool would corrupt. The
# final resources.arsc is a merged table, which is expected. No resource FILE changes, and the gate in
# step 4 checks exactly that.
#
# LANGS picks which language splits to bundle (default "all"; e.g. LANGS="pt en"). The ABI and density
# splits are always bundled. Prereq: scripts/build.sh has produced build/gen/base.apk.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
require_apk

if [ ! -f "build/gen/base.apk" ]; then
    echo "build/gen/base.apk missing. Run scripts/build.sh first" >&2
    exit 1
fi
cd "$REPO_ROOT"
ensure_keystore

# want_split TOKEN -> 0 if the split should be bundled. ABI and density splits always are; a language
# split is bundled only when LANGS is "all" or lists it. Language tokens are the short locale codes
# (pt, en, ...); ABI/density tokens (arm64_v8a, xxxhdpi, ...) never match the locale shape.
langs_lc=" $(printf '%s' "$LANGS" | tr 'A-Z' 'a-z') "
want_split() {
    case "$1" in
        *dpi|arm64_v8a|armeabi_v7a|x86|x86_64|mips|mips64) return 0 ;;
    esac
    [ "$LANGS" = "all" ] && return 0
    case "$langs_lc" in *" $1 "*) return 0 ;; esac
    return 1
}

# 1. Assemble the merge input: the grafted base + the selected config splits.
rm -rf build/gen/merge_in build/gen/merged.apk build/gen/wazeology.apk
mkdir -p build/gen/merge_in
cp build/gen/base.apk build/gen/merge_in/base.apk
kept=0
for s in apk/split_*.apk; do
    tok="$(basename "$s" .apk)"; tok="${tok#split_config.}"
    if want_split "$tok"; then cp "$s" build/gen/merge_in/; kept=$((kept + 1)); fi
done
log "merge input: base + $kept splits (LANGS=$LANGS)"

# 2. Binary merge -> single apk (merges resource tables, sanitizes the split manifest, embeds libs).
log "apkeditor m (binary split merge -> single apk)"
run_tools apkeditor m -i build/gen/merge_in -o build/gen/merged.apk

# 3. Align + sign the final apk.
log "zipalign + apksigner (bundled apk)"
run_tools bash -lc '
set -e
BT=/opt/android-sdk/build-tools/34.0.0
"$BT/zipalign" -p -f 4 build/gen/merged.apk build/gen/wazeology.apk
"$BT/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android build/gen/wazeology.apk
rm -f build/gen/wazeology.apk.idsig
"$BT/apksigner" verify build/gen/wazeology.apk && echo "bundled-apk signature OK"
'

# 4. GATE: prove the merge corrupted nothing. Every pristine-base res/* file must be byte-identical in
#    the final apk except the ones the graft intentionally patched; the only allowed removal is the
#    obsolete split descriptor. The .so files must be stored uncompressed (extractNativeLibs=false).
#    (A script file, not an inline heredoc: run_tools has no `docker run -i`, so a `python3 - <<PY`
#    would read empty stdin and silently no-op.)
log "verifying resource integrity (golden rule preserved: no resource XML recompiled)"
run_tools python3 scripts/verify_merge.py apk/base.apk build/gen/base.apk build/gen/wazeology.apk

log "built the bundled apk: build/gen/wazeology.apk"
log "install it with: scripts/install.sh"
