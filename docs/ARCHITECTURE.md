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

Exposed as globals via ZombieBuddy (exact exposure style TBD in Phase 3 —
`@Exposer.LuaClass` table vs `@LuaMethod(global=true)` functions):

| Function | Args | Returns |
|---|---|---|
| `wbIsAvailable()` | — | `true` when the Java side loaded (Lua uses this to detect ZombieBuddy presence) |
| `wbGetSteamCmdPath()` | — | path string, or `nil` if not detected |
| `wbGetWorkshopId(modId)` | PZ mod id (`mod.info` `id=`) | workshop ID string, or `nil` = "Unknown workshop ID" |
| `wbCheckForUpdates()` | — | starts a job; returns jobId. Job result lists workshop IDs with updates |
| `wbUpdateMod(workshopId)` | workshop ID | jobId |
| `wbUpdateAll()` | — | jobId (checks, then downloads only outdated items) |
| `wbGetJobStatus(jobId)` | jobId | status table (see below), or `nil` if unknown job |

### Job status shape (proposed, Lua polls via `Events.OnTick`)

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
- `modIds` parsed from each downloaded `mod.info` (`id=` line). A workshop item can contain multiple mods — hence the list.
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

### First run / missing pieces
- ZombieBuddy not installed → Lua detects `wbIsAvailable() == false` (globals missing) → Mods menu shows install guidance instead of Update buttons.
- steamcmd not found → `wbGetSteamCmdPath()` returns nil → UI shows "steamcmd not found" with install instructions (Windows: unzip Valve's `steamcmd.zip`; Linux: tarball + 32-bit libs).

## UI placement (B42)

- **Update all**: wrap `ModSelector:create`, anchor the `ISButton` to `self.backButton`.
- **Check for updates**: a second button next to it. Explicit, non-destructive check — no automatic checking (avoids surprise downloads and slow menu opens).
- **Per-row**: wrap `ModListBox:doDrawItem` per instance; draw status text keyed by `item.modId`. Rows are not widget-composed, so no per-row buttons (would need manual hit-testing).
- **Per-mod Update button / status label**: `ModInfoPanel` — `createChildren()` once, `updateView(modInfo)` per selection.
- Row states (three): in our map → "Update" (+ "Update available" badge after a check); game's `getWorkshopID()` non-empty → "Managed by Steam"; else grey "Unknown workshop ID".
- Wrapping survives `reloadMods()` unless the list instance is recreated — verify in-game (Phase 2).

## Design constraints

- **B42 only** (ZombieBuddy requirement).
- **Zero bytecode patches**: we use only ZombieBuddy's Lua-exposure surface, its most stable API.
- **Never store Steam credentials.** Anonymous steamcmd login is the default; if it's ever rejected, fall back to an interactive user login (Steam Guard via the user's own terminal), never persisted.
- **Mods load at game start** — updating files while sitting in the Mods menu is safe; changes apply on next new game / continue.
