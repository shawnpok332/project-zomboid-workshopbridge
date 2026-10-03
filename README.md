# WorkshopBridge

Download and update Steam Workshop mods from inside Project Zomboid. Built for non-Steam (GOG) players who can't use the Steam Workshop directly.

A Lua UI in the Mods menu ("Check for updates", "Update all", per-mod "Update") talks to a Java backend (via [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)) that runs `steamcmd`, moves downloaded mods into place, and remembers which workshop item each mod came from.

In this doc, "the Zomboid folder" means the game's save/cache directory: `~/Zomboid` on Linux, `%USERPROFILE%\Zomboid` on Windows. That's where saves, mods, and logs live. It is not the game install folder (the one with `ProjectZomboid.jar`).

## Installation

1. **Install [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)** (one-time). WorkshopBridge's Java backend loads through it. The mod tells you in-game if it's missing.
2. **Copy the `WorkshopBridge` folder** from a release into the `mods` folder inside your Zomboid folder, then enable it in the Mods menu like any other mod.
3. **steamcmd**, two options:
   - *Let the mod handle it:* on your first update, WorkshopBridge downloads Valve's official steamcmd into `Zomboid/workshop_cache/steamcmd/` automatically.
   - *Use your own:* create `Zomboid/workshopbridge.properties` in your Zomboid folder with one line:
     ```
     steamcmd.path=C:\path\to\steamcmd.exe
     ```
     The mod validates it by running `<exe> +quit`. A broken path fails fast
     with a message telling you to fix or remove it; when no path is
     configured, the mod uses its managed copy (bootstrapping it first if
     needed).
   - *NixOS:* Valve's steamcmd can't run directly (no `/lib/ld-linux.so.2`).
     Install `steam-run` from nixpkgs (or enable `nix-ld`); WorkshopBridge
     runs steamcmd through `steam-run` automatically when it's available.
4. Launch the game. If you run with `-Dzomboid.steam=0` (GOG), everything works. WorkshopBridge never touches Steamworks.

## Usage

- **Check for updates** scans the workshop for newer versions of your WorkshopBridge-tracked mods. Nothing happens automatically. Checks only run when you ask, so a surprise update can't break your save.
- **Update all (N)** downloads and installs every available update. Mods are replaced cleanly, stale files removed.
- **Download** grabs a brand-new mod from the workshop: paste a workshop ID or URL (e.g. `2685600088` or the full `steamcommunity.com/sharedfiles/...?id=2685600088` link). It installs like an update and is tracked from then on.
- Downloads run one at a time; a waiting download shows "Queued...".
- Each mod row shows its state: tracked by WorkshopBridge, **Managed by Steam**, or **Unknown workshop ID**. Selecting a tracked mod shows a per-mod button: **Update** when a check found a newer version, **Force update** otherwise (it re-downloads regardless).
- Long operations show a progress panel with a throbber. If the network is down you'll get "Couldn't reach Steam's servers - check your internet connection" instead of a raw exception.

## How it works

1. **Update** asks the Java side to run `steamcmd +login anonymous +workshop_download_item 108600 <id> +quit` on a background thread.
2. The downloaded mod is copied flat into `Zomboid/mods/<modID>/`. The game's mod scan only looks one level deep, so nesting under a workshop-ID folder would hide the mod.
3. The `workshopID -> [modID]` mapping is persisted in `Zomboid/workshopbridge_map.json`. That's what powers update checks.
4. Update checks compare the workshop item's `time_updated` (via the Steam Web API) against the locally installed version.

## Building

The Lua side needs no build. The Java backend compiles with Gradle against your
Project Zomboid install's classes plus `ZombieBuddy.jar`; one-time setup and the
`gradle installJar` step are in [WorkshopBridge/java-src/README.md](WorkshopBridge/java-src/README.md).

## Troubleshooting

- **steamcmd fails with `posix_spawn failed, error: 13 (Permission denied)`**,
  e.g. when the game runs inside steam-run's sandbox: the JDK's default process
  launcher (posix_spawn) can be blocked there. The mod sets
  `-Djdk.lang.Process.launchMechanism=FORK` itself at load when you haven't set
  it; if downloads still fail to launch, add the flag to your game's Java
  command line manually.
- **steamcmd can't execute from the game drive** (e.g. a `noexec` removable-media
  mount, or a sandboxed bind mount): the mod automatically bootstraps the
  steamcmd *binary* into `~/.cache/workshopbridge/steamcmd` (or
  `$XDG_CACHE_HOME`) and retries there. Workshop downloads still land in
  `Zomboid/workshop_cache` on the game drive.

## Docs

- [TESTING.md](TESTING.md) - how to run the offline suites and the online smoke test
- [PLAN.md](PLAN.md) - phased build plan and open questions
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - Lua/Java contract, data flows, map format
- [docs/RESEARCH.md](docs/RESEARCH.md) - validation research (why Lua-only can't work, ZombieBuddy, steamcmd)

## License

MIT, see [LICENSE](LICENSE).

---
Built with help from Muse, Meta's AI assistant.
