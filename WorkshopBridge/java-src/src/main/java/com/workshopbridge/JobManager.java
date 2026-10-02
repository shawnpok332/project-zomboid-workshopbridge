package com.workshopbridge;

/**
 * Runs downloads on background threads and exposes pollable job status.
 * Skeleton - Phase 3.
 *
 * Responsibilities (planned):
 * - submit(Callable) -> jobId (UUID)
 * - status(jobId) -> map shaped per docs/ARCHITECTURE.md ("Job status shape")
 * - cancel(jobId)
 * - Lua polls via Events.OnTick; the game thread never blocks.
 */
public class JobManager {
    // TODO(Phase 3)
}
