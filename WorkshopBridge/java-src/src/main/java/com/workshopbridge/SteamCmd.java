package com.workshopbridge;

/**
 * steamcmd detection and invocation. Skeleton - Phase 3.
 *
 * Responsibilities (planned):
 * - detect(): PATH lookup -> common locations (per-OS) -> user-configured override
 * - download(workshopId, cacheDir): ProcessBuilder running
 *   {@code steamcmd +force_install_dir <cacheDir> +login anonymous
 *    +workshop_download_item 108600 <workshopId> +quit}
 *   with output capture, timeouts, and retry on transient failures
 * - moveIntoPlace(workshopId, cacheDir, modsDir): copy
 *   {@code steamapps/workshop/content/108600/<workshopId>/mods/*}
 *   into {@code Zomboid/mods/}, replacing existing installs; parse mod.info
 *   {@code id=} lines to report installed mod ids back to WorkshopMap
 * - never store Steam credentials; anonymous login is the default, with an
 *   interactive account-login fallback if anonymous is ever rejected
 */
public class SteamCmd {
    // TODO(Phase 3)
}
