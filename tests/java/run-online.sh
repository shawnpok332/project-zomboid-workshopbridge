#!/usr/bin/env bash
# MANUAL online smoke test for WorkshopBridge. Hits the real Steam Web API
# and Valve's CDN, then downloads a real (~180KB) workshop mod with a real
# steamcmd and runs it through the install pipeline.
#
# This is NOT part of the offline suite: it needs internet access and can
# take several minutes (steamcmd's first-run self-update). Run it before a
# release, or whenever the Valve-facing code changes.
#
# Needs: JDK 17+ on PATH (javac, java).
# Everything it writes stays in tests/java/.test-work-online/ (gitignored),
# wiped at the start of every run.
#
# Run from anywhere:  ./tests/java/run-online.sh
set -u
cd "$(dirname "$0")"  # tests/java
HERE="$PWD"
REPO="$(cd ../.. && pwd)"

for tool in javac java; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "error: '$tool' not found on PATH. Install a JDK 17+ first, e.g.:" >&2
        echo "  Debian/Ubuntu: sudo apt install openjdk-17-jdk" >&2
        echo "  Fedora:        sudo dnf install java-17-openjdk-devel" >&2
        echo "  macOS:         brew install openjdk@17" >&2
        exit 1
    fi
done
JH_BIN="$(dirname "$(command -v javac)")"

WORK="$HERE/.test-work-online"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/zomboid"

echo "compiling..."
"$JH_BIN/javac" -d "$WORK/classes" \
    $(find stubs src -name "*.java") \
    "$REPO/WorkshopBridge/java-src/src/main/java/com/workshopbridge/"*.java \
    || exit 1

echo "running online smoke test (needs internet)..."
"$JH_BIN/java" \
    -Dwb.test.zomboid="$WORK/zomboid" \
    -cp "$WORK/classes" com.workshopbridge.OnlineSmoke
