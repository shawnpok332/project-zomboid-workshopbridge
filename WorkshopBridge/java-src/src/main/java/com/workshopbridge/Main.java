package com.workshopbridge;

/**
 * Optional entry point: ZombieBuddy calls {@code Main.main(String[])} when the
 * mod loads. Kept deliberately light - the backend initializes lazily on the
 * first Lua call, when ZomboidFileSystem is guaranteed ready.
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("[WorkshopBridge] Java backend loaded via ZombieBuddy "
                + "(Lua API: wbIsAvailable, wbGetSteamCmdPath, wbGetWorkshopId, "
                + "wbCheckForUpdates, wbUpdateAll, wbUpdateMod, wbGetJobStatus)");
    }
}
