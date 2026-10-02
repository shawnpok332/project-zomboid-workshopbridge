# WorkshopBridge build plan

Staged plan agreed with shawnpok332. Each phase ends with a review checkpoint — we don't start the next phase until the previous one is signed off.

## Phase 0 — Validation (done)

- [x] Pure-Lua `steamcmd` execution is impossible (Kahlua has no `os.execute`/`io`, Java interop is whitelist-only, no sockets, sandboxed file I/O). See `docs/RESEARCH.md`.
- [x] ZombieBuddy validated as the Java→Lua bridge (B42-only, `@Exposer.LuaClass` / `@LuaMethod(global=true)`).
- [x] steamcmd anonymous workshop downloads work for PZ app ID 108600; install paths and mod-folder layout confirmed.
- [x] Prior art reviewed: `zomboid-mod-downloader`, `pz_launcher`, `pzmm`.

## Phase 1 — Skeleton + joint review (in progress)

- [x] Repo structure, docs, Lua stubs, Java stubs.
- [ ] **Checkpoint:** review skeleton together. Open items to resolve:
  1. B42 mod layout: is `42/media/java/WorkshopBridge.jar` + `javaJarFile` in mod.info correct? (per ZombieBuddy ModdingGuide; verify against a real B42 install)
  2. mod.info fields: `require=\ZombieBuddy` syntax, `apiVersion`, `javaPkgName` — verify.
  3. Mods-screen hook: exact screen/row class names for B42 (research pending).
  4. License choice (MIT scaffolded — confirm).
  5. **GOG ZombieBuddy install path**: GOG users must manually copy `ZombieBuddy.jar` + native lib and inject the `-agentlib:`/`-javaagent:` JVM flag. Our target users are GOG players, so this must be tested on a real GOG install and documented step-by-step.

## Phase 2 — Lua UI skeleton fill (after Phase 1 sign-off)

- Detect Java API presence; show "ZombieBuddy required" guidance when missing.
- Hook the Mods screen: add **Update all** button; per-row **Update** button or grey **"Unknown workshop ID"** label.
- Job polling UI (progress state per `docs/ARCHITECTURE.md`).
- [ ] **Checkpoint:** shawnpok332 verifies in-game (B42 + ZombieBuddy installed, Java stubs returning canned responses).

## Phase 3 — Java side (together, after Phase 2 sign-off)

- `SteamCmd`: detect steamcmd (PATH → common locations → user-configured path), spawn via `ProcessBuilder` on background threads, capture output.
- `WorkshopMap`: persist `workshopID → {modIds, timeUpdated, lastDownloaded}` as JSON under `Zomboid/`; invert for modID → workshopID lookups.
- Download flow: `+force_install_dir` to a cache dir → `+login anonymous` → `+workshop_download_item 108600 <id>` → `+quit`; move `mods/*` into `Zomboid/mods/` (replace existing); update map from `mod.info` `id=` lines.
- Update check: keyless `ISteamRemoteStorage/GetPublishedFileDetails` → compare `time_updated` vs stored; download only when newer.
- `JobManager`: background jobs with pollable status; cancellation.
- Edge cases: workshop item with multiple mods; workshop item deleted; steamcmd missing 32-bit libs on Linux; anonymous login rejected → account-login fallback (interactive, never store credentials); Steam Guard UX.
- [ ] **Checkpoint:** end-to-end test — install a small workshop mod, then update it.

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
