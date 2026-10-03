package com.workshopbridge;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs downloads/checks on background threads and exposes pollable status.
 * Lua polls {@link #statusJson(String)} on tick; the game thread never blocks.
 */
public final class JobManager {
    public enum State { RUNNING, DONE, FAILED }

    public static final class Job {
        public final String id;
        public final String kind;
        volatile State state = State.RUNNING;
        volatile int done;
        volatile int total;
        volatile String message = "";
        volatile String error;
        /** modIds with updates available (check jobs). */
        volatile List<String> updates = Collections.emptyList();

        Job(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }

        String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state.name().toLowerCase(Locale.ROOT));
            m.put("done", done);
            m.put("total", total);
            m.put("message", message);
            if (error != null) {
                m.put("error", error);
            }
            m.put("updates", updates);
            return Json.stringify(m);
        }
    }

    @FunctionalInterface
    private interface JobTask {
        void run(Job job) throws Exception;
    }

    private final Backend backend;
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "workshopbridge-job");
        t.setDaemon(true);
        return t;
    });
    /**
     * Downloads run one at a time: concurrent steamcmd processes share one
     * install dir and gain nothing (each is network-bound with per-process
     * overhead), while serialization keeps behavior deterministic and the
     * UI trivial (one active download). The queue is implicit in the
     * executor; a waiting job reports "Queued..." until it starts.
     */
    private final ExecutorService downloadExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "workshopbridge-download");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    JobManager(Backend backend) {
        this.backend = backend;
    }

    public String submitCheck() {
        return submit("check", this::runCheck);
    }

    public String submitUpdateAll() {
        return submitDownload("update-all", this::runUpdateAll);
    }

    public String submitUpdate(String workshopId) {
        return submitDownload("update", job -> runUpdate(job, workshopId));
    }

    /** JSON status object, or null for unknown job ids. */
    public String statusJson(String jobId) {
        Job j = jobs.get(jobId);
        return j == null ? null : j.toJson();
    }

    private String submit(String kind, JobTask task) {
        return submitOn(exec, kind, task, "");
    }

    /** Download-bearing jobs: serialized, "Queued..." until actually started. */
    private String submitDownload(String kind, JobTask task) {
        return submitOn(downloadExec, kind, task, "Queued...");
    }

    private String submitOn(ExecutorService target, String kind, JobTask task,
            String initialMessage) {
        Job job = new Job(UUID.randomUUID().toString().substring(0, 8), kind);
        job.message = initialMessage;
        jobs.put(job.id, job);
        prune();
        target.submit(() -> {
            try {
                task.run(job);
            } catch (Throwable t) {
                fail(job, t);
            }
        });
        return job.id;
    }

    private void runCheck(Job job) {
        Map<String, WorkshopMap.Entry> items = backend.workshopMap().snapshot();
        List<String> ids = new ArrayList<>(items.keySet());
        job.total = ids.size();
        job.message = "Checking " + ids.size() + " workshop items...";
        final Map<String, Long> remote;
        try {
            remote = WorkshopApi.getTimeUpdated(ids);
        } catch (Exception e) {
            fail(job, e);
            return;
        }
        List<String> withUpdates = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int i = 0;
        for (String wsid : ids) {
            i++;
            job.done = i;
            Long tu = remote.get(wsid);
            WorkshopMap.Entry e = items.get(wsid);
            if (tu == null) {
                // the API had no entry: item deleted or made private
                missing.add(wsid);
                job.message = "Checked " + i + "/" + ids.size();
            } else if (tu > e.timeUpdated) {
                withUpdates.addAll(e.modIds);
                job.message = "Update available: " + wsid;
            } else {
                job.message = "Checked " + i + "/" + ids.size();
            }
        }
        job.updates = withUpdates;
        job.done = ids.size();
        job.message = checkSummary(withUpdates.size(), missing);
        job.state = State.DONE;
    }

    private void runUpdateAll(Job job) {
        Map<String, WorkshopMap.Entry> items = backend.workshopMap().snapshot();
        List<String> ids = new ArrayList<>(items.keySet());
        job.message = "Checking for updates...";
        final Map<String, Long> remote;
        try {
            remote = WorkshopApi.getTimeUpdated(ids);
        } catch (Exception e) {
            fail(job, e);
            return;
        }
        Map<String, Long> outdated = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String wsid : ids) {
            Long tu = remote.get(wsid);
            if (tu == null) {
                missing.add(wsid);
            } else if (tu > items.get(wsid).timeUpdated) {
                outdated.put(wsid, tu);
            }
        }
        if (outdated.isEmpty()) {
            job.total = 0;
            job.message = checkSummary(0, missing);
            job.state = State.DONE;
            return;
        }
        job.total = outdated.size();
        int i = 0;
        for (Map.Entry<String, Long> e : outdated.entrySet()) {
            i++;
            job.message = "Downloading " + e.getKey() + " (" + i + "/" + outdated.size() + ")...";
            try {
                downloadAndInstall(e.getKey(), e.getValue());
            } catch (Exception ex) {
                fail(job, new Exception("failed on " + e.getKey() + ": " + ex.getMessage(), ex));
                return;
            }
            job.done = i;
        }
        job.message = "All mods up to date" + missingSuffix(missing);
        job.state = State.DONE;
    }

    private void runUpdate(Job job, String workshopId) {
        if (workshopId == null || !workshopId.matches("\\d+")) {
            fail(job, new IllegalArgumentException("invalid workshop id: " + workshopId));
            return;
        }
        job.total = 1;
        job.message = "Downloading " + workshopId + "...";
        long timeUpdated;
        try {
            timeUpdated = WorkshopApi.getTimeUpdated(List.of(workshopId))
                    .getOrDefault(workshopId, System.currentTimeMillis() / 1000L);
        } catch (Exception e) {
            // best effort: without a timestamp the next check will just re-download once
            timeUpdated = System.currentTimeMillis() / 1000L;
        }
        try {
            downloadAndInstall(workshopId, timeUpdated);
        } catch (Exception ex) {
            fail(job, ex);
            return;
        }
        job.done = 1;
        job.message = "Done";
        job.state = State.DONE;
    }

    private void downloadAndInstall(String workshopId, long timeUpdated) throws Exception {
        File itemDir = backend.steamCmd().download(
                workshopId, backend.cacheDir(), line -> System.out.println("[WorkshopBridge] " + line));
        // staging lives under the workshop cache (same filesystem as mods/,
        // so the swap renames stay atomic) and outside mods/ itself, where
        // the game's file watcher would trip over the transient backup dirs
        List<String> modIds = ModInstaller.install(
                itemDir, backend.modsDir(),
                new File(backend.cacheDir(), ".install-staging"),
                line -> System.out.println("[WorkshopBridge] " + line));
        backend.workshopMap().record(workshopId, modIds, timeUpdated);
    }

    private void fail(Job job, Throwable t) {
        job.state = State.FAILED;
        job.error = t.getMessage() == null ? t.toString() : t.getMessage();
        System.out.println("[WorkshopBridge] job " + job.id + " (" + job.kind + ") failed: " + t);
    }

    // package-private for tests
    static String checkSummary(int updateCount, List<String> missing) {
        String base = updateCount == 0
                ? "Everything is up to date"
                : updateCount + " mod(s) have updates";
        return base + missingSuffix(missing);
    }

    // package-private for tests
    static String missingSuffix(List<String> missing) {
        if (missing.isEmpty()) {
            return "";
        }
        return "; " + missing.size()
                + " workshop item(s) no longer listed (deleted or private?): "
                + String.join(", ", missing);
    }

    private void prune() {
        if (jobs.size() <= 50) {
            return;
        }
        jobs.entrySet().removeIf(e ->
                e.getValue().state == State.DONE || e.getValue().state == State.FAILED);
    }
}
