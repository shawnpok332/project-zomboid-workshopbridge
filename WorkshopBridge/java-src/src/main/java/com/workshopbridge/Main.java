package com.workshopbridge;

/**
 * Optional entry point: ZombieBuddy calls {@code Main.main(String[])} when the
 * mod loads. Kept deliberately light - the backend initializes lazily on the
 * first Lua call, when ZomboidFileSystem is guaranteed ready.
 */
public class Main {
    /**
     * Name of the JDK's process-launch selector. The default (posix_spawn)
     * fails with EACCES inside steam-run's bubblewrap sandbox for some
     * runtimes (observed Oct 2026 with the GOG-bundled JRE on NixOS:
     * every ProcessBuilder.start() died with "posix_spawn failed,
     * error: 13 (Permission denied)" while fork+execve worked fine).
     */
    static final String LAUNCH_MECHANISM_PROP = "jdk.lang.Process.launchMechanism";

    public static void main(String[] args) {
        ensureForkLaunchMechanism();
        System.out.println("[WorkshopBridge] Java backend loaded via ZombieBuddy "
                + "(Lua API: wbIsAvailable, wbGetSteamCmdPath, wbGetWorkshopId, "
                + "wbCheckForUpdates, wbUpdateAll, wbUpdateMod, wbGetJobStatus)");
    }

    /**
     * Best-effort workaround for the posix_spawn/EACCES sandbox problem above:
     * when the user has not chosen explicitly, ask the JDK to spawn processes
     * with fork instead. The JDK reads this property once, when
     * {@code java.lang.ProcessImpl} first initializes; this runs at mod load,
     * before any of our ProcessBuilder use, so it normally takes effect. If
     * the class already initialized (or the user set the flag themselves) this
     * is a harmless no-op. Package-private so tests can call it.
     */
    static void ensureForkLaunchMechanism() {
        if (System.getProperty(LAUNCH_MECHANISM_PROP) == null) {
            System.setProperty(LAUNCH_MECHANISM_PROP, "FORK");
            System.out.println("[WorkshopBridge] process launch mechanism set to FORK"
                    + " (posix_spawn is unreliable in some sandboxes)");
        }
    }
}
