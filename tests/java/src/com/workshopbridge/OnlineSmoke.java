package com.workshopbridge;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Manual ONLINE smoke test. Hits the REAL Steam Web API and Valve's CDN,
 * then does a REAL workshop download with a real steamcmd.
 *
 * This is deliberately NOT part of the offline suite (tests/java/run.sh):
 * it needs internet and can take several minutes (steamcmd's first-run
 * self-update). Run it before a release, or whenever the Valve-facing code
 * changes. Everything it writes stays in the (gitignored) test work dir.
 *
 * The workshop item used (2685600088) is a ~180KB mod, so the download step
 * is cheap. If that item ever disappears, this test should fail loudly -
 * that means the world changed, not the test.
 */
public class OnlineSmoke {
    /** Workshop id of the small mod downloaded by the smoke test. */
    private static final String PROBE_ID = "2685600088";

    static int failures = 0;

    static void check(boolean c, String name, Object extra) {
        System.out.println((c ? "PASS " : "FAIL ") + name
                + (c || extra == null ? "" : " -- " + extra));
        if (!c) failures++;
    }

    public static void main(String[] args) throws Exception {
        String zomboidProp = System.getProperty("wb.test.zomboid");
        if (zomboidProp == null || zomboidProp.isEmpty()) {
            System.err.println("wb.test.zomboid not set - run via tests/java/run-online.sh");
            System.exit(2);
        }
        File zomboidDir = new File(zomboidProp);

        // ---- 1. real Steam Web API ----
        System.out.println("--- real Steam Web API");
        Map<String, Long> times;
        try {
            times = WorkshopApi.getTimeUpdated(List.of(PROBE_ID));
        } catch (Exception e) {
            check(false, "live Steam API reachable", e.getMessage());
            times = Map.of();
        }
        Long tu = times.get(PROBE_ID);
        check(tu != null && tu > 1_700_000_000L, "live time_updated sane", tu);

        // ---- 2. real steamcmd bootstrap from Valve's CDN ----
        System.out.println("--- real steamcmd bootstrap (first run self-updates; be patient)");
        SteamCmd sc = new SteamCmd(zomboidDir);
        String exe;
        try {
            exe = sc.ensureInstalled(System.out::println);
        } catch (Exception e) {
            check(false, "steamcmd bootstrapped from Valve CDN", e.getMessage());
            exe = null;
        }
        check(exe != null && new File(exe).isFile(),
                "bootstrapped steamcmd exists", exe);

        // ---- 3. real anonymous download of the probe mod ----
        if (exe != null) {
            System.out.println("--- real workshop download (" + PROBE_ID + ")");
            try {
                File itemDir = sc.download(PROBE_ID,
                        new File(zomboidDir, "workshop_cache"), System.out::println);
                File modsDir = new File(itemDir, "mods");
                String[] modFolders = modsDir.list(
                        (d, n) -> new File(d, n).isDirectory());
                check(modFolders != null && modFolders.length > 0,
                        "real download has mods/",
                        modFolders == null ? null : String.join(",", modFolders));

                // ---- 4. through the real install pipeline ----
                List<String> installed = ModInstaller.install(
                        itemDir, new File(zomboidDir, "mods"),
                        new File(zomboidDir, "workshop_cache/.install-staging"),
                        System.out::println);
                check(!installed.isEmpty(), "real install yields mod ids", installed);
            } catch (Exception e) {
                check(false, "real download+install", e.getMessage());
            }
        }

        System.out.println(failures == 0 ? "ONLINE SMOKE PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
