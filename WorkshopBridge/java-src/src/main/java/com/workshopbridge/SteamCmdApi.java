package com.workshopbridge;

import se.krka.kahlua.integration.annotations.LuaMethod;

/**
 * The Lua-visible API, exposed as plain globals via ZombieBuddy.
 *
 * Every method is static, non-blocking and thread-safe: anything slow runs on
 * a JobManager background thread and Lua polls {@link #wbGetJobStatus}.
 * {@code wbGetJobStatus} returns a JSON string (decoded in Lua by WB_Json)
 * because Kahlua return marshaling of Java objects is not something we want
 * to depend on.
 */
public final class SteamCmdApi {
    private SteamCmdApi() {}

    @LuaMethod(name = "wbIsAvailable", global = true)
    public static boolean wbIsAvailable() {
        try {
            Backend.get();
            return true;
        } catch (Throwable t) {
            System.out.println("[WorkshopBridge] backend init failed: " + t);
            return false;
        }
    }

    /** Absolute steamcmd path, or null when not detected. */
    @LuaMethod(name = "wbGetSteamCmdPath", global = true)
    public static String wbGetSteamCmdPath() {
        try {
            return Backend.get().steamCmd().findExecutable();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Workshop id for a PZ mod id (the {@code id=} value from mod.info),
     * or null when the mod wasn't installed via WorkshopBridge.
     */
    @LuaMethod(name = "wbGetWorkshopId", global = true)
    public static String wbGetWorkshopId(String modId) {
        try {
            return modId == null ? null : Backend.get().workshopMap().getWorkshopId(modId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Starts an update-check job. Returns a job id, or null on failure. */
    @LuaMethod(name = "wbCheckForUpdates", global = true)
    public static String wbCheckForUpdates() {
        try {
            return Backend.get().jobs().submitCheck();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Starts an update-all job. Returns a job id, or null on failure. */
    @LuaMethod(name = "wbUpdateAll", global = true)
    public static String wbUpdateAll() {
        try {
            return Backend.get().jobs().submitUpdateAll();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Starts an update job for one workshop item. Returns a job id, or null on failure. */
    @LuaMethod(name = "wbUpdateMod", global = true)
    public static String wbUpdateMod(String workshopId) {
        try {
            return Backend.get().jobs().submitUpdate(workshopId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Polls a job. Returns a JSON status object
     * ({@code state/done/total/message[/error][/updates]}), or null for
     * unknown job ids. See docs/ARCHITECTURE.md for the shape.
     */
    @LuaMethod(name = "wbGetJobStatus", global = true)
    public static String wbGetJobStatus(String jobId) {
        try {
            return jobId == null ? null : Backend.get().jobs().statusJson(jobId);
        } catch (Throwable t) {
            return null;
        }
    }
}
