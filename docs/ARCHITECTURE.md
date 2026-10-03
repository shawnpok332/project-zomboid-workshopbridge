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

## Lua ↔ Java contract (planned)

Exposed as **plain Lua globals** (`wbIsAvailable()` etc.) - Phase 3 must honor
these exact names, e.g. via `@LuaMethod(name = "wbIsAvailable", global = true)`.

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
    state = "running" | "done" | "failed" | "cancelled",
    done = 2, total = 5,            -- items processed (for Update all)
    message = "Downloading 123456789 (3/5)…",
    error = nil | "steamcmd not found",
}
```

Jobs run on Java background threads. Lua never blocks waiting on them.

## workshopbridge_map.json (proposed)

```json
{
  "version": 1,
  "workshopItems": {
    "123456789": {
      "modIds": ["ModA", "ModB"],
      "timeUpdated": 1727745600,
      "lastDownloaded": 1727745700
    }
  }
}
```

- Written only by the Java side, after a successful download+move.
- `modIds` parsed from each downloaded `mod.info` (`id=` line). A workshop item can contain multiple mods - hence the list.
- Reverse lookup (modID → workshopID) is derived by inverting the map in memory.
- `timeUpdated` comes from `GetPublishedFileDetails`; compared against the workshop on update checks.

## Flows

### Check for updates
1. Lua: **Check for updates** button → `wbCheckForUpdates()` → jobId.
2. Java: for each mapped workshop item, `GetPublishedFileDetails` → compare `time_updated` vs stored `timeUpdated`. No downloads.
3. Lua polls; on completion, rows with available updates show an "Update available" badge, and **Update all** becomes "Update all (n)".

### Single mod update
1. Lua: row button → `wbGetWorkshopId(modId)` → `wbUpdateMod(workshopId)` → jobId.
2. Lua polls `wbGetJobStatus(jobId)` on tick, shows progress on the row.
3. Java: download → move into `Zomboid/mods/` (replace existing) → update map → job `done`.

### Update all
1. Lua: **Update all** button → `wbUpdateAll()` → jobId.
2. Java: for each mapped workshop item, `GetPublishedFileDetails` → if `time_updated > timeUpdated`, download+move+update map. Job reports `done/total`.
3. Lua: refresh the Mods list when the job completes.

### Download a new mod
1. Lua: **Download** button → dialog takes a workshop ID or URL → `WB_ParseWorkshopId` → `wbUpdateMod(workshopId)` → jobId. (The Java side treats untracked ids the same as updates: download → move into `Zomboid/mods/` → record in map.)
2. Lua polls `wbGetJobStatus(jobId)` on tick with the progress panel; on completion the Mods list is reloaded so the new mod appears, and it is tracked from then on.

### First run / missing pieces
- ZombieBuddy not installed → Lua detects `wbIsAvailable() == false` (globals missing) → Mods menu shows install guidance instead of Update buttons.
- steamcmd not found → the Java side **bootstraps it automatically** from Valve's CDN into `Zomboid/workshop_cache/steamcmd/` (inside the download job, with progress). No system-wide discovery: either `steamcmd.path` in `Zomboid/workshopbridge.properties` (validated by execution, always wins) or the previously bootstrapped managed copy. `wbGetSteamCmdPath()` returns nil only when neither exists yet.

## UI placement (B42, verified in-game Oct 2026)

- **Update all** + **Check for updates**: wrapped `ModSelector:create`; the buttons join vanilla's bottom-right cluster (MapsOrder, ModsOrder, Accept), anchored right+bottom with the same font and sizing flags vanilla uses. (First attempt anchored them left of the Back button, which is bottom-left - they rendered offscreen.)
- **Per-row**: wrap `ModListBox:doDrawItem` per instance; draw status text keyed by `item.modId`. Rows are not widget-composed, so no per-row buttons (would need manual hit-testing).
- **Wrapper rule: always propagate return values.** Vanilla `prerender` does `v.height = y2 - y` where `y2 = self:doDrawItem(...)`; our first wrapper dropped the return and the menu rendered black with `__sub not defined for operands` thrown every frame. Wrapping a vanilla method means forwarding args AND returns.
- **Per-mod Update button / status label**: `ModInfoPanel` - `createChildren()` once, `updateView(modInfo)` per selection.
- Row states (three): in our map → "Update" (+ "Update available" badge after a check); game's `getWorkshopID()` non-empty → "Managed by Steam"; else grey "Unknown workshop ID".
- Wrapping is idempotent per instance and re-applied after `reloadMods()` (defensive re-hook in the update-all completion handler).

## Design constraints

- **B42 only** (ZombieBuddy requirement).
- **Zero bytecode patches**: we use only ZombieBuddy's Lua-exposure surface, its most stable API.
- **Never store Steam credentials.** Anonymous steamcmd login is the default; if it's ever rejected, fall back to an interactive user login (Steam Guard via the user's own terminal), never persisted.
- **Mods load at game start** - updating files while sitting in the Mods menu is safe; changes apply on next new game / continue.
