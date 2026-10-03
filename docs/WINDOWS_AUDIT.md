# Windows compatibility audit — WorkshopBridge

**Date:** 2026-10-03 · **Scope:** audit only, no code changed · **Repo:** `~/workspace/workshopbridge`
**Read:** `docs/ARCHITECTURE.md`, `PLAN.md`, `README.md`, `docs/RESEARCH.md`, `TESTING.md`,
all of `WorkshopBridge/java-src/src/main/java/com/workshopbridge/`, the Lua UI in
`WorkshopBridge/42/media/lua/client/WorkshopBridge/`, `tests/java/` (incl. `fakebin/`).

## Verdict

The default flow (automatic steamcmd bootstrap, no manual config) is structurally
Windows-capable: the code already branches on `isWindows()` for the CDN artifact
(`steamcmd.zip`), extraction (`unzip`), the binary name (`steamcmd.exe`), and the
executable-bit check. No P0 *code* breakage was found in the happy path. The real
Windows risks are: (a) wrong Linux-specific advice on Windows failure paths, (b) a
UTF-8 BOM silently dropping a hand-written `steamcmd.path`, (c) Windows file
locking vs. the atomic install/cleanup, and (d) docs gaps for a non-developer
tester (notably: the Windows-GOG ZombieBuddy manual install has no verbatim steps
anywhere, and it is unconfirmed whether the buddy is even on GOG).

Verified in this audit (no Windows machine needed):
- `https://client-update.steamstatic.com/installer/steamcmd.zip` downloads and
  contains exactly one file, `steamcmd.exe`, at the archive root — so
  `installArchive(win=true)` → `unzip` → `steamcmd.exe` resolves correctly
  (`SteamCmd.java:634-660`).
- `steamcmd.exe +quit` prints `Steam Console Client (c) Valve Corporation` on
  Windows (multiple independent public sources, incl. redirected/non-TTY output),
  so the validation fingerprint in `validateExecutable`
  (`SteamCmd.java:505-520`) matches on Windows.

---

## P0 — blocks the Windows test

### P0-1. Confirm the buddy is on GOG Windows, not Steam
On a Steam install every mod reports the game's own workshop ID, so WorkshopBridge
shows "Managed by Steam" on all rows, the map stays empty, and checks trivially
report "Everything is up to date". The whole test (bootstrap, download, update)
is meaningless there — the mod exists for non-Steam players. If the buddy is on
Steam Windows, the test plan needs rethinking, not the code.
*Fix proposal:* confirm with the buddy before writing the Windows docs; if Steam,
either skip him or scope his test to "mod loads, shows Managed by Steam, doesn't
error".

### P0-2. No verbatim Windows-GOG ZombieBuddy install steps exist
`docs/RESEARCH.md:23` notes "Windows installer exists; GOG users need the manual
route — copy JAR + native lib, inject `-agentlib:`/`-javaagent:` JVM flag; **verify
on a real GOG install**". The README just says "Install ZombieBuddy (one-time)".
A non-developer cannot follow the manual route from that. Without ZombieBuddy the
mod only shows the in-game guidance label (`WB_HookModsMenuNoApi`), so the buddy
test stalls at step zero.
*Fix proposal:* write the Windows-GOG ZB install as numbered Notepad-level steps
(where the ZB files come from, exactly where they go, how the GOG launch target
gets the `-javaagent:` flag — GOG Galaxy launch options vs. a shortcut), and have
the buddy follow them verbatim as a doc test (already listed in PLAN.md).

---

## P1 — wrong or degraded behavior on Windows

### P1-1. `missing32BitHint` gives Linux package advice on Windows
`SteamCmd.java:391-411`. The 1-arg overload sniffs `isNixOS()`; on Windows it
falls into the generic branch and tells the user to run
`sudo apt install lib32gcc-s1 lib32stdc++6`. It is appended to real failure
messages in `findExecutable()` (`SteamCmd.java:136`, broken `steamcmd.path`
override) and `bootstrapIn()` (`SteamCmd.java:624`, bootstrapped copy fails
validation) — i.e. exactly when a Windows user is already stuck.
*Fix proposal:* check `isWindows()` first and return a Windows-appropriate hint:
Windows Defender / SmartScreen quarantining `steamcmd.exe`, right-click →
Properties → "Unblock" on a downloaded exe, re-download if corrupt
(`CreateProcess error=193`), allow-list the `workshop_cache\steamcmd` folder.

### P1-2. UTF-8 BOM in `workshopbridge.properties` silently drops `steamcmd.path`
`SteamCmd.java:867-881` (`readOverride`) uses `Properties.load(InputStream)`,
which does not strip a BOM. Windows Notepad saves UTF-8 *with* BOM by default,
so a hand-written file yields the key `\uFEFFsteamcmd.path`; `getProperty`
returns null and the mod quietly bootstraps its own copy instead of using the
user's exe. Confusing, hard to diagnose.
*Fix proposal:* read the file as UTF-8 text, strip a leading `\uFEFF`, then
`p.load(new StringReader(...))`. (CRLF alone is fine — `Properties` handles it.)

### P1-3. Windows file locking vs. atomic install and cleanup
`ModInstaller.java:82-150` (`atomicReplace`), `:257-270` (`deleteRecursiveQuiet`).
The swap itself is fail-safe on Windows (NTFS renames are atomic; a failed first
move leaves the old tree untouched and the job just errors). The exposure is
*cleanup and retries*: Windows Defender real-time scanning and the game's own
file watcher can briefly hold handles inside the backup/staging trees, so
`deleteRecursiveQuiet` logs "could not delete" and leaves `*.old-TAG` dirs behind,
or a mid-swap move throws `AccessDeniedException` and the whole install job fails
even though nothing is corrupt.
*Fix proposal:* retry deletes/moves a few times with short backoff before giving
up (Windows AV holds are transient); the existing `recoverInterruptedInstalls`
already repairs leftovers on the next run, so this is about avoiding scary
job failures, not data loss.

### P1-4. MAX_PATH (260 chars) for deeply nested mod files
`ModInstaller.java:233-255` (`copyRecursive`). Workshop mods with very deep trees
can exceed Windows' legacy 260-char limit; whether it bites depends on the
PZ-bundled JRE's manifest (`longPathAware`) and the `LongPathsEnabled` registry
key — neither verified. Failure mode is a failed install (`IOException`), not
corruption.
*Fix proposal:* document the limitation; on failure, the job message could
suggest enabling long paths (`Computer\HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\FileSystem\LongPathsEnabled`).

### P1-5. `isExecDenied` doesn't recognize Windows access-denied
`SteamCmd.java:593-602` matches `permission denied` / `error=13` / `error: 13`
(Unix). On Windows the equivalents are `CreateProcess error=5` (access denied)
and `error=193` (not a valid Win32 application). The `bootstrap()` retry loop
(`SteamCmd.java:605-630`) therefore never retries in the fallback dir on Windows.
Low impact in practice (no `noexec` mounts on Windows), but the matcher is
Unix-only by accident.
*Fix proposal:* also match `error=5` / `error=193` (with a comment that 193
usually means a corrupt download, not a permissions problem).

### P1-6. Fallback steamcmd dir is `~/.cache` on Windows
`SteamCmd.java:176-187` (`fallbackSteamCmdDir`). Works (`C:\Users\<name>\.cache\…`)
but is non-idiomatic; the Windows convention is `%LOCALAPPDATA%`.
*Fix proposal:* on Windows prefer `%LOCALAPPDATA%\workshopbridge\steamcmd`,
falling back to `~/.cache` when the env var is absent.

### P1-7. Java test suite cannot run on native Windows
`tests/java/run.sh` is bash, and `tests/java/fakebin/steamcmd.sh` is a shell
script. Worse, even a `.bat` fake wouldn't work: `ProcessBuilder` cannot execute
batch files directly on Windows (`CreateProcess error=193` — needs `cmd /c`),
while the real `steamcmd.exe` is a true exe. So Windows regressions in the Java
backend are invisible to the current harness.
*Fix proposal:* document WSL2 as the dev-test route for the Java suite; the
buddy's manual test remains the Windows coverage. (Longer term: a tiny fake
`steamcmd.exe` or a `cmd /c` shim in the launch path when the candidate ends
with `.bat`/`.cmd`.)

---

## P2 — polish and docs

### P2-1. FORK launch-mechanism flag is set (and messaged) on Windows too
`Main.java:34-48` (`ensureForkLaunchMechanism`). `jdk.lang.Process.launchMechanism`
is only read by the Unix `ProcessImpl`; on Windows it is a harmless no-op — but
the log line "process launch mechanism set to FORK (posix_spawn is unreliable in
some sandboxes)" prints on Windows where it is meaningless and confusing.
*Fix proposal:* skip the set (or at least the message) when `os.name` contains
"win".

### P2-2. `readModId` uses the platform default charset
`ModInstaller.java:203-231` uses `new FileReader(f)`. Mod ids are ASCII by
convention so this is low-risk, but be explicit: `Files.newBufferedReader(path,
StandardCharsets.UTF_8)`. (Related edge: a UTF-8 BOM at the start of `mod.info`
defeats the `id=` match when it is the first line — same BOM theme as P1-2.)

### P2-3. steamcmd.exe console encoding is not UTF-8
`SteamCmd.java:276-299` (download drainer) and `:484-500` (validation drainer)
decode stdout as UTF-8. On Windows the console code page is typically 437/850 or
windows-1252, so non-ASCII mod names/titles mojibake in the game log and in
failure "output tails". Cosmetic.
*Fix proposal:* on Windows decode with `Charset.defaultCharset()` instead of
hardcoded UTF-8.

### P2-4. Possible console-window flash when spawning steamcmd.exe
`ProcessBuilder` on Windows does not pass `CREATE_NO_WINDOW`, so launching
`steamcmd.exe` from a GUI game *may* flash a console window during validation and
downloads. Cosmetic; no behavior impact.

### P2-5. Map save vs. open editors
`WorkshopMap.java:118-148`: the atomic write-then-move fails on Windows if the
user has `workshopbridge_map.json` open in an editor (replace-while-open is
denied); the catch logs "map save failed" and the in-memory state is kept, so the
next save retries. Self-healing; noted for completeness.

### P2-6. README/docs gaps for the Windows tester
- No Windows **Troubleshooting** section (README's is Linux-only: posix_spawn,
  noexec). Needed: Defender quarantine/SmartScreen on `steamcmd.exe`, the
  Properties → Unblock checkbox, `%LOCALAPPDATA%` fallback location.
- `workshopbridge.properties` via Notepad: must save as "All files" (not
  `workshopbridge.properties.txt`) and UTF-8 *without* BOM (see P1-2).
- Spell out reaching the Zomboid folder: Win+R → `%USERPROFILE%` → open `Zomboid`.
- `TESTING.md`: note the offline suites don't run on native Windows (P1-7);
  point developers at WSL2.

---

## Checked and cleared (no action)

- **Bootstrap artifact**: `cdnUrl(true)` → `steamcmd.zip`; `unzip` has zip-slip
  protection; the exe is expected at the archive root and Valve ships exactly
  that (verified by download).
- **Validation fingerprint**: `steamcmd.exe +quit` prints "Steam Console Client"
  on Windows (public sources) — matches `SteamCmd.java:505-520`.
- **First-run self-update vs. 30s validation timeout**: `validationOk` treats a
  timeout as usable, so a slow self-update doesn't cause re-bootstraps
  (`SteamCmd.java:373-382`).
- **CRLF**: `BufferedReader.readLine()` strips `\r` (`readModId`), and
  `java.util.Properties` handles CRLF (`readOverride`) — only the BOM is a
  problem (P1-2).
- **Spaces in paths**: `ProcessBuilder` quotes argv elements itself; no manual
  quoting anywhere. (`+force_install_dir` receives the dir as a single argv
  element, so spaces are safe — the "quote it" advice in old forum guides is
  about interactive use.)
- **Unix-only code is guarded**: `sh -c "command -v steam-run"` runs only when
  `isNixOS()`; `/etc/os-release` read is exception-guarded; `LAUNCH_PREFIX` is
  empty off NixOS; `posixSpawnHint` can't fire on Windows (message never
  contains `posix_spawn`); the executable-bit check is skipped on Windows
  (`SteamCmd.java:470-480`).
- **Atomicity primitives exist on Windows**: `ATOMIC_MOVE` with
  `REPLACE_EXISTING` fallback in `WorkshopMap.save` and `ModInstaller.moveAtomic`;
  moves never target an existing directory; staging stays on the same volume as
  `mods/` (the `~/.cache` fallback is binary-only).
- **Lua side**: no `os.`/`io.`/shell/path assumptions anywhere in
  `WB_*.lua` (Kahlua exposes almost none); `wbGetSteamCmdPath` is exposed but
  unused by the UI.
- **`Backend.resolveZomboidDir`**: falls back to `~/Zomboid`, which on Windows
  is `%USERPROFILE%\Zomboid` — same as the primary lookup.

## Could not verify without a Windows machine

- End-to-end bootstrap → download → install on a real Windows GOG install
  (the buddy test; PLAN.md already lists: steamcmd.exe bootstrap, paths with
  spaces, fresh-profile first-run).
- `ZomboidFileSystem.getCacheDir()` return value on Windows GOG (README asserts
  `%USERPROFILE%\Zomboid`).
- First-run self-update wall time on a typical home connection vs. the 30s
  validation leniency.
- Magnitude of Defender real-time-scanning interference (slowness, locks).
- MAX_PATH behavior with the PZ-bundled JRE (manifest + registry dependent).
- The Windows-GOG ZombieBuddy manual install flow (P0-2).

## Suggested buddy checklist (supplement to PLAN.md's Windows items)

1. Confirm GOG (not Steam) install first.
2. Follow the new verbatim ZB install doc; mod must show Update buttons, not the
   guidance label.
3. Click Update on one mod: expect automatic steamcmd bootstrap (~1 min,
   self-update), then a successful install; watch for Defender prompts.
4. `Check for updates` on a 5-mod item (e.g. 3393821407): expect "1 update
   available" / "Update all (1)".
5. Update via one mod's panel: sibling badges must clear.
6. Paths with spaces (Windows username with a space is the realistic case).
7. Offline run: expect the friendly "Couldn't reach Steam's servers" message,
   not a stack trace.
