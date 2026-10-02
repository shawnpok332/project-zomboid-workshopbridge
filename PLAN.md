# WorkshopBridge build plan

Staged plan agreed with shawnpok332. Each phase ends with a review checkpoint — we don't start the next phase until the previous one is signed off.

## Phase 0 — Validation (done)

- [x] Pure-Lua `steamcmd` execution is impossible (Kahlua has no `os.execute`/`io`, Java interop is whitelist-only, no sockets, sandboxed file I/O). See `docs/RESEARCH.md`.
- [x] ZombieBuddy validated as the Java→Lua bridge (B42-only, `@Exposer.LuaClass` / `@LuaMethod(global=true)`).
- [x] steamcmd anonymous workshop downloads work for PZ app ID 108600; install paths and mod-folder layout confirmed.
- [x] Prior art reviewed: `zomboid-mod-downloader`, `pz_launcher`, `pzmm`.

## Phase 1 — Skeleton + joint review (done)

- [x] Repo structure, docs, Lua stubs, Java stubs. Reviewed in parallel with Phase 2 kickoff.
- [x] Open items from research resolved via game decompile (see `docs/RESEARCH.md` §7).

## Phase 2 — Lua UI (in progress)

Open verification items (need a real B42 install):
1. B42 mod layout: is `42/media/java/WorkshopBridge.jar` + `javaJarFile` in mod.info correct? (per ZombieBuddy ModdingGuide)
2. mod.info fields: `require=\ZombieBuddy` syntax, `apiVersion`, `javaPkgName`.
3. License choice (MIT scaffolded — confirm).
4. **GOG ZombieBuddy install path**: GOG users must manually copy `ZombieBuddy.jar` + native lib and inject the `-agentlib:`/`-javaagent:` JVM flag. Our target users are GOG players, so this must be tested on a real GOG install and documented step-by-step.
5. In-game UI verification (see checkpoint below): button placement, row badges, progress panel, ModInfoPanel layout.

## Phase 2 — Lua UI skeleton fill (after Phase 1 sign-off)

- Detect Java API presence; show "ZombieBuddy required" guidance when missing.
- Hook the Mods screen: **Check for updates** + **Update all** buttons; per-row status text; per-mod **Update** button / three-state status label in ModInfoPanel.
- Job polling UI: progress indicator (done/total/message from the job status) so long downloads/checks give visible feedback; errors surfaced, not silent.
- [ ] **Checkpoint:** shawnpok332 verifies in-game (B42 + ZombieBuddy installed, Java stubs returning canned responses).

## Phase 3 — Java side (in progress, started while shawnpok332 reinstalls)

Implemented (uncompiled — needs ZombieBuddy.jar + PZ classes, see java-src/README):
- `SteamCmdApi` (Lua globals via `@LuaMethod(global=true)`), `Backend` (lazy singleton),
  `JobManager` (background jobs, JSON status), `WorkshopMap` (persisted JSON map),
  `SteamCmd` (detection + `ProcessBuilder` downloads), `ModInstaller` (clean-replace
  installs, `mod.info` id parsing), `WorkshopApi` (keyless `GetPublishedFileDetails`),
  `Json` (minimal parser/writer), `Main` (load logging).
- Lua contract updated: `wbGetJobStatus` returns a JSON string; new `WB_Json.lua`
  decoder; debug stub mirrors the JSON contract.
- Mod layout corrected to verified B42 form: `common/mod.info` (`require=ZombieBuddy`,
  no backslash), `42/media/java/WorkshopBridge.jar`, `42/media/lua/...`.

Still to do together:
- Compile against real ZombieBuddy.jar + PZ classes; fix any API drift.
- End-to-end test: install a small workshop mod, then update it.
- Remaining edge cases: workshop item with multiple mods; workshop item deleted; steamcmd missing 32-bit libs on Linux; anonymous login rejected → account-login fallback (interactive, never store credentials); Steam Guard UX; read the game's own `ChooseGameInfo.getModDetails(modId).getWorkshopID()` as a supplementary "Managed by Steam" signal; job cancellation.
- Network failures: no pre-flight probe by design — failures are translated to friendly messages (`Net.friendlyMessage`: "Couldn't reach Steam's servers - check your internet connection.") and surface through the job error in the Lua UI.
- [ ] **Checkpoint:** end-to-end test — install a small workshop mod, then update it.
- ZBS signing, VirusTotal per release (Phase 4).

## Phase 4 — Harden + release (after Phase 3)

- ZBS-sign releases (Ed25519); publish VirusTotal scan per release (see meowwoem's `SECURITYCHECK.MD` pattern).
- Reproducible-build notes so users can verify the JAR.
- GOG ZombieBuddy install guide with screenshots.
- Workshop page + README install instructions.
- Consider: "adopt" flow for mods the user installed manually (match by modID → ask for workshop URL), currently out of scope.

## Open questions (carried)

- Exact B42 Mods-screen Lua hook (research in flight).
- Whether pzmm-style scanners flag the JAR's `ProcessBuilder` usage at warn or block level — mitigations already planned (open source, signing, ZB approval dialog).
- B41 support: out of scope (ZombieBuddy is B42-only). Revisit only if a B41-compatible loader emerges.
