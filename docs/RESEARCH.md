# WorkshopBridge research notes

Condensed validation research. Full per-topic reports were produced Oct 2026; this is the durable summary.

## 1. Pure-Lua steamcmd execution: impossible

Project Zomboid embeds a modified **Kahlua** Lua interpreter. Three independent blockers:

- No `os.execute` (never implemented in Kahlua) and no `io` library at all.
- Java interop is whitelist-only via `LuaManager.Exposer`; `java.lang.Runtime` / `ProcessBuilder` are not exposed, and newer builds gate reflection workarounds (`LuaManager.validateReflectionAccess`).
- No sockets/HTTP in Lua; file I/O sandboxed to `getFileWriter`/`getFileReader` under `Zomboid/Lua/` only.

Note: scanners (pzmm) flag the *string* `os.execute` in mod code as a malware indicator — that doesn't mean the call executes, only that its presence is suspicious. Don't go down this hole.

Sources: [pz-modding-guide](https://github.com/cocolabs/pz-modding-guide) (Exposer whitelist), [LuaManager JavaDocs](https://projectzomboid.com/modding/zombie/Lua/LuaManager.html), [pz-save-manager](https://github.com/chpomob/pz-save-manager) ("impossible from inside the game").

## 2. ZombieBuddy: viable Java→Lua bridge

- Repo: [zed-0xff/ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) (MIT, active, single maintainer).
- Mod author flow: `require=\ZombieBuddy` + `javaJarFile=media/java/YourMod.jar` + `javaPkgName=...` in mod.info; Gradle-built JAR in `media/java/` (B42 versioned layout: `42/media/java/`).
- Exposure: `@Exposer.LuaClass` on a class, or `@LuaMethod(name=..., global=true)` for plain globals. Java mods are "completely unrestricted" per its docs — `ProcessBuilder` is supported by design, not a hack.
- Trust UX: native approval dialog (mod id, JAR path, SHA-256) before loading new/changed JARs; optional ZBS Ed25519 signing.
- Constraints: **B42-only**; players install ZombieBuddy separately (Windows installer exists; GOG users need the manual route — copy JAR + native lib, inject `-agentlib:`/`-javaagent:` JVM flag; **verify on a real GOG install**); we use zero `@Patch`es, only the Lua-exposure surface.
- Precedent: [ZBBetterWorkshopUpload](https://github.com/zed-0xff/zbetterworkshopupload) (Java does file I/O, Lua does UI); [AnimatedVehiclePartsFix](https://github.com/meowwoem/pzmods-animatedvehiclepartsfix) ships a JAR + `SECURITYCHECK.MD` with VirusTotal link (good release template).
- Alternatives considered: Leaf (heavier platform install), Storm (requires its launcher — wrong fit for vanilla/GOG), pz_launcher's Java API (only works inside pz_launcher).

## 3. steamcmd workshop downloads for PZ

- App ID **108600**. Command: `steamcmd +login anonymous +workshop_download_item 108600 <workshopID> +quit` (Linux: `./steamcmd.sh`).
- **Anonymous login works** for PZ workshop items (confirmed by multiple PZ server/downloader guides). It's a per-app Steamworks setting, so keep an interactive account-login fallback; never store credentials.
- Files land in `<steamcmd>/steamapps/workshop/content/108600/<workshopID>/`. Use `+force_install_dir <dir>` **before** `+login` to redirect into our own cache dir.
- Layout inside: `mods/<ModID>/mod.info` (+ `preview.png`, `workshop.txt`). Install = copy inner `mods/*` into `Zomboid/mods/`. The `id=` line in `mod.info` is the ID the game uses.
- Mod folders: Windows `%USERPROFILE%\Zomboid\mods`, Linux/macOS `~/Zomboid/mods`.
- Windows: portable zip from Valve (`client-update.steamstatic.com/installer/steamcmd.zip`), self-updates on first run. Detection = PATH + common locations + user-configured path.
- Linux: `steamcmd_linux.tar.gz` + 32-bit libs (`lib32gcc-s1`, `lib32stdc++6` on Debian/Ubuntu). No sudo needed for steamcmd itself.
- Gotchas: first-run self-update is slow; keep `+quit` or the process hangs; retry on transient failures.

## 4. Workshop metadata without an API key

- `ISteamRemoteStorage/GetPublishedFileDetails/v1/` is **keyless** (POST `itemcount` + `publishedfileids[N]`) and returns title, description, `time_updated`, etc. — enough for update checks.
- `IPublishedFileService/QueryFiles` (real search) **requires** a (free) Steam API key. Search UI is out of scope for now; the paste-ID/update flow doesn't need it.

## 5. Prior art (don't reinvent)

- [lumi-nary/zomboid-mod-downloader](https://github.com/lumi-nary/zomboid-mod-downloader) — Tauri app: browse/queue/batch-download via SteamCMD, installs into place. (Possibly Windows-only releases.)
- [unjammer/PZ_Launcher](https://github.com/unjammer/PZ_Launcher) — launcher with SteamCMD workshop tab, GOG-aware. Windows-only.
- [paraxaqq/pzmm](https://github.com/paraxaqq/pzmm) — mod manager with workshop browser + security scanner. Its scanner is why we plan signing + VirusTotal per release.

## 6. UI hook (pending)

Main-menu button: `Events.OnMainMenuEnter` + `MainScreen.instance` (or wrap `MainScreen.create`). For this project we target the **Mods screen** instead — exact B42 screen/row class names under research.
