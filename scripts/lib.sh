#!/usr/bin/env bash
# Shared config + Docker helpers for every script in this repo.
# Host rule: NO tool ever runs on the host. Everything goes through run_tools() (the toolchain image).
set -euo pipefail

# --- repo paths -----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_DIR="$REPO_ROOT/apk"
BUILD_DIR="$REPO_ROOT/build"
DECOMP_DIR="$BUILD_DIR/base_apktool"

# One PKCS12 signs Waze with Wazeology in both build targets: baked into the installer app (which
# signs on the phone) and read by BuildWaze on the host. Generated once and reused, so every build
# from either target can update every other. A separate throwaway keystore signs the installer app.
SIGNING_KEY="$BUILD_DIR/gen/wazeology-sign.p12"
SIGNING_KEY_PASS="wazeology"
KEYSTORE="$BUILD_DIR/debug.keystore"

# Repo-relative paths, valid both on the host (scripts cd to $REPO_ROOT) and inside run_tools (/work).
PAYLOAD_SRC="payload/java"                     # injected into Waze (com.waze.wazeology + .debug)
PAYLOAD_TEST_SRC="payload/test"
CORE_SRC="core/src"                            # the build engine, shared by both targets (no android.*)
CORE_TEST_SRC="core/test/src"                  # engine gate against apk/base.apk
INSTALLER_CORE_SRC="installer/core/src"        # installer-only logic, host-testable (no android.*)
APP_SRC="installer/app/src"                    # the installer app (Android)
INSTALLER_TEST_SRC="installer/test/src"        # installer logic gate (no apk needed)
CLI_SRC="cli/src"                              # BuildWaze, the direct target's host entry point
ASSETS_DIR="build/assets"                      # baked patch assets + signing key, read by both targets
DIST_DIR="dist"                                # artifacts: wazeology-installer.apk, waze/*.apk
PINS="$CORE_SRC/com/wazeology/core/Pins.java"

# --- pinned versions (reproducibility) ------------------------------------------------------
# The build engine (core ... Pins.java) is compiled against the same pins; build-assets.sh greps
# both sides and fails when they drift.
WAZE_PACKAGE="com.waze"
WAZE_VERSION="${WAZE_VERSION:-5.23.0.2}"      # pinned default; overridable via .env
WAZE_VERSION_CODE="1030725"
APK_SOURCE="${APK_SOURCE:-apk-pure}"          # apk-pure (default) | google-play
IMAGE="waze-tools:latest"

# Installer app identity + tool paths in the image (keep in sync with docker/Dockerfile).
INSTALLER_PACKAGE="com.wazeology.installer"
INSTALLER_VERSION_CODE="1"
INSTALLER_MIN_SDK="26"
INSTALLER_TARGET_SDK="34"
ANDROID_JAR="/opt/android-sdk/platforms/android-34/android.jar"
BUILD_TOOLS="/opt/android-sdk/build-tools/34.0.0"
APKSIG_JAR="/opt/apksig.jar"

# Optional .env (WAZE_VERSION, APK_SOURCE, GOOGLE_EMAIL, AAS_TOKEN, ...)
if [ -f "$REPO_ROOT/.env" ]; then
    # shellcheck disable=SC1091
    set -a; . "$REPO_ROOT/.env"; set +a
fi

# --- docker helpers -------------------------------------------------------------------------
# Run a tool inside the toolchain image, repo mounted at /work, as the host user so outputs
# are not root-owned. HOME=/tmp gives shell tools a writable home. JAVA_TOOL_OPTIONS pins
# user.home too: the anonymous uid has no /etc/passwd entry, so the JVM (apktool, d8, apksigner)
# would otherwise resolve user.home to the literal "?" and drop its caches in a "?" dir at the
# repo root instead of honouring $HOME.
run_tools() {
    docker run --rm \
        -u "$(id -u):$(id -g)" \
        -e HOME=/tmp \
        -e JAVA_TOOL_OPTIONS=-Duser.home=/tmp \
        -v "$REPO_ROOT:/work" -w /work \
        "$IMAGE" "$@"
}

require_image() {
    if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
        echo "toolchain image '$IMAGE' missing — run scripts/build-image.sh first" >&2
        exit 1
    fi
}

require_apk() {
    if [ ! -f "$APK_DIR/base.apk" ]; then
        echo "apk/base.apk missing — run scripts/fetch-apk.sh first" >&2
        exit 1
    fi
}

# Throwaway keystore that signs the installer app apk. Gitignored — never committed.
ensure_keystore() {
    if [ ! -f "$KEYSTORE" ]; then
        mkdir -p "$BUILD_DIR"
        echo ">> generating debug keystore (throwaway)"
        run_tools keytool -genkeypair -v -keystore "build/debug.keystore" \
            -storepass android -keypass android -alias androiddebugkey \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Wazeology Installer,O=waze,C=BR"
    fi
}

# The PKCS12 that signs Waze with Wazeology (both targets). Generated once and reused. A key from an
# older layout (installer-sign.p12, builder-sign.p12) is moved, never regenerated: a new key would
# give Waze with Wazeology a new signer, and Android refuses to update an install signed by the old
# one. Gitignored.
ensure_signing_key() {
    local old
    for old in "$BUILD_DIR/gen/installer-sign.p12" "$BUILD_DIR/gen/builder-sign.p12"; do
        if [ ! -f "$SIGNING_KEY" ] && [ -f "$old" ]; then
            mv "$old" "$SIGNING_KEY"
            echo ">> moved the existing signing key $(basename "$old") to $SIGNING_KEY"
        fi
    done
    if [ ! -f "$SIGNING_KEY" ]; then
        mkdir -p "$(dirname "$SIGNING_KEY")"
        echo ">> generating the Wazeology signing key (PKCS12)"
        run_tools keytool -genkeypair -v -storetype PKCS12 -keystore "build/gen/wazeology-sign.p12" \
            -storepass "$SIGNING_KEY_PASS" -keypass "$SIGNING_KEY_PASS" -alias wazeology \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Wazeology,O=wazeology,C=BR"
    fi
}

# Compile Java 8 sources in the toolchain image: compile_java <outdir> <classpath> <src dir>...
# (repo-relative). The phone and the host JVM both run what this produces.
compile_java() {
    local out="$1" cp="$2"
    shift 2
    run_tools bash -lc 'set -e
out="$1"; cp="$2"; shift 2
rm -rf "$out" && mkdir -p "$out"
javac -source 8 -target 8 -nowarn -Xlint:-options -cp "$cp" -d "$out" $(find "$@" -name "*.java")' \
        _ "$out" "$cp" "$@"
}

# Verify every apk in a dir (repo-relative) with the platform tools: signature, and stored entries
# page-aligned. verify_apks <dir> [--print-certs]
verify_apks() {
    run_tools bash -lc 'set -e
BT=/opt/android-sdk/build-tools/34.0.0
for apk in "$1"/*.apk; do
    "$BT/apksigner" verify $2 "$apk"
    "$BT/zipalign" -c -p 4 "$apk"
done' _ "$1" "${2:-}"
}

log() { echo ">> $*"; }
