# WorkshopBridge

Download and update Steam Workshop mods **from inside Project Zomboid** — built for non-Steam (GOG) players who can't use the Steam Workshop directly.

A Lua UI in the Mods menu ("Check for updates", "Update all", per-mod "Update") talks to a Java backend (via [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)) that runs `steamcmd`, moves downloaded mods into place, and remembers which workshop item each mod came from.

## Status

Lua UI and Java backend are implemented. The Lua side is verified with an offline test rig (JSON decoder + full UI flow); the Java side compiles under JDK 17 and passes an offline harness with a fake steamcmd. Still ahead: in-game verification, real steamcmd end-to-end, release hardening. See [PLAN.md](PLAN.md).

## Installation

1. **Install [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)** (one-time). WorkshopBridge's Java backend loads through it. The mod tells you in-game if it's missing.
2. **Copy the `WorkshopBridge` folder** from a release into your `Zomboid/mods/` directory, then enable it in the Mods menu like any other mod.
3. **steamcmd** — you have two options:
   - *Let the mod handle it:* on your first update, WorkshopBridge downloads Valve's official steamcmd into `Zomboid/workshop_cache/steamcmd/` automatically.
   - *Use your own:* create `Zomboid/workshopbridge.properties` with one line:
     ```
     steamcmd.path=C:\path\to\steamcmd.exe
     ```
     The mod validates it by running `<exe> +quit`; if that fails it falls back to the managed copy.
4. Launch the game. If you run with `-Dzomboid.steam=0` (GOG), everything works — WorkshopBridge never touches Steamworks.

## Usage

- **Check for updates** scans the workshop for newer versions of your WorkshopBridge-tracked mods. Nothing happens automatically — checks only run when you ask, so a surprise update can't break your save.
- **Update all (N)** downloads and installs every available update. Mods are replaced cleanly (stale files removed).
- Each mod row shows its state: **Update** (tracked by WorkshopBridge), **Managed by Steam**, or **Unknown workshop ID**. Selecting a tracked mod shows a per-mod **Update** button.
- Long operations show a progress panel with a throbber. If the network is down you'll get "Couldn't reach Steam's servers — check your internet connection" instead of a raw exception.

## How it works

1. **Update** asks the Java side to run `steamcmd +login anonymous +workshop_download_item 108600 <id> +quit` on a background thread.
2. The downloaded mod is copied flat into `Zomboid/mods/<modID>/` (GOG's mod scan only looks one level deep, so nesting under a workshop-ID folder would hide it).
3. The `workshopID → [modID]` mapping is persisted in `Zomboid/workshop_cache/workshopbridge_map.json`, which is what powers update checks.
4. Update checks compare the workshop item's `time_updated` (via the Steam Web API) against the locally installed version.

## Docs

- [PLAN.md](PLAN.md) — phased build plan and open questions
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — Lua↔Java contract, data flows, map format
- [docs/RESEARCH.md](docs/RESEARCH.md) — validation research (why Lua-only can't work, ZombieBuddy, steamcmd)

## License

MIT — see [LICENSE](LICENSE).
