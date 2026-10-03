package com.workshopbridge;

import java.io.File;

/**
 * Singleton holder for the backend services. Initialized lazily on the first
 * Lua call (which happens in the menu, long after ZomboidFileSystem is ready).
 */
public final class Backend {
    private static volatile Backend instance;

    public static Backend get() {
        Backend b = instance;
        if (b == null) {
            synchronized (Backend.class) {
                b = instance;
                if (b == null) {
                    instance = b = new Backend();
                }
            }
        }
        return b;
    }

    private final File zomboidDir;
    private final File modsDir;
    private final File cacheDir;
    private final WorkshopMap workshopMap;
    private final SteamCmd steamCmd;
    private final JobManager jobs;

    private Backend() {
        this.zomboidDir = resolveZomboidDir();
        this.modsDir = new File(zomboidDir, "mods");
        this.cacheDir = new File(zomboidDir, "workshop_cache");
        this.workshopMap = new WorkshopMap(new File(zomboidDir, "workshopbridge_map.json"));
        try {
            this.workshopMap.load();
        } catch (Exception e) {
            System.out.println("[WorkshopBridge] map load failed: " + e);
        }
        this.steamCmd = new SteamCmd(zomboidDir);
        this.jobs = new JobManager(this);
        System.out.println("[WorkshopBridge] backend ready (zomboidDir=" + zomboidDir + ")");
    }

    private static File resolveZomboidDir() {
        try {
            String dir = zombie.ZomboidFileSystem.instance.getCacheDir();
            if (dir != null && !dir.isEmpty()) {
                return new File(dir);
            }
        } catch (Throwable t) {
            System.out.println("[WorkshopBridge] ZomboidFileSystem unavailable, using fallback: " + t);
        }
        return new File(System.getProperty("user.home"), "Zomboid");
    }

    public File zomboidDir() {
        return zomboidDir;
    }

    public File modsDir() {
        return modsDir;
    }

    public File cacheDir() {
        return cacheDir;
    }

    public WorkshopMap workshopMap() {
        return workshopMap;
    }

    /**
     * PZ mod id -> workshop id, with self-healing: when the map has no entry
     * recording {@code modId}, scan the installed mods; a folder whose
     * mod.info id matches but which was recorded under its folder name
     * (author typo in the folder, or a mod.info layout we didn't parse at
     * install time) gets its entry repaired on the spot.
     */
    public synchronized String getWorkshopId(String modId) {
        String wsid = workshopMap.getWorkshopId(modId);
        if (wsid != null || modId == null || modId.isEmpty()) {
            return wsid;
        }
        File[] dirs = modsDir.listFiles(File::isDirectory);
        if (dirs == null) {
            return null;
        }
        for (File dir : dirs) {
            if (!modId.equals(ModInstaller.readModId(dir))) {
                continue;
            }
            String byFolder = workshopMap.getWorkshopId(dir.getName());
            if (byFolder != null) {
                workshopMap.replaceModId(byFolder, dir.getName(), modId);
                System.out.println("[WorkshopBridge] repaired map entry for "
                        + modId + " (was recorded as folder \"" + dir.getName() + "\")");
                return byFolder;
            }
        }
        return null;
    }

    public SteamCmd steamCmd() {
        return steamCmd;
    }

    public JobManager jobs() {
        return jobs;
    }
}
