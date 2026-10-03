package com.workshopbridge;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Offline test harness. Run with:
 *   -Dwb.test.zomboid=<tmpdir>
 * (the runner script sets this up; the stub Steam API binds port 0 and the
 * harness points WorkshopApi at it programmatically).
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
        String zomboidProp = System.getProperty("wb.test.zomboid");
        if (zomboidProp == null || zomboidProp.isEmpty()) {
            // Never run against the real ~/Zomboid: without the property the
            // backend would resolve to the user's actual game folder.
            System.err.println("wb.test.zomboid not set - run via tests/java/run.sh");
            System.exit(2);
        }

        // ---- 1. Json basics ----
        Object o = Json.parse("{\"a\":1,\"b\":[true,null],\"c\":\"x\\\"y\"}");
        Map<String, Object> m = Json.object(o);
        check(m != null && ((Number) m.get("a")).intValue() == 1, "json parse object");
        check(Json.stringify(m).contains("\"a\":1"), "json stringify");

        // ---- 1b. Json edge cases ----
        Map<String, Object> esc = Json.object(
                Json.parse("{\"q\":\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\"}"));
        check("a\"b\\c/d\b\f\n\r\t".equals(esc.get("q")), "json escapes", esc.get("q"));
        Map<String, Object> uni = Json.object(Json.parse("{\"u\":\"\\u0041\\u00e9\"}"));
        check("A\u00e9".equals(uni.get("u")), "json unicode escapes", uni.get("u"));
        Map<String, Object> sur = Json.object(Json.parse("{\"s\":\"\\ud83d\\ude00\"}"));
        check("\uD83D\uDE00".equals(sur.get("s")), "json surrogate pair", sur.get("s"));
        Object nested = Json.parse("{\"a\":{\"b\":[1,2,{\"c\":null}]}}");
        check("{\"a\":{\"b\":[1,2,{\"c\":null}]}}".equals(Json.stringify(nested)),
                "json nesting round-trip");
        check(((Number) Json.parse("01")).intValue() == 1, "json leading-zero number (lenient)");
        boolean threw = false;
        try {
            Json.parse("{\"a\":}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "json malformed object throws");
        boolean threw2 = false;
        try {
            Json.parse("{\"a\":1} trailing");
        } catch (IllegalArgumentException e) {
            threw2 = true;
        }
        check(threw2, "json trailing data throws");

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

        // ---- 3b. corrupt map file loads empty instead of throwing ----
        File corruptFile = new File(zomboidDir, "corrupt_map.json");
        Files.writeString(corruptFile.toPath(), "this is not json{{{", StandardCharsets.UTF_8);
        WorkshopMap corrupt = new WorkshopMap(corruptFile);
        corrupt.load(); // must not throw
        check(corrupt.snapshot().isEmpty(), "corrupt map loads empty");

        // ---- 3c. point steamcmd at the fake (used by every job test below) ----
        File props = new File(zomboidDir, "workshopbridge.properties");
        String fakeExe = new File(System.getProperty("wb.test.fakebin"),
                "steamcmd.sh").getAbsolutePath();
        Files.writeString(props.toPath(), "steamcmd.path=" + fakeExe + "\n");

        // ---- 4. check job with a stub Steam API: 111 updated, 222 gone ----
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int apiPort = api.getAddress().getPort();
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
        // WorkshopApi reads its URL once, at class-load time: point it at the
        // stub before its first use. Binding port 0 above means run.sh needs
        // no free-port hack (and no python3).
        System.setProperty("workshopbridge.steamApiUrl",
                "http://127.0.0.1:" + apiPort + "/");
        JobManager jobs = new JobManager(backend);
        // Gate the live-HTTP checks on a plain probe of the stub server, not on
        // our code's error strings: in sandboxed environments loopback HTTP may
        // be intercepted or dead, and the failure mode varies. On a normal
        // machine the probe succeeds and every assertion below runs for real.
        boolean liveHttp = stubApiUsable(System.getProperty("workshopbridge.steamApiUrl"));
        if (!liveHttp) {
            System.out.println("SKIPPED live check-job HTTP test (no usable loopback HTTP here)");
            System.out.println("SKIPPED check finds ModA update (no usable loopback HTTP here)");
            System.out.println("SKIPPED check flags deleted item (no usable loopback HTTP here)");
            System.out.println("SKIPPED live update-all HTTP test (no usable loopback HTTP here)");
        } else {
            String checkId = jobs.submitCheck();
            Map<String, Object> st = awaitDone(jobs, checkId);
            check("done".equals(st.get("state")), "check completes", st.get("state"));
            List<Object> updates = Json.array(st.get("updates"));
            check(updates != null && updates.size() == 1 && "ModA".equals(updates.get(0)),
                    "check finds ModA update", updates);
            String msg = String.valueOf(st.get("message"));
            check(msg.contains("222") && msg.contains("no longer listed"),
                    "check flags deleted item", msg);

            // ---- 4c. update-all job against the same stub API ----
            // map: 111 -> ModA (outdated), 222 -> ModB (missing from API)
            String upAllId = jobs.submitUpdateAll();
            Map<String, Object> uast = awaitDone(jobs, upAllId);
            check("done".equals(uast.get("state")), "update-all completes", uast.get("error"));
            check("111".equals(backend.workshopMap().getWorkshopId("FakeMod-111")),
                    "update-all re-records workshop->mod");
            String umsg = String.valueOf(uast.get("message"));
            check(umsg.contains("All mods up to date") && umsg.contains("222"),
                    "update-all message notes missing item", umsg);
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
        String found = backend.steamCmd().findExecutable();
        check(fakeExe.equals(found), "override steamcmd found", found);
        String upId = jobs.submitUpdate("99999");
        Map<String, Object> ust = awaitDone(jobs, upId);
        check("done".equals(ust.get("state")), "update completes: " + ust.get("state"),
                ust.get("error"));
        File installedInfo = new File(backend.modsDir(), "FakeMod-99999/common/mod.info");
        check(installedInfo.isFile(), "mod installed to mods dir");
        WorkshopMap.Entry e99999 = backend.workshopMap().snapshot().get("99999");
        check(e99999 != null && e99999.modIds.contains("FakeMod-99999"),
                "map records workshop->mod");

        // ---- 5b. steamcmd failure surfaces as a failed job, not a hang ----
        String failId = jobs.submitUpdate("0"); // fake steamcmd exits 1 for id 0
        Map<String, Object> fst = awaitDone(jobs, failId);
        check("failed".equals(fst.get("state")), "steamcmd failure -> failed job",
                fst.get("state"));
        check(String.valueOf(fst.get("error")).contains("no mods/"),
                "failure error names the problem", fst.get("error"));

        // ---- 6. invalid workshop id fails cleanly ----
        String badId = jobs.submitUpdate("abc");
        Map<String, Object> bst = awaitDone(jobs, badId);
        check("failed".equals(bst.get("state")), "invalid id fails", bst.get("state"));

        // ---- 7. unknown job ----
        check(jobs.statusJson("no-such-job") == null, "unknown job -> null");

        // ---- 9. interrupted-install recovery ----
        File modsDir = backend.modsDir();
        Consumer<String> quiet = s -> {};
        // case A: crash between the two renames -> complete forward
        File victimOld = new File(modsDir, "VictimMod.old-aaa");
        File victimNew = new File(modsDir, "VictimMod.new-aaa");
        writeFile(new File(victimOld, "common/mod.info"), "id=VictimMod\n");
        writeFile(new File(victimNew, "common/mod.info"), "id=VictimMod\n");
        writeFile(new File(victimNew, "newfile.txt"), "new");
        ModInstaller.recoverInterruptedInstalls(modsDir.toPath(), quiet);
        check(new File(modsDir, "VictimMod/newfile.txt").isFile(),
                "mid-swap crash completes forward");
        check(!victimOld.exists() && !victimNew.exists(), "mid-swap leftovers cleaned");
        // case B: backup left after a completed swap -> dropped, live tree kept
        File staleLive = new File(modsDir, "StaleMod");
        writeFile(new File(staleLive, "keep.txt"), "keep");
        File staleOld = new File(modsDir, "StaleMod.old-bbb");
        writeFile(new File(staleOld, "old.txt"), "old");
        ModInstaller.recoverInterruptedInstalls(modsDir.toPath(), quiet);
        check(new File(staleLive, "keep.txt").isFile() && !staleOld.exists(),
                "post-swap backup cleaned");
        // case C: partial staging from a copy-phase crash -> dropped
        File partialLive = new File(modsDir, "PartialMod");
        writeFile(new File(partialLive, "keep.txt"), "keep");
        File partialNew = new File(modsDir, "PartialMod.new-ccc");
        writeFile(new File(partialNew, "partial.txt"), "partial");
        ModInstaller.recoverInterruptedInstalls(modsDir.toPath(), quiet);
        check(new File(partialLive, "keep.txt").isFile() && !partialNew.exists(),
                "partial staging dropped");

        // ---- 10. reinstall clean-replaces through the atomic swap ----
        // scratch dirs live under the test work dir, never the system temp
        // (on Windows that would be inside the user profile)
        File scratch = scratchDir(zomboidDir, "wb-scratch");
        File itemV1 = new File(scratch, "itemV1/mods/ReMod");
        writeFile(new File(itemV1, "common/mod.info"), "id=ReMod\n");
        writeFile(new File(itemV1, "old.txt"), "v1");
        ModInstaller.install(new File(scratch, "itemV1"), modsDir, quiet);
        check(new File(modsDir, "ReMod/old.txt").isFile(), "v1 installed");
        File itemV2 = new File(scratch, "itemV2/mods/ReMod");
        writeFile(new File(itemV2, "common/mod.info"), "id=ReMod\n");
        writeFile(new File(itemV2, "new.txt"), "v2");
        List<String> ids = ModInstaller.install(new File(scratch, "itemV2"), modsDir, quiet);
        File reMod = new File(modsDir, "ReMod");
        check(new File(reMod, "new.txt").isFile() && !new File(reMod, "old.txt").exists(),
                "reinstall clean-replaces (stale files gone)");
        check(ids.equals(List.of("ReMod")), "install returns mod ids", ids);
        boolean leftovers = false;
        File[] modEntries = modsDir.listFiles();
        if (modEntries != null) {
            for (File f : modEntries) {
                if (f.getName().contains(".new-") || f.getName().contains(".old-")) {
                    leftovers = true;
                }
            }
        }
        check(!leftovers, "no staging dirs left after install");

        // ---- 11. installArchive handles a real tar.gz ----
        File tgzDir = scratchDir(zomboidDir, "wb-tgz");
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

        // ---- 12. 32-bit hint fires on the classic failure signatures ----
        check(SteamCmd.missing32BitHint(
                "did not identify as steamcmd (exit=127, output: .../linux32/steamcmd: No such file or directory)")
                .contains("32-bit"), "32-bit hint on exit=127");
        check(SteamCmd.missing32BitHint(
                "error while loading shared libraries: libstdc++.so.6: cannot open shared object file")
                .contains("32-bit"), "32-bit hint on shared libraries");
        check(SteamCmd.missing32BitHint(
                "did not identify as steamcmd (exit=1, output: nope)").isEmpty(),
                "no 32-bit hint for other failures");

        // ---- 13. NixOS detection and hint ----
        check(SteamCmd.isNixOSRelease("NAME=NixOS\nID=nixos\nVERSION=\"25.05\"\n"),
                "detects NixOS");
        check(!SteamCmd.isNixOSRelease("NAME=Ubuntu\nID=ubuntu\n"),
                "non-NixOS not detected");
        check(SteamCmd.missing32BitHint("did not identify as steamcmd (exit=127)", true)
                .contains("steam-run"), "NixOS hint names steam-run");

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void writeFile(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
    }

    /** Test scratch dir inside the (gitignored, per-run) test work dir. */
    static File scratchDir(File zomboidDir, String prefix) throws Exception {
        File base = new File(zomboidDir, ".scratch");
        base.mkdirs();
        return Files.createTempDirectory(base.toPath(), prefix).toFile();
    }

    /**
     * True when a plain HTTP round-trip to the stub API works. Used to decide
     * whether the live-HTTP checks can run in this environment; deliberately
     * independent of the code under test.
     */
    static boolean stubApiUsable(String url) {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            byte[] body = "itemcount=0".getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(body);
            if (c.getResponseCode() != 200) {
                return false;
            }
            String resp = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return resp.contains("publishedfiledetails");
        } catch (Exception e) {
            return false;
        }
    }
}
