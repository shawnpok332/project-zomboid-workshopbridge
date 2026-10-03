#!/bin/sh
# fake steamcmd for tests: creates a canned workshop item layout.
# args: +force_install_dir <dir> +login anonymous +workshop_download_item 108600 <id> +quit
CACHE=""
ID=""
prev=""
for a in "$@"; do
  if [ "$prev" = "+force_install_dir" ]; then CACHE="$a"; fi
  if [ "$prev" = "108600" ]; then ID="$a"; fi
  prev="$a"
done
MODDIR="$CACHE/steamapps/workshop/content/108600/$ID/mods/FakeMod/common"
if [ "$ID" = "0" ]; then
  # failure mode for tests: exit nonzero without producing any files
  echo "ERROR! Download item 0 failed (No match)" >&2
  exit 1
fi
mkdir -p "$MODDIR"
printf 'id=FakeMod\ntitle=Fake Mod\n' > "$MODDIR/mod.info"
mkdir -p "$MODDIR/../media/lua"
echo "-- fake" > "$MODDIR/../media/lua/fake.lua"
echo "Steam Console Client"
