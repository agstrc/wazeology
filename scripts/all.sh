#!/usr/bin/env bash
# One-shot: fetch -> decompile -> patch -> build -> test.
# Assumes the toolchain image exists (run scripts/build-image.sh once first).
# fetch-apk and decompile skip their work when their outputs already exist; FORCE=1 re-runs both.
# TARGET picks what build.sh makes: installer, waze or all (default).
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$D/fetch-apk.sh"                 # skips if apk/base.apk + splits already present (FORCE=1 re-downloads)
"$D/decompile.sh"                 # skips if build/base_apktool already present (FORCE=1 re-decompiles)
"$D/patch.sh"
"$D/build.sh" "${TARGET:-all}"    # bakes build/assets, then dist/wazeology-installer.apk and/or dist/waze/
"$D/test.sh"                      # proves the build engine against apk/base.apk + the installer logic
