#!/usr/bin/env bash
# One-shot: fetch -> decompile -> patch -> framecheck -> build -> install.
# Assumes the toolchain image exists (run scripts/build-image.sh once first).
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$D/fetch-apk.sh"     # skips nothing; re-downloads the pinned version
"$D/decompile.sh"
"$D/patch.sh"
"$D/build.sh"         # runs framecheck internally, then compiles/grafts/signs
"$D/install.sh"
