#!/usr/bin/env bash
# Offline test rig for WorkshopBridge's Lua side. No game, no network needed.
#
# Needs: a Lua interpreter on PATH (tries lua, lua5.4, lua5.3).
# The Java->Lua round-trip additionally needs javac+java (JDK 17+);
# it is skipped gracefully when no JDK is available.
#
# Run from anywhere:  ./tests/lua/run.sh
set -u
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
export WB_LUA_DIR="$ROOT/WorkshopBridge/42/media/lua/client/WorkshopBridge"

LUA=""
for c in lua lua5.4 lua5.3; do
    if command -v "$c" >/dev/null 2>&1; then LUA="$c"; break; fi
done
# fall back to this workspace's own Lua build (~/workspace/.tools)
if [ -z "$LUA" ]; then
    for t in "$HOME/workspace/.tools/lua-5.4.7/src/lua" "$HOME/workspace/.tools/lua/src/lua"; do
        if [ -x "$t" ]; then LUA="$t"; break; fi
    done
fi
if [ -z "$LUA" ]; then
    echo "No Lua interpreter found on PATH (tried lua, lua5.4, lua5.3)." >&2
    echo "Install one, e.g.: apt install lua5.4  |  brew install lua  |  https://www.lua.org/download.html" >&2
    exit 1
fi

fail=0
run() { # name, script
    echo "--- $1"
    if "$LUA" "$2"; then :; else fail=1; fi
}

run "JSON decoder" test_json.lua
run "UI integration (stubbed PZ globals)" test_ui.lua
run "Download dialog" test_download.lua

# Java -> Lua contract: serialize real job statuses with the real Json class,
# then decode them with the real Lua decoder.
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
    TMPD="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$TMPD'" EXIT
    if javac -d "$TMPD" \
            "$ROOT/WorkshopBridge/java-src/src/main/java/com/workshopbridge/Json.java" \
            Rt.java 2>"$TMPD/javac.log" \
        && java -cp "$TMPD" Rt > "$TMPD/statuses.txt"; then
        export WB_STATUSES="$TMPD/statuses.txt"
        run "Java->Lua JSON round-trip" test_roundtrip.lua
    else
        echo "--- Java->Lua JSON round-trip SKIPPED (javac failed)"
        cat "$TMPD/javac.log"
    fi
else
    echo "--- Java->Lua JSON round-trip SKIPPED (no JDK on PATH)"
fi

if [ "$fail" -eq 0 ]; then echo "ALL LUA TESTS PASSED"; else echo "FAILURES PRESENT"; fi
exit "$fail"
