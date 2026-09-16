#!/usr/bin/env bash
# Host-JVM unit test of the frame byte layouts (no Android needed). Gates the build.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image

log "compiling + running frame byte-layout checks"
run_tools bash -lc '
set -e
rm -rf build/framecheck && mkdir -p build/framecheck
javac -d build/framecheck \
  src/com/waze/wazeology/Kawasaki.java src/com/waze/wazeology/FlagMode.java \
  src/com/waze/wazeology/DistanceUnit.java src/com/waze/wazeology/TurnType.java \
  src/com/waze/wazeology/Command.java src/com/waze/wazeology/Capabilities.java \
  src/com/waze/wazeology/Frames.java src/test/FramesCheck.java
java -cp build/framecheck FramesCheck
'
