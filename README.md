# WorkshopBridge

Download and update Steam Workshop mods **from inside Project Zomboid** — built for non-Steam (GOG) players who can't use the Steam Workshop directly.

A Lua UI in the Mods menu ("Update all", per-mod "Update") talks to a Java backend (via [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)) that shells out to `steamcmd`, moves the downloaded mods into place, and remembers which workshop item each mod came from.

## Status

**Phase 1 — skeleton.** Repo structure, docs, and stub code are here for joint review. Nothing is functional yet. See [PLAN.md](PLAN.md) for the staged build plan.

## How it works (planned)

1. You open the Mods menu. Mods installed through WorkshopBridge show an **Update** button; anything else shows "Unknown workshop ID".
2. **Update all** (or a single Update) asks the Java side to check the workshop for newer versions.
3. Java runs `steamcmd +login anonymous +workshop_download_item 108600 <id> +quit` on a background thread, copies the result into `Zomboid/mods/`, and records the `workshopID → [modID]` mapping in `workshopbridge_map.json`.
4. The Lua UI polls the background job and shows progress.

## Requirements (planned)

- Project Zomboid **Build 42** (ZombieBuddy is B42-only)
- [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) installed (one-time setup; the mod detects its absence and tells you what to do)
- `steamcmd` — auto-detected; the mod guides you to install it if missing

## Docs

- [PLAN.md](PLAN.md) — phased build plan and open questions
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — Lua↔Java contract, data flows, map format
- [docs/RESEARCH.md](docs/RESEARCH.md) — validation research (why Lua-only can't work, ZombieBuddy, steamcmd)

## License

MIT — see [LICENSE](LICENSE).
