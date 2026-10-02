package com.workshopbridge;

/**
 * Persists the workshopID -> { modIds, timeUpdated, lastDownloaded } mapping.
 * Skeleton - Phase 3.
 *
 * Responsibilities (planned):
 * - load/save {@code Zomboid/workshopbridge_map.json} (JSON, versioned envelope)
 * - record(workshopId, modIds, timeUpdated) after a successful download+move
 * - lookupWorkshopId(modId): invert the map in memory for per-row "Update" buttons
 * - update checks: compare stored timeUpdated against ISteamRemoteStorage/GetPublishedFileDetails
 */
public class WorkshopMap {
    // TODO(Phase 3)
}
