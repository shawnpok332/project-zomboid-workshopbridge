#!/usr/bin/env bash
# Offline Java test rig for WorkshopBridge's backend. No game, no network.
# Needs: JDK 17+ on PATH (javac, java).
#
# All test file activity (fake Zomboid dir, map, mods, steamcmd cache) stays
# inside tests/java/.test-work/ (gitignored). It is wiped at the start of
# every run and kept afterwards, so a failed run can be inspected.
#
# Run from anywhere:  ./tests/java/run.sh
set -u
cd "$(dirname "$0")"  # tests/java
HERE="$PWD"
REPO="$(cd ../.. && pwd)"
JH_BIN="$(command -v javac | xargs dirname)"

# free port for the stub Steam API
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"

WORK="$HERE/.test-work"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/zomboid"

echo "compiling..."
"$JH_BIN/javac" -d "$WORK/classes" \
    $(find stubs src -name "*.java") \
    "$REPO/WorkshopBridge/java-src/src/main/java/com/workshopbridge/"*.java \
    || exit 1

echo "running tests..."
"$JH_BIN/java" \
    -Dwb.test.zomboid="$WORK/zomboid" \
    -Dwb.test.fakebin="$HERE/fakebin" \
    -Dwb.test.fixtures="$HERE/fixtures" \
    -Dworkshopbridge.steamApiUrl="http://127.0.0.1:$PORT/" \
    -cp "$WORK/classes" com.workshopbridge.WBTest "$PORT"
