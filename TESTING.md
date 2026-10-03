# Testing WorkshopBridge

Two offline suites plus one manual online smoke test. Nothing here needs the game.

## Offline suites

### Lua: `./tests/lua/run.sh`

Needs: a Lua interpreter (`lua`, `lua5.4`, or `lua5.3` on PATH; falls back to the
workspace's own Lua build).

Runs, in order:

1. **JSON decoder** (`test_json.lua`) - the pure-Lua `WB_Json` decoder: objects,
   arrays, nesting, escapes, unicode, numbers, malformed input.
2. **UI integration** (`test_ui.lua`) - loads the real `WB_*.lua` files with stubbed
   PZ globals and walks the whole flow: boot, menu hook, three-state per-mod panel
   (Force update / Managed by Steam / Unknown workshop ID), progress panel
   (eager per-screen build, visible during jobs, flash auto-hide, sticky
   click-to-dismiss errors), check-for-updates (update badges on rows,
   per-mod button flips to "Update", result summary flash), per-mod update
   (button back to "Force update" when done), update-all, unknown jobs, and
   the `update()` fallback job pump.
3. **Download dialog** (`test_download.lua`) - the workshop-ID/URL parser plus the
   download flow: dialog open/validate/cancel, fake download job, `reloadMods`
   on completion.
3. **Java to Lua JSON round-trip** (`test_roundtrip.lua`) - serializes real job
   statuses with the real Java `Json` class, then decodes them with the real Lua
   decoder. Needs `javac`/`java` (JDK 17+); skipped loudly when absent.

### Java: `./tests/java/run.sh`

Needs: JDK 17+ on PATH (`javac`, `java`).

Compiles the real backend against test stubs (no game classes, no ZombieBuddy
needed) and runs `WBTest`. The Steam Web API is a local stub HTTP server and
steamcmd is a fake shell script (`tests/java/fakebin/steamcmd.sh`), so the whole
thing is hermetic: no network, no game.

All file activity stays in `tests/java/.test-work/` (gitignored): a fake Zomboid
dir holding the map, mods, properties, and steamcmd cache. It is wiped at the
start of every run and kept afterwards, so a failed run can be inspected. The
harness refuses to run without that dir configured - it will never touch your
real `~/Zomboid`.

Covered:

- `Json` parsing: basics plus escapes, unicode, surrogate pairs, nesting,
  malformed input (the parser is hand-rolled and load-bearing, so it gets
  edge-case tests)
- `WorkshopApi.parseTimeUpdated` against a **real captured Steam response**
  (`tests/java/fixtures/publishedfiledetails.json`), including the string
  `publishedfileid` and the `result=9` (bogus id) omission; malformed JSON
  and structurally invalid responses now throw instead of parsing as empty
  (a broken response must fail the check, never look like "all deleted")
- `WorkshopMap`: atomic save (no `.tmp` left behind), reload round-trip,
  corrupt file loads empty instead of throwing, `42.0/mod.info` id parsing,
  self-healing reverse lookup (stale folder-name entry repaired via mod.info
  scan)
- `JobManager`: check job end to end (finds updates, flags items the API no
  longer lists), update-all job, per-mod update job, invalid ids, unknown jobs,
  steamcmd failure surfacing as a failed job with the cause in `error`,
  download serialization (second download reports "Queued...", never overlaps),
  repeat checks coalesce onto the running job, malformed API response fails the
  check instead of reporting "up to date"
- `SteamCmd`: explicit-path override, fake download to install to map
  recording, `installArchive` extracting a real `.tar.gz`, nonzero exit with a
  stale cache rejected (no reinstalling the old tree as if fresh), timed-out
  process waited on after destroy so it can't overlap the next queued job
- `ModInstaller`: atomic swap reinstall (stale files gone, no staging
  leftovers in `mods/` or the stage dir), and all three crash-recovery cases
  (mid-swap completes forward, post-swap backup dropped, partial staging
  dropped), in both the current stage-dir layout and the legacy in-`mods/`
  layout. Staging lives in `workshop_cache/.install-staging` (outside `mods/`)
  so the game's file watcher never trips over the transient backup dirs.
- `Net.friendlyMessage`: DNS/connect timeouts become actionable messages

In environments where Java cannot do a plain HTTP round-trip to localhost (some
sandboxes intercept or block it), the checks that need the stub HTTP server
print `SKIPPED ... (no usable loopback HTTP here)` instead of passing silently.
On a normal machine they run.

## Online smoke test (manual)

`./tests/java/run-online.sh` - hits the **real** Steam Web API and Valve's CDN:

1. `GetPublishedFileDetails` for a known item, sanity-checks `time_updated`.
2. Bootstraps a real steamcmd from Valve's CDN into the test work dir.
3. Anonymously downloads a real ~180KB workshop mod and runs it through the
   actual install pipeline.

Needs JDK 17+ and internet. Takes several minutes on first run (steamcmd's
first-run self-update). Not part of the offline suite and not run by default.
Run it before a release, or whenever the Valve-facing code changes. Everything
it writes stays in `tests/java/.test-work-online/` (gitignored).

Linux note: steamcmd is a 32-bit binary, so the bootstrap step fails with
install instructions if your system lacks the 32-bit runtime libraries
(`sudo apt install lib32gcc-s1 lib32stdc++6` on Debian/Ubuntu). On NixOS,
install `steam-run` from nixpkgs (or enable `nix-ld`); the mod runs steamcmd
through `steam-run` automatically when it's available.

If the probe workshop item ever disappears, step 1 fails loudly - that means
the world changed, not the test.

## What the suites deliberately do not cover

- Real ZombieBuddy Java to Lua exposure in-game (needs a working ZombieBuddy
  build for B42; the Lua stub covers the contract shape).
- In-game visuals: the progress panel and badges are logic-tested, not eyeballed.
- Real steamcmd self-update and large-mod downloads (the online smoke test
  covers bootstrap plus a tiny download).
- macOS bootstrap (Valve ships no steamcmd for it; the mod refuses with an
  explanatory message, untested by hand so far).

## Debugging a failure

- Java: look in `tests/java/.test-work/zomboid/` - the map, `mods/`,
  `workshop_cache/`, and `workshopbridge.properties` are all there as the
  failed run left them.
- The `SKIPPED (no usable loopback HTTP ...)` lines are environmental, not failures.
- Lua: the failing `test_*.lua` prints `FAIL <name> -- <detail>`; rerun just
  that file with `lua tests/lua/test_ui.lua` (with `WB_LUA_DIR` set).
