# WorkshopBridge build plan

Staged plan agreed with joshua. Each phase ends with a review checkpoint - we don't start the next phase until the previous one is signed off.

## Phase 0 - Validation (done)

- [x] Pure-Lua `steamcmd` execution is impossible (Kahlua has no `os.execute`/`io`, Java interop is whitelist-only, no sockets, sandboxed file I/O). See `docs/RESEARCH.md`.
- [x] ZombieBuddy validated as the Java→Lua bridge (B42-only, `@Exposer.LuaClass` / `@LuaMethod(global=true)`).
- [x] steamcmd anonymous workshop downloads work for PZ app ID 108600; install paths and mod-folder layout confirmed.
- [x] Prior art reviewed: `zomboid-mod-downloader`, `pz_launcher`, `pzmm`.

## Phase 1 - Skeleton + joint review (done)

- [x] Repo structure, docs, Lua stubs, Java stubs. Reviewed in parallel with Phase 2 kickoff.
- [x] Open items from research resolved via game decompile (see `docs/RESEARCH.md` §7).

## Phase 2 - Lua UI (done, verified in-game Oct 2026)

- B42 mod layout confirmed against a real B42 ZombieBuddy mod: `common/mod.info` (`require=ZombieBuddy`, no backslash), `42/media/java/WorkshopBridge.jar`, `42/media/lua/...`.
- In-game verification (42.20.4, debug stub): mod loads with no Lua errors, Check/Update-all buttons render bottom-right, per-mod Update button + three-state status label work, clicking Update flips the label to "Updating...".
- Two real bugs found and fixed during verification: (1) `doDrawItem` wrapper dropped vanilla's return value -> black screen, `__sub not defined for operands` every frame; (2) buttons anchored left of the bottom-left Back button -> rendered offscreen. Both covered by regression tests in `tests/lua/`.
- License: MIT scaffolded, not yet confirmed.
- **GOG ZombieBuddy install path**: GOG users must manually copy `ZombieBuddy.jar` + native lib and inject the `-agentlib:`/`-javaagent:` JVM flag. Must be tested on a real GOG install and documented step-by-step. Currently blocked: see Phase 3.

## Phase 3 - Java side (blocked on ZombieBuddy, Oct 2026)

**Blocker:** ZombieBuddy 2.3.2 does not load Java mods on PZ 42.21.0. The game changed `ZomboidFileSystem.loadMods(ArrayList<String>)` to `loadMods(List<String>)` and ZB's hook still matches the old signature ([zed-0xff/ZombieBuddy#53](https://github.com/zed-0xff/ZombieBuddy/issues/53)). [PR #56](https://github.com/zed-0xff/ZombieBuddy/pull/56) widens the hook to `List` (matches both 42.20 and 42.21) but is not yet confirmed in-game. Plan: joshua forks or builds from the PR, then we compile against the real `ZombieBuddy.jar` + PZ classes.

Implemented (compiled under JDK 17 with stubs, 19-check harness green with a fake steamcmd):
- `SteamCmdApi` (Lua globals via `@LuaMethod(global=true)`), `Backend` (lazy singleton),
  `JobManager` (background jobs, JSON status), `WorkshopMap` (persisted JSON map),
  `SteamCmd` (explicit `steamcmd.path` config or managed bootstrap, no discovery; `ProcessBuilder` downloads), `ModInstaller` (clean-replace
  installs, `mod.info` id parsing), `WorkshopApi` (keyless `GetPublishedFileDetails`),
  `Json` (minimal parser/writer), `Net` (friendly network-failure messages), `Main` (load logging).
- Lua contract: `wbGetJobStatus` returns a JSON string; `WB_Json.lua` decoder on the Lua side; debug stub mirrors the JSON contract. Round-trip verified with real Java output.

Still to do together:
- Compile against real ZombieBuddy.jar + PZ classes; fix any API drift.
- End-to-end test: install a small workshop mod, then update it.
- Remaining edge cases: workshop item with multiple mods; workshop item deleted; steamcmd missing 32-bit libs on Linux; anonymous login rejected → account-login fallback (interactive, never store credentials); Steam Guard UX; read the game's own `ChooseGameInfo.getModDetails(modId).getWorkshopID()` as a supplementary "Managed by Steam" signal; job cancellation.
- Network failures: no pre-flight probe by design - failures are translated to friendly messages (`Net.friendlyMessage`: "Couldn't reach Steam's servers - check your internet connection.") and surface through the job error in the Lua UI.
- [ ] **Checkpoint:** end-to-end test - install a small workshop mod, then update it.
- ZBS signing, VirusTotal per release (Phase 4).

## Phase 4 - Harden + release (after Phase 3)

- ZBS-sign releases (Ed25519); publish VirusTotal scan per release (see meowwoem's `SECURITYCHECK.MD` pattern).
- Reproducible-build notes so users can verify the JAR.
- GOG ZombieBuddy install guide with screenshots.
- Workshop page + README install instructions.
- Consider: "adopt" flow for mods the user installed manually (match by modID → ask for workshop URL), currently out of scope.

## Open questions (carried)

- Whether pzmm-style scanners flag the JAR's `ProcessBuilder` usage at warn or block level - mitigations already planned (open source, signing, ZB approval dialog).
- B41 support: out of scope (ZombieBuddy is B42-only). Revisit only if a B41-compatible loader emerges.
- License choice: MIT scaffolded, not yet confirmed by joshua.
