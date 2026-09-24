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
  payload/java/com/waze/wazeology/Kawasaki.java payload/java/com/waze/wazeology/FlagMode.java \
  payload/java/com/waze/wazeology/DistanceUnit.java payload/java/com/waze/wazeology/TurnType.java \
  payload/java/com/waze/wazeology/Command.java payload/java/com/waze/wazeology/Capabilities.java \
  payload/java/com/waze/wazeology/Frames.java payload/test/FramesCheck.java
java -cp build/framecheck FramesCheck
'
