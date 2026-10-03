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
- **GOG ZombieBuddy install path**: proven on joshua's machine (launch script: `steam-run` + bundled JRE + `-javaagent:ZombieBuddy.jar` + `-Dzomboid.steam=0`). Step-by-step guide with screenshots still to be written (Phase 4).

## Phase 3 - Java side (done Oct 2026)

**Blocker (resolved):** ZombieBuddy 2.3.2 did not load Java mods on PZ 42.21.0 (`loadMods(ArrayList<String>)` -> `loadMods(List<String>)`, [zed-0xff/ZombieBuddy#53](https://github.com/zed-0xff/ZombieBuddy/issues/53)). joshua built ZB from [PR #56](https://github.com/zed-0xff/ZombieBuddy/pull/56) and we compiled clean against the real 42.21.0 `projectzomboid.jar`.

- [x] `SteamCmdApi` (Lua globals), `Backend` (lazy singleton), `JobManager` (background jobs, JSON status), `WorkshopMap` (atomic persisted map), `SteamCmd` (explicit `steamcmd.path` or managed Valve-CDN bootstrap; NixOS `steam-run` auto-wrap), `ModInstaller` (atomic swap installs, crash recovery), `WorkshopApi` (keyless `GetPublishedFileDetails`), `Json`, `Net` (friendly failures), `Main` (load logging).
- [x] **Checkpoint:** end-to-end test passed - real download+install of a workshop mod in-game (EnableResetLuaButton, Oct 2026), after the full online smoke test passed on joshua's machine (real API + CDN + steamcmd).
- [x] Live-session hardening: noexec/sandbox binary fallback dir, single-flight bootstrap, validation-timeout leniency (first-run self-update), FORK launch-mechanism default (posix_spawn EACCES in steam-run's sandbox), ANSI stripping, sticky error panel, staging outside `mods/` (game's file watcher tripped over backup dirs).
- [x] Download-new-mod UI (Lua): Download button + ID/URL dialog; Java side needed no changes.

Moved to Phase 4 (hardening, not blockers): workshop item with multiple mods; anonymous-login rejection -> account-login fallback (interactive, never store credentials); Steam Guard UX; `getWorkshopID()` as supplementary "Managed by Steam" signal; job cancellation.

## Phase 4 - Harden + release

- Remaining edge cases from Phase 3 (see above).
- Deleted/private workshop items are already surfaced in the job message (done Oct 2026).
- 32-bit Linux runtime: hints in place (distro packages, NixOS `steam-run`/`nix-ld`); keep validating on real systems.
- ZBS-sign releases (Ed25519); publish VirusTotal scan per release (see meowwoem's `SECURITYCHECK.MD` pattern).
- Reproducible-build notes so users can verify the JAR.
- GOG ZombieBuddy install guide with screenshots (path proven, guide not written).
- Workshop page + README install instructions.
- Consider: "adopt" flow for mods the user installed manually (match by modID → ask for workshop URL), currently out of scope.

## Open questions (carried)

- Whether pzmm-style scanners flag the JAR's `ProcessBuilder` usage at warn or block level - mitigations already planned (open source, signing, ZB approval dialog).
- B41 support: out of scope (ZombieBuddy is B42-only). Revisit only if a B41-compatible loader emerges.
- License choice: MIT scaffolded, not yet confirmed by joshua.

## Backlog (from live testing, Oct 2026)

New scope goes here, not into the phases above, until it is picked up and
planned properly.

- [ ] **Progress/feedback UI never renders in-game (bug, high priority).**
  Clicking Download (dialog closes), per-mod Update, or Update-all shows no
  progress UI at all, although the jobs themselves run fine (verified: real
  download + install completed). The panel + sticky-error paths work in the
  stubbed Lua tests, so this is in-game-only.
  - **Root cause found Oct 2026** (vanilla `ISUI` sources): PZ's
    `ISUIElement:instantiate()` calls `self:createChildren()` itself, but our
    `WB_ProgressPanel` also called it from `initialise()`, and we called
    `instantiate()` explicitly on top: the panel was built three times with
    discarded Java peers and never rendered. Fixed by building a plain
    `ISPanel` exactly like the working buttons (no custom derive class);
    the download dialog had the same triple-build and now builds once via
    `buildControls()`.
  - **Still not rendering (user report Oct 2026)** - only buttons and the
    per-mod "Updating..." text appear. New lead: the panel was built LAZILY
    inside the pcall'd tick callback, so any construction failure was
    swallowed silently and the module-level singleton stayed poisoned
    forever; the working dialog/buttons are all built during normal UI
    construction. Restructured: one panel per Mods screen
    (`ms.wbProgressPanel`), built EAGERLY in the menu hook with the dialog's
    proven new->initialise->instantiate->addChild order, plus a diagnostic
    log line at creation (coords + javaObject presence).
  **Needs in-game verification** - copy the new Lua, open the Mods menu,
  check the log for the "progress panel created" line, then run a
  check/download and confirm the panel appears. If it still doesn't render,
  the log line will say which step broke.
  - **Deeper problem found Oct 2026**: job `onDone` callbacks NEVER fire
    in-game (no "complete"/"failed" log lines; per-mod "Updating..." stuck
    forever). The tick poll shows no state transitions at all, although the
    Java contract is verified correct. Suspect `Events.OnTick` doesn't fire
    (or our handler doesn't run) while the Mods menu is open - it's a
    main-menu screen. Added a temporary poll heartbeat
    (`poll heartbeat: tick=N activeJobs=M`, every ~10s) plus a fallback
    pump: the menu instance's per-frame `update()` now also drives
    `WB_PollJobs` (idempotent, double-pumping harmless). Next in-game run
    will show whether the tick fires (heartbeat lines) and the fallback
    should make jobs complete regardless.
  - **Resolved Oct 2026**: with the update() fallback pump in place, the
    poll delivers: `nil -> running -> done` transitions fire and completion
    lines print in-game. Heartbeat kept but quieted (only with active
    jobs). Panel confirmed rendering visually by user.
  - Check/update-all now flash their result summary ("Everything is up to
    date" / "N mod(s) have updates") instead of going silent on success.
- [ ] **Mod id vs folder-name mismatch (bug, fixed Oct 2026).** A mod whose
  folder name differs from its mod.info `id=` (e.g. author typo: folder
  "True Weigth", id "TrueWeight") showed "Unknown workshop ID". Root cause:
  `readModId` didn't know the B42 `42.0/mod.info` layout, so it fell back to
  the folder name when recording. Fixed: added `42.0/mod.info` to the
  candidates, plus a self-healing reverse lookup (`Backend.getWorkshopId`)
  that repairs stale folder-name entries on the spot by scanning mod.info.
  Existing bad entries fix themselves on next lookup; no map wipe needed.
- [x] **Per-mod button title reflects state (Oct 2026).** "Update" when a
  check found an update for that mod, "Force update" otherwise (clicking
  always re-downloads; per-mod update never checks first). The visible mod
  panel refreshes on check completion without reselecting.
- [x] **Serialized downloads (Oct 2026).** Download-bearing jobs
  (`submitUpdate`, `submitUpdateAll`) run on a dedicated single-thread
  executor; checks stay on the cached pool. A waiting job reports
  "Queued..." until it starts (surfaced in the per-mod label and progress
  panel via the normal message path). Removes the concurrent-steamcmd
  question and the shared-panel flicker entirely.

- [ ] **Per-mod "Updating..." cleared on selection change (minor).**
  The stuck-forever case is fixed (the update() fallback pump delivers
  onDone now). Remaining cosmetic: selecting another mod mid-update and
  coming back clears the "Updating..." label, since `updateView` doesn't
  know about in-flight jobs. Fix if it annoys: track in-progress jobs per
  mod id and render from that in `WB_RefreshModPanel`.

- [ ] **Mod menu UI refresh without restart/lua reload.** After an
  install/update, the Mods menu list should reflect the change. We already
  call `ms:reloadMods()` on completion; verify in-game whether the visible
  list actually refreshes, and if not find the right refresh hook (the game
  may cache the mod list per screen open). Related to the progress-UI bug
  above only in that both are "did anything happen?" UX.

- [ ] **Mod dependencies.** When downloading/updating a mod, detect required
  workshop items and offer to install them too. Example: `3799732653`
  depends on `3171167894`. Open mechanism: Steam's `GetPublishedFileDetails`
  has no dependencies field, so investigate sources (workshop page
  "Required items" section, SteamKit, ...). Then: Java resolves the dep
  list, Lua prompts (install-all vs pick), jobs install each dep like a
  normal download.

- [ ] **"Open in Workshop" button.** Per-mod button opening the item's
  workshop page in the system browser. Lua can't launch browsers (Kahlua has
  no `os.execute`), so this is a Java-side `ProcessBuilder`
  (`xdg-open` / `cmd /c start` / `open`) behind a new Lua global, e.g.
  `wbOpenWorkshopPage(workshopId)`. Show only when a workshop id is known
  (tracked by us or Steam-managed). Mind the posix_spawn/FORK situation on
  the spawn path.

- [ ] **Release checklist (before any public build).**
  - `WB_Config.DEBUG_STUB` is still `true`: without the Java backend the mod
    silently fakes a working UI. Must be off (or hard-gated) for release.
  - Version number in mod.info.
  - Release signing + VirusTotal scan of the jar.
  - GOG/ZombieBuddy install guide with screenshots; Workshop page +
    release-ready README install instructions.
- [ ] **GitHub releases for WorkshopBridge.** Versioned, downloadable
  releases of the mod itself. Depends on: release checklist above.
  (May never get done.)
- [ ] **Self update check.** The mod checks GitHub releases for a newer
  version of itself and tells the user. Depends on: GitHub releases.
  (May never get done.)
- [ ] **Auto update.** Download and install the new version itself.
  Depends on: self update check. (May never get done.)
