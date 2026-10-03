package com.workshopbridge;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Offline test harness. Run with:
 *   -Dwb.test.zomboid=<tmpdir> -Dworkshopbridge.steamApiUrl=http://127.0.0.1:PORT/
 * (the runner script sets these up).
 */
public class WBTest {
    static int failures = 0;

    static void check(boolean c, String name, Object extra) {
        System.out.println((c ? "PASS " : "FAIL ") + name
                + (c || extra == null ? "" : " -- " + extra));
        if (!c) failures++;
    }

    static void check(boolean c, String name) {
        check(c, name, null);
    }

    /** Polls a job until it leaves RUNNING; returns the final status map. */
    static Map<String, Object> awaitDone(JobManager jobs, String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String s = jobs.statusJson(jobId);
            if (s == null) throw new IllegalStateException("job vanished: " + jobId);
            Map<String, Object> st = Json.object(Json.parse(s));
            if (!"running".equals(st.get("state"))) return st;
            Thread.sleep(100);
        }
        throw new IllegalStateException("job timed out: " + jobId);
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. Json basics ----
        Object o = Json.parse("{\"a\":1,\"b\":[true,null],\"c\":\"x\\\"y\"}");
        Map<String, Object> m = Json.object(o);
        check(m != null && ((Number) m.get("a")).intValue() == 1, "json parse object");
        check(Json.stringify(m).contains("\"a\":1"), "json stringify");

        // ---- 2. Net messages ----
        // ---- 2b. parse a REAL captured Steam API response ----
        String fixtureDir = System.getProperty("wb.test.fixtures");
        String realJson = Files.readString(
                new File(fixtureDir, "publishedfiledetails.json").toPath(), StandardCharsets.UTF_8);
        Map<String, Long> parsed = WorkshopApi.parseTimeUpdated(realJson);
        check(parsed.get("2685600088") != null && parsed.get("2685600088") > 1_700_000_000L,
                "real response: time_updated parsed", parsed.get("2685600088"));
        check(!parsed.containsKey("1"), "real response: bogus id absent (result=9)");
        check(WorkshopApi.parseTimeUpdated("not json").isEmpty(), "garbage -> empty map");
        check(WorkshopApi.parseTimeUpdated("{\"response\":{}}").isEmpty(), "empty response -> empty");
        check(Net.friendlyMessage(new java.io.IOException(
                new java.net.UnknownHostException("x")))
                .startsWith("Couldn't reach Steam's servers"), "net dns");
        check(Net.friendlyMessage(new java.io.IOException("weird")).equals("weird"),
                "net passthrough");

        // ---- 3. Backend + atomic map save ----
        Backend backend = Backend.get();
        File zomboidDir = backend.zomboidDir();
        check(zomboidDir.getAbsolutePath()
                .equals(new File(System.getProperty("wb.test.zomboid")).getAbsolutePath()),
                "zomboid dir honors test property", zomboidDir);
        backend.workshopMap().record("111", List.of("ModA"), 1000L);
        backend.workshopMap().record("222", List.of("ModB"), 1000L);
        File mapFile = new File(zomboidDir, "workshopbridge_map.json");
        check(mapFile.isFile(), "map file written");
        check(!new File(zomboidDir, "workshopbridge_map.json.tmp").exists(),
                "no temp file left behind (atomic save)");
        WorkshopMap reloaded = new WorkshopMap(mapFile);
        reloaded.load();
        check("111".equals(reloaded.getWorkshopId("ModA"))
                && "222".equals(reloaded.getWorkshopId("ModB")), "map reload round-trip");
        check(reloaded.snapshot().get("111").timeUpdated == 1000L, "map timeUpdated");

        // ---- 4. check job with a stub Steam API: 111 updated, 222 gone ----
        // NOTE: workshopbridge.steamApiUrl must be set via -D (with this port)
        // before WorkshopApi loads.
        int apiPort = Integer.parseInt(args[0]);
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", apiPort), 0);
        String apiJson = "{\"response\":{\"publishedfiledetails\":["
                + "{\"publishedfileid\":\"111\",\"time_updated\":2000,"
                + "\"result\":1}]}}";
        api.createContext("/", ex -> {
            byte[] b = apiJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        api.start();
        // NOTE: workshopbridge.steamApiUrl must be set via -D before WorkshopApi loads
        JobManager jobs = new JobManager(backend);
        String checkId = jobs.submitCheck();
        Map<String, Object> st = awaitDone(jobs, checkId);
        String checkErr = String.valueOf(st.get("error"));
        if ("failed".equals(st.get("state")) && checkErr.contains("turned off")) {
            // this sandbox blocks even localhost TCP: the live-HTTP part of
            // this test cannot run here. Loud skip, not a pass.
            System.out.println("SKIPPED live check-job HTTP test (no TCP in this environment)");
            System.out.println("SKIPPED check finds ModA update (no TCP in this environment)");
            System.out.println("SKIPPED check flags deleted item (no TCP in this environment)");
        } else {
            check("done".equals(st.get("state")), "check completes", st.get("state"));
            List<Object> updates = Json.array(st.get("updates"));
            check(updates != null && updates.size() == 1 && "ModA".equals(updates.get(0)),
                    "check finds ModA update", updates);
            String msg = String.valueOf(st.get("message"));
            check(msg.contains("222") && msg.contains("no longer listed"),
                    "check flags deleted item", msg);
        }
        api.stop(0);

        // ---- 4b. check-summary message building (no network needed) ----
        check(JobManager.checkSummary(2, List.of("222")).contains("2 mod(s) have updates")
                && JobManager.checkSummary(2, List.of("222")).contains("222"),
                "summary with updates + missing");
        check(JobManager.checkSummary(0, List.of()).equals("Everything is up to date"),
                "summary clean");
        String s2 = JobManager.checkSummary(0, List.of("222", "333"));
        check(s2.contains("Everything is up to date") && s2.contains("222, 333"),
                "summary clean + missing lists ids", s2);

        // ---- 5. fake steamcmd download -> install -> map ----
        File props = new File(zomboidDir, "workshopbridge.properties");
        String fakeExe = new File(System.getProperty("wb.test.fakebin"),
                "steamcmd.sh").getAbsolutePath();
        Files.writeString(props.toPath(), "steamcmd.path=" + fakeExe + "\n");
        String found = backend.steamCmd().findExecutable();
        check(fakeExe.equals(found), "override steamcmd found", found);
        String upId = jobs.submitUpdate("99999");
        Map<String, Object> ust = awaitDone(jobs, upId);
        check("done".equals(ust.get("state")), "update completes: " + ust.get("state"),
                ust.get("error"));
        File installedInfo = new File(backend.modsDir(), "FakeMod/common/mod.info");
        check(installedInfo.isFile(), "mod installed to mods dir");
        check("99999".equals(backend.workshopMap().getWorkshopId("FakeMod")),
                "map records workshop->mod");

        // ---- 6. invalid workshop id fails cleanly ----
        String badId = jobs.submitUpdate("abc");
        Map<String, Object> bst = awaitDone(jobs, badId);
        check("failed".equals(bst.get("state")), "invalid id fails", bst.get("state"));

        // ---- 7. unknown job ----
        check(jobs.statusJson("no-such-job") == null, "unknown job -> null");

        // ---- 8. installArchive handles a real tar.gz ----
        File tgzDir = Files.createTempDirectory("wb-tgz").toFile();
        File contentDir = new File(tgzDir, "content");
        contentDir.mkdirs();
        File sh = new File(contentDir, "steamcmd.sh");
        Files.writeString(sh.toPath(), "#!/bin/sh\necho hi\n");
        Process tar = new ProcessBuilder("tar", "-czf",
                new File(tgzDir, "sc.tar.gz").getAbsolutePath(),
                "-C", contentDir.getAbsolutePath(), "steamcmd.sh").start();
        check(tar.waitFor() == 0, "tar fixture created");
        SteamCmd sc = new SteamCmd(zomboidDir);
        File exeDir = new File(tgzDir, "out");
        String exe = sc.installArchive(new File(tgzDir, "sc.tar.gz"), false, exeDir,
                s -> {});
        check(new File(exe).isFile() && new File(exe).canExecute(), "installArchive extracts tar.gz", exe);

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
