package com.workshopbridge;

import me.zed-0xff.zombie_buddy.Exposer;

/**
 * ZombieBuddy-exposed API callable from Lua. Skeleton - Phase 1.
 *
 * Phase 3 will implement each method and decide the final exposure style
 * ({@code @Exposer.LuaClass} table vs {@code @LuaMethod(global = true)} globals).
 * The Lua-side contract is documented in docs/ARCHITECTURE.md.
 */
@Exposer.LuaClass
public class SteamCmdApi {

    /** True when the Java side loaded; Lua uses this to detect ZombieBuddy presence. */
    public boolean isAvailable() {
        return true; // TODO(Phase 3)
    }

    /** steamcmd executable path, or null when not detected. */
    public String getSteamCmdPath() {
        return null; // TODO(Phase 3): PATH -> common locations -> user-configured path
    }

    /**
     * Workshop ID for a PZ mod id (the {@code id=} value from mod.info),
     * or null when the mod wasn't installed via WorkshopBridge ("Unknown workshop ID").
     */
    public String getWorkshopId(String modId) {
        return null; // TODO(Phase 3): invert WorkshopMap
    }

    /** Starts an update job for one workshop item. Returns a job id for polling. */
    public String updateMod(String workshopId) {
        return null; // TODO(Phase 3): JobManager.submit(...)
    }

    /** Starts an update-all job (check, then download only outdated items). Returns a job id. */
    public String updateAll() {
        return null; // TODO(Phase 3): JobManager.submit(...)
    }

    /** Polls a job. Returns a status table (shape in docs/ARCHITECTURE.md), or null for unknown job. */
    public Object getJobStatus(String jobId) {
        return null; // TODO(Phase 3): JobManager.status(jobId)
    }
}
