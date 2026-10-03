#!/usr/bin/env bash
# Offline Java test rig for WorkshopBridge's backend. No game, no network.
# Needs: JDK 17+ on PATH (javac, java).
#
# Run from anywhere:  ./tests/java/run.sh
set -u
cd "$(dirname "$0")"  # tests/java
HERE="$PWD"
REPO="$(cd ../.. && pwd)"
JH_BIN="$(command -v javac | xargs dirname)"

# free port for the stub Steam API
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT

CLASSES="$TMPD/classes"
mkdir -p "$CLASSES"
echo "compiling..."
"$JH_BIN/javac" -d "$CLASSES" \
    $(find stubs src -name "*.java") \
    "$REPO/WorkshopBridge/java-src/src/main/java/com/workshopbridge/"*.java \
    || exit 1

echo "running tests..."
"$JH_BIN/java" \
    -Dwb.test.zomboid="$TMPD/zomboid" \
    -Dwb.test.fakebin="$HERE/fakebin" \
    -Dwb.test.fixtures="$HERE/fixtures" \
    -Dworkshopbridge.steamApiUrl="http://127.0.0.1:$PORT/" \
    -cp "$CLASSES" com.workshopbridge.WBTest "$PORT"
