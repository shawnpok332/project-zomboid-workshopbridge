# WorkshopBridge research notes

Condensed validation research. Full per-topic reports were produced Oct 2026; this is the durable summary.

## 1. Pure-Lua steamcmd execution: impossible

Project Zomboid embeds a modified **Kahlua** Lua interpreter. Three independent blockers:

- No `os.execute` (never implemented in Kahlua) and no `io` library at all.
- Java interop is whitelist-only via `LuaManager.Exposer`; `java.lang.Runtime` / `ProcessBuilder` are not exposed, and newer builds gate reflection workarounds (`LuaManager.validateReflectionAccess`).
- No sockets/HTTP in Lua; file I/O sandboxed to `getFileWriter`/`getFileReader` under `Zomboid/Lua/` only.

Note: scanners (pzmm) flag the *string* `os.execute` in mod code as a malware indicator - that doesn't mean the call executes, only that its presence is suspicious. Don't go down this hole.

Sources: [pz-modding-guide](https://github.com/cocolabs/pz-modding-guide) (Exposer whitelist), [LuaManager JavaDocs](https://projectzomboid.com/modding/zombie/Lua/LuaManager.html), [pz-save-manager](https://github.com/chpomob/pz-save-manager) ("impossible from inside the game").

## 2. ZombieBuddy: viable Java→Lua bridge

- Repo: [zed-0xff/ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) (MIT, active, single maintainer).
- Mod author flow: `require=\ZombieBuddy` + `javaJarFile=media/java/YourMod.jar` + `javaPkgName=...` in mod.info; Gradle-built JAR in `media/java/` (B42 versioned layout: `42/media/java/`).
- Exposure: `@Exposer.LuaClass` on a class, or `@LuaMethod(name=..., global=true)` for plain globals. Java mods are "completely unrestricted" per its docs - `ProcessBuilder` is supported by design, not a hack.
- Trust UX: native approval dialog (mod id, JAR path, SHA-256) before loading new/changed JARs; optional ZBS Ed25519 signing.
- Constraints: **B42-only**; players install ZombieBuddy separately (Windows installer exists; GOG users need the manual route - copy JAR + native lib, inject `-agentlib:`/`-javaagent:` JVM flag; **verify on a real GOG install**); we use zero `@Patch`es, only the Lua-exposure surface.
- Precedent: [ZBBetterWorkshopUpload](https://github.com/zed-0xff/zbetterworkshopupload) (Java does file I/O, Lua does UI); [AnimatedVehiclePartsFix](https://github.com/meowwoem/pzmods-animatedvehiclepartsfix) ships a JAR + `SECURITYCHECK.MD` with VirusTotal link (good release template).
- Alternatives considered: Leaf (heavier platform install), Storm (requires its launcher - wrong fit for vanilla/GOG), pz_launcher's Java API (only works inside pz_launcher).

## 3. steamcmd workshop downloads for PZ

- App ID **108600**. Command: `steamcmd +login anonymous +workshop_download_item 108600 <workshopID> +quit` (Linux: `./steamcmd.sh`).
- **Anonymous login works** for PZ workshop items (confirmed by multiple PZ server/downloader guides). It's a per-app Steamworks setting, so keep an interactive account-login fallback; never store credentials.
- Files land in `<steamcmd>/steamapps/workshop/content/108600/<workshopID>/`. Use `+force_install_dir <dir>` **before** `+login` to redirect into our own cache dir.
- Layout inside: `mods/<ModID>/mod.info` (+ `preview.png`, `workshop.txt`). Install = copy inner `mods/*` into `Zomboid/mods/`. The `id=` line in `mod.info` is the ID the game uses.
- Mod folders: Windows `%USERPROFILE%\Zomboid\mods`, Linux/macOS `~/Zomboid/mods`.
- Windows: portable zip from Valve (`client-update.steamstatic.com/installer/steamcmd.zip`), self-updates on first run. Detection (historical note, superseded): this originally said PATH + common locations + user-configured path. The implementation deliberately does NOT search PATH or guess locations (a wrong steamcmd is worse than none); it checks the configured `steamcmd.path` override, then its own managed copy under the workshop cache (bootstrapping from Valve's CDN if absent). See `docs/ARCHITECTURE.md` ("First run / missing pieces").
- Linux: `steamcmd_linux.tar.gz` + 32-bit libs (`lib32gcc-s1`, `lib32stdc++6` on Debian/Ubuntu). No sudo needed for steamcmd itself.
- Gotchas: first-run self-update is slow; keep `+quit` or the process hangs; retry on transient failures.

## 4. Workshop metadata without an API key

- `ISteamRemoteStorage/GetPublishedFileDetails/v1/` is **keyless** (POST `itemcount` + `publishedfileids[N]`) and returns title, description, `time_updated`, etc. - enough for update checks.
- `IPublishedFileService/QueryFiles` (real search) **requires** a (free) Steam API key. Search UI is out of scope for now; the paste-ID/update flow doesn't need it.

## 5. Prior art (don't reinvent)

- [lumi-nary/zomboid-mod-downloader](https://github.com/lumi-nary/zomboid-mod-downloader) - Tauri app: browse/queue/batch-download via SteamCMD, installs into place. (Possibly Windows-only releases.)
- [unjammer/PZ_Launcher](https://github.com/unjammer/PZ_Launcher) - launcher with SteamCMD workshop tab, GOG-aware. Windows-only.
- [paraxaqq/pzmm](https://github.com/paraxaqq/pzmm) - mod manager with workshop browser + security scanner. Its scanner is why we plan signing + VirusTotal per release.

## 7. Built-in workshop code: not reusable (decompile analysis)

Source: game decompile (`gameStates/ConnectToServerState.java`, `core/znet/SteamWorkshop.java`, `gameStates/ChooseGameInfo.java`, `ZomboidFileSystem.java`).

- The `item.update()` the user spotted is the **multiplayer server-join flow**: the server sends required workshop item IDs + timestamps, the client subscribes/downloads them via Steamworks native calls (`SubscribeItem`, `DownloadItem`, `CreateQueryUGCDetailsRequest`, `GetItemState`). It is server-driven and fully automatic - the "break mods if you aren't careful" shape.
- Everything hangs off `SteamWorkshop.instance`, which only initializes when `-Dzomboid.steam=1` (`SteamUtils.isSteamModeEnabled()`; `SteamUtils.java:39`). With `-Dzomboid.steam=0` (GOG) the entire built-in workshop machinery is dead code. **Verdict: do not reuse/patch; the steamcmd approach stands.**
- The per-mod `workshopId` storage (`ChooseGameInfo.Mod`, line 477) is **derived from directory layout, not stored anywhere**: grandparent dir of the mod folder must be numeric (`SteamUtils.isValidSteamID`) → `workshopId = dirName`, `source = "Steam"`. The folder sources producing such layouts (`getInstalledItemModsFolders` → `steamapps/workshop/content/108600/<wsID>/mods`, `getStagedItemModsFolders`) are likewise Steam-gated.
- On GOG the only live mod source is the flat `Zomboid/mods` scan, which is **one level deep** (`getAllModFoldersAux`). Installing nested as `Zomboid/mods/<wsID>/<modID>/` would make mods **invisible to the game**. So we keep our own `workshopbridge_map.json`.
- Useful residue: our Java side can still *read* `ChooseGameInfo.getModDetails(modId).getWorkshopID()` as a supplementary signal - three-state row UI: in our map → "Update"; game's ID non-empty → "Managed by Steam"; else → "Unknown workshop ID".

## 6. Mods-screen UI hook (verified for B42)

- Screen class: `ModSelector` (`ISPanelJoypad`), `media/lua/client/OptionScreens/ModSelector/`; singleton `ModSelector.instance`; opened via `MainScreen:onClickModList()`.
- Rows are **drawn, not widget-composed**: `ModListBox:doDrawItem(y, item, alt)` renders each row; `item` is a `ModData` table with `item.modId` = the mod.info `id=`.
- **No dedicated event** for the Mods screen - method-wrapping is the recipe: wrap `ModSelector:create` (add "Update all" anchored to `backButton`; per-instance `doDrawItem` wrap for row status text), wrap `ModInfoPanel:createChildren` + `updateView(modInfo)` (per-mod Update button / "Unknown workshop ID" label).
- Design decision: status *text* in rows, the real per-mod Update *button* in `ModInfoPanel` (per-row buttons would need manual hit-testing).
- Bonus: engine Java `ChooseGameInfo.Mod.getWorkshopID()` exists but is empty for non-Steam mods - our Java-side map stays authoritative.
- Verify in-game (Phase 2): whether `reloadMods()` recreates the `ModListBox` instance; `ModInfoPanel` geometry; B41 screen class unknown (out of scope - B42-only project).
- Sources: [PZ-Umbrella type stubs](https://github.com/PZ-Umbrella/Umbrella/tree/master/library/lua/client/OptionScreens/ModSelector) (mirror the vanilla B42 Lua tree), [ChooseGameInfo.Mod Javadocs](https://projectzomboid.com/modding/zombie/gameStates/ChooseGameInfo.Mod.html).
