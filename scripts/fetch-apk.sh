#!/usr/bin/env bash
# Download the pinned Waze version (in-container) and normalize into apk/base.apk +
# apk/split_config.*.apk. apk-pure goes through FetchWaze, the installer's own APKPure lookup, which
# picks the bundle carrying the native split for ABIS (default arm64-v8a); google-play uses apkeep.
# The APK is NEVER committed.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image

ABI="${ABIS:-arm64-v8a}"
ABI="${ABI%%,*}"
# Without the native split for the phone's processor, Waze with Wazeology has no native libraries.
SPLIT="$APK_DIR/split_config.${ABI//-/_}.apk"

# Skip the (slow) download when the workspace already holds the normalized apks, native split
# included. FORCE=1 re-fetches.
if [ -z "${FORCE:-}" ] && [ -f "$APK_DIR/base.apk" ] && [ -f "$SPLIT" ]; then
    log "apk/base.apk + splits already present — skipping download (set FORCE=1 to re-fetch)"
    exit 0
fi

mkdir -p "$APK_DIR/_dl"
rm -rf "${APK_DIR:?}/_dl"/*

log "downloading ${WAZE_PACKAGE}@${WAZE_VERSION} for ${ABI} from ${APK_SOURCE} (in container)"
case "$APK_SOURCE" in
    apk-pure)
        # apkeep ignores the ABI here and may hand back the armeabi-v7a bundle, so use the installer's
        # lookup, which checks each bundle's file list for the native split before downloading it.
        compile_java build/gen/fetch "$APKSIG_JAR" "$CORE_SRC" "$INSTALLER_CORE_SRC" "$CLI_SRC"
        run_tools java -cp "build/gen/fetch:$APKSIG_JAR" com.wazeology.cli.FetchWaze /work \
            --out apk/_dl --abi "${ABIS:-arm64-v8a}"
        ;;
    google-play)
        : "${GOOGLE_EMAIL:?set GOOGLE_EMAIL in .env for google-play}"
        : "${AAS_TOKEN:?set AAS_TOKEN in .env for google-play}"
        # google-play pins by versionCode; splits come down together.
        run_tools apkeep -a "${WAZE_PACKAGE}@${WAZE_VERSION_CODE}" -d google-play \
            -e "$GOOGLE_EMAIL" -t "$AAS_TOKEN" "apk/_dl"
        ;;
    *)
        echo "unknown APK_SOURCE='$APK_SOURCE' (use apk-pure or google-play)" >&2
        exit 1
        ;;
esac

log "normalizing download into apk/base.apk + apk/split_config.*.apk"
run_tools python3 scripts/normalize_apks.py

[ -f "$SPLIT" ] || { echo "FATAL: the download has no ${ABI} native split ($SPLIT missing)" >&2; exit 1; }

log "verifying identity"
run_tools aapt2 dump badging "apk/base.apk" | grep -E "^package: name" || true
ls -1 "$APK_DIR"/base.apk "$APK_DIR"/split_*.apk
