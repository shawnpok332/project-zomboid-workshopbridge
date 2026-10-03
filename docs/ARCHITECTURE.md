# WorkshopBridge architecture

## Components

```
┌─────────────────────────────┐
│  Lua UI (client)            │  WB_Main.lua, WB_ModsMenu.lua, WB_Jobs.lua
│  - "Update all" button      │  Runs on the game thread. Never blocks:
│  - per-mod Update /         │  all Java calls are fire-and-poll.
│    "Unknown workshop ID"    │
└──────────────┬──────────────┘
               │ ZombieBuddy-exposed Java API
               │ (@Exposer.LuaClass / @LuaMethod global)
┌──────────────▼──────────────┐
│  Java backend               │  SteamCmdApi.java (exposed)
│  - SteamCmd: detect + spawn │  JobManager, WorkshopMap, SteamCmd
│    steamcmd (ProcessBuilder) │
│  - file moves Zomboid/mods/ │
│  - update checks via        │
│    GetPublishedFileDetails  │
└──────────────┬──────────────┘
               │ files
┌──────────────▼──────────────┐
│  Zomboid/                   │
│  - mods/<ModID>/            │  installed mods (PZ standard layout)
│  - workshopbridge_map.json  │  workshopID -> { modIds, timeUpdated,
│  - workshop_cache/          │    lastDownloaded }  (Java owns this file)
│    (steamcmd download area) │
└─────────────────────────────┘
```

## Lua ↔ Java contract (implemented)

Exposed as **plain Lua globals** (`wbIsAvailable()` etc.) via
`@LuaMethod(name = ..., global = true)`.

| Function | Args | Returns |
|---|---|---|
| `wbIsAvailable()` | - | `true` when the Java side loaded (Lua uses this to detect ZombieBuddy presence) |
| `wbGetSteamCmdPath()` | - | path string, or `nil` if not detected |
| `wbGetWorkshopId(modId)` | PZ mod id (`mod.info` `id=`) | workshop ID string, or `nil` = "Unknown workshop ID" |
| `wbCheckForUpdates()` | - | starts a job; returns jobId. A done status carries `updates` = list of **modIds** with updates available |
| `wbUpdateMod(workshopId)` | workshop ID | jobId |
| `wbUpdateAll()` | - | jobId (checks, then downloads only outdated items) |
| `wbGetJobStatus(jobId)` | jobId | **JSON string** `{"state","done","total","message"[,"error"][,"updates"]}`, or null for unknown jobs. Lua decodes it with the pure-Lua `WB_Json.lua` (Kahlua's Java return marshaling is deliberately not relied upon). A done check-job carries `updates` = list of **modIds** with updates available |

### Job status shape (JSON string, decoded in Lua by WB_Json)

```lua
{
    state = "running" | "done" | "failed",
    done = 2, total = 5,            -- items processed (for Update all)
    message = "Downloading 123456789 (3/5)…",
    error = nil | "steamcmd not found",
}
```

Jobs run on Java background threads. Lua never blocks waiting on them: it
polls `wbGetJobStatus(jobId)` on `Events.OnTick`, with a fallback pump
driven by the Mods screen's per-frame `update()` (the tick doesn't reliably
fire while a main-menu screen is open). The poll is idempotent, so both
pumps running at once is harmless.

Download-bearing jobs (`wbUpdateMod`, `wbUpdateAll`) run **serialized** on a
dedicated single-thread executor - concurrent steamcmd processes share one
install dir and gain nothing. Checks stay on the cached pool. A download job
waiting its turn reports `"Queued..."` as its message until it starts.

## workshopbridge_map.json

```json
{
  "version": 1,
  "items": {
    "123456789": {
      "modIds": ["ModA", "ModB"],
      "timeUpdated": 1727745600,
      "lastDownloaded": 1727745700
    }
  }
}
```

- Written only by the Java side, after a successful download+move. Saves are
  atomic (write temp + rename); a corrupt file loads as empty rather than
  throwing.
- `modIds` parsed from each downloaded `mod.info` (`id=` line; B42 layouts
  `common/`, `42.0/`, `42/`/`41/`/`40/`, then flat). Falls back to the folder
  name when no mod.info is found. A workshop item can contain multiple mods -
  hence the list.
- Reverse lookup (modID → workshopID) inverts the map in memory, with
  self-healing: on a miss, installed mod folders are scanned and an entry
  recorded under a stale folder name (author typo, or a layout we didn't
  parse at install time) is repaired to the true mod.info id on the spot.
- `timeUpdated` comes from `GetPublishedFileDetails`; compared against the workshop on update checks.

## Flows

### Check for updates
1. Lua: **Check for updates** button → `wbCheckForUpdates()` → jobId.
2. Java: for each mapped workshop item, `GetPublishedFileDetails` → compare `time_updated` vs stored `timeUpdated`. No downloads.
3. Lua polls with the progress panel; on completion, rows with available updates show an "Update available" badge, **Update all** becomes "Update all (n)", the selected mod's panel refreshes in place, and the result summary ("Everything is up to date" / "N mod(s) have updates") flashes briefly. Failures stick in the panel until clicked.

### Single mod update
1. Lua: per-mod button → `wbGetWorkshopId(modId)` → `wbUpdateMod(workshopId)` → jobId. The button reads **Update** when a check flagged the mod, **Force update** otherwise (it always re-downloads; it never checks first).
2. Lua polls with the progress panel; the row label tracks the job ("Updating...", "Queued...", "Up to date" / failure).
3. Java (serialized with other downloads): download → move into `Zomboid/mods/` (replace existing) → update map → job `done`.

### Update all
1. Lua: **Update all** button → `wbUpdateAll()` → jobId.
2. Java: for each mapped workshop item, `GetPublishedFileDetails` → if `time_updated > timeUpdated`, download+move+update map. Job reports `done/total`.
3. Lua: refresh the Mods list when the job completes.

### Download a new mod
1. Lua: **Download** button → dialog takes a workshop ID or URL → `WB_ParseWorkshopId` → `wbUpdateMod(workshopId)` → jobId. (The Java side treats untracked ids the same as updates: download → move into `Zomboid/mods/` → record in map.)
2. Lua polls `wbGetJobStatus(jobId)` on tick with the progress panel; on completion the Mods list is reloaded so the new mod appears, and it is tracked from then on.

### First run / missing pieces
- ZombieBuddy not installed → Lua detects `wbIsAvailable() == false` (globals missing) → Mods menu shows install guidance instead of Update buttons.
- steamcmd not found → the Java side **bootstraps it automatically** from Valve's CDN into `Zomboid/workshop_cache/steamcmd/` (with progress). No system-wide discovery: either `steamcmd.path` in `Zomboid/workshopbridge.properties` (validated by execution, always wins) or the previously bootstrapped managed copy. `wbGetSteamCmdPath()` returns nil only when neither exists yet. If the binary can't execute from the game drive (noexec/sandboxed mount), it is bootstrapped again under `~/.cache/workshopbridge/steamcmd` (or `$XDG_CACHE_HOME`) and retried there.
- Process launching: the mod defaults the JDK to `FORK` process spawning at load (the default `posix_spawn` fails with EACCES inside steam-run's sandbox); the user's explicit `-Djdk.lang.Process.launchMechanism` always wins.

## UI placement (B42, verified in-game Oct 2026)

- **Update all** + **Check for updates**: wrapped `ModSelector:create`; the buttons join vanilla's bottom-right cluster (MapsOrder, ModsOrder, Accept), anchored right+bottom with the same font and sizing flags vanilla uses. (First attempt anchored them left of the Back button, which is bottom-left - they rendered offscreen.)
- **Per-row**: wrap `ModListBox:doDrawItem` per instance; draw status text keyed by `item.modId`. Rows are not widget-composed, so no per-row buttons (would need manual hit-testing).
- **Wrapper rule: always propagate return values.** Vanilla `prerender` does `v.height = y2 - y` where `y2 = self:doDrawItem(...)`; our first wrapper dropped the return and the menu rendered black with `__sub not defined for operands` thrown every frame. Wrapping a vanilla method means forwarding args AND returns.
- **Per-mod Update button / status label**: `ModInfoPanel` - `createChildren()` once, `updateView(modInfo)` per selection. Button title is **Update** when a check flagged that mod, **Force update** otherwise.
- Row states (three): in our map → per-mod button (+ "Update available" badge after a check); game's `getWorkshopID()` non-empty → "Managed by Steam"; else grey "Unknown workshop ID".
- Wrapping is idempotent per instance and re-applied after `reloadMods()` (defensive re-hook in the update-all completion handler).

## Design constraints

- **B42 only** (ZombieBuddy requirement).
- **Zero bytecode patches**: we use only ZombieBuddy's Lua-exposure surface, its most stable API.
- **Never store Steam credentials.** Anonymous steamcmd login is the default; if it's ever rejected, fall back to an interactive user login (Steam Guard via the user's own terminal), never persisted.
- **Mods load at game start** - updating files while sitting in the Mods menu is safe; changes apply on next new game / continue.
