package com.workshopbridge;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Finds steamcmd and runs workshop downloads with it.
 *
 * Discovery order (deliberately minimal - no PATH parsing, no install
 * location guessing):
 *   1. Explicit override ({@code steamcmd.path} in
 *      {@code <Zomboid>/workshopbridge.properties}) - always wins, but is
 *      still validated; a broken override fails fast with a clear message
 *      instead of silently falling through.
 *   2. The mod-managed bootstrap dir from a previous run, so a bootstrapped
 *      copy is reused rather than re-downloaded.
 *   Every candidate is validated by actually executing it
 *      ({@code <exe> +quit}, ~30s timeout) and checking the output looks like
 *      steamcmd. A file that merely exists is not proof it works (missing
 *      32-bit libs on Linux, corrupt installs, ...).
 *   3. If nothing usable is found, a working copy is bootstrapped automatically
 *      from Valve's CDN into the mod-managed {@code <Zomboid>/workshop_cache/steamcmd/}
 *      using only JDK stdlib (HttpClient + ZipInputStream + a small tar reader).
 *      When that dir lives on a filesystem that refuses to execute files
 *      (noexec mounts are common for removable media, e.g. a game library on
 *      an external drive), the steamcmd *binary* is bootstrapped into a
 *      fallback dir under the user's cache instead ({@code ~/.cache/} or
 *      {@code $XDG_CACHE_HOME}); workshop downloads still land in the primary
 *      cache dir, only the executable moves.
 *
 * CONSENT MODEL (deliberate choice): bootstrapping is AUTOMATIC inside the
 * download path (it runs on the JobManager background thread with progress
 * messages through the job log), not a separate user-confirmed step. Rationale:
 * the user already consented to network activity by clicking Update; steamcmd
 * is Valve's official tool fetched from Valve's CDN into the mod's own cache
 * dir; and every existing tool in this space (pz_launcher, etc.) installs
 * steamcmd as part of its flow. Failures (no network, macOS, corrupt
 * download) surface as failed jobs with actionable messages.
 *
 * LUA FOLLOW-UP (not done here): if the UI ever wants an explicit
 * "Install steamcmd" indicator/button, expose a new Lua global (e.g.
 * {@code wbEnsureSteamCmd()}) that runs {@link #ensureInstalled} on a
 * background thread and returns a job id. The plumbing below already supports
 * it; only the Lua side + a JobManager entry point would be new.
 */
public final class SteamCmd {
    /** Project Zomboid's Steam app id. */
    public static final String APP_ID = "108600";

    /** Generous per-item timeout; big mods are slow. */
    private static final long DOWNLOAD_TIMEOUT_MINUTES = 20;

    /** How long a candidate gets to prove it is steamcmd. */
    private static final long VALIDATE_TIMEOUT_SECONDS = 30;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Serializes bootstraps: extracting over a copy another thread is
     * validating/running fails with ETXTBSY ("Text file busy"). */
    private static final Object BOOTSTRAP_LOCK = new Object();

    /** Matches ANSI SGR color sequences (ESC[...m), which steamcmd emits. */
    private static final java.util.regex.Pattern ANSI_SGR =
            java.util.regex.Pattern.compile("\u001B\\[[0-9;]*m");

    private final File zomboidDir;

    /**
     * Prefix prepended to steamcmd invocations on systems that cannot run
     * Valve's prebuilt binaries directly. On NixOS there is no
     * /lib/ld-linux.so.2, so foreign binaries need steam-run (or a
     * system-wide nix-ld, in which case no prefix is needed and none is
     * applied). Empty everywhere else.
     */
    private static final List<String> LAUNCH_PREFIX = computeLaunchPrefix();

    /** Last validated path (positive results are cached; misses are re-scanned). */
    private volatile String cachedExe;
    /** Why the last explicit override was rejected (null when none set / valid). */
    private volatile String overrideError;

    public SteamCmd(File zomboidDir) {
        this.zomboidDir = zomboidDir;
    }

    /**
     * Absolute path to a validated steamcmd executable, or null when none is
     * configured yet. Never triggers a download.
     *
     * Deliberately simple: no PATH parsing, no guessing install locations.
     * Either the user configured an explicit path, or we use our own
     * bootstrapped copy. Anything in between is a support headache.
     */
    public String findExecutable() {
        overrideError = null;
        String override = readOverride();
        if (override != null) {
            File f = new File(override);
            if (!f.isFile()) {
                overrideError = "steamcmd.path points at a missing file: " + override
                        + " (use forward slashes, e.g. C:/steamcmd/steamcmd.exe;"
                        + " single backslashes are properties escapes)";
                return null;
            }
            String reason = validateExecutable(f.getAbsolutePath());
            if (!validationOk(reason)) {
                overrideError = "steamcmd.path is not a working steamcmd (" + reason + "): " + override
                        + missing32BitHint(reason) + posixSpawnHint(reason);
                return null;
            }
            if (reason != null) {
                // validation timed out: probably a first-run self-update; the
                // real download completes it (same leniency as bootstrap())
                System.out.println("[WorkshopBridge] steamcmd.path validation timed out,"
                        + " proceeding anyway (first-run self-update?): " + override);
            }
            return f.getAbsolutePath();
        }
        if (cachedExe != null && new File(cachedExe).isFile()) {
            return cachedExe;
        }
        cachedExe = null;
        // our own managed copy from a previous bootstrap - a known location,
        // not a system-wide hunt. Checks every candidate dir: on a noexec
        // filesystem the primary copy can exist yet never validate.
        // validationOk treats a timeout as usable (first-run self-update).
        for (File dir : steamCmdDirs()) {
            File managed = new File(dir, isWindows() ? "steamcmd.exe" : "steamcmd.sh");
            if (managed.isFile()
                    && validationOk(validateExecutable(managed.getAbsolutePath()))) {
                cachedExe = managed.getAbsolutePath();
                return cachedExe;
            }
        }
        return null;
    }

    /** The steamcmd binary dir inside the Zomboid folder (primary candidate). */
    private File primarySteamCmdDir() {
        return new File(zomboidDir,
                "workshop_cache" + File.separator + "steamcmd");
    }

    /**
     * Fallback dir for the steamcmd binary when the primary dir is on a
     * filesystem that refuses to execute files. Package-private for tests.
     */
    static File fallbackSteamCmdDir(String xdgCacheHome, String userHome) {
        File base = (xdgCacheHome != null && !xdgCacheHome.isBlank())
                ? new File(xdgCacheHome)
                : new File(userHome, ".cache");
        return new File(base, "workshopbridge" + File.separator + "steamcmd");
    }

    private static File fallbackSteamCmdDir() {
        return fallbackSteamCmdDir(System.getenv("XDG_CACHE_HOME"),
                System.getProperty("user.home"));
    }

    /** Candidate steamcmd binary dirs in preference order. */
    private List<File> steamCmdDirs() {
        List<File> dirs = new ArrayList<>();
        dirs.add(primarySteamCmdDir());
        File fb = fallbackSteamCmdDir();
        if (!fb.equals(primarySteamCmdDir())) {
            dirs.add(fb);
        }
        return dirs;
    }

    /**
     * Returns a working steamcmd path, bootstrapping one from Valve's CDN when
     * nothing usable is installed. Throws with a user-readable reason when that
     * is impossible (explicit override broken, no network, macOS, ...).
     */
    public String ensureInstalled(Consumer<String> log) throws IOException {
        Consumer<String> out = log != null ? log : s -> {};
        String exe = findExecutable();
        if (exe != null) {
            return exe;
        }
        if (overrideError != null) {
            throw new IOException(overrideError + " -- fix or remove steamcmd.path in "
                    + new File(zomboidDir, "workshopbridge.properties"));
        }
        synchronized (BOOTSTRAP_LOCK) {
            // another thread may have bootstrapped while we waited
            exe = findExecutable();
            if (exe != null) {
                return exe;
            }
            return bootstrap(out);
        }
    }

    /**
     * Downloads a workshop item. Files land in
     * {@code <cacheDir>/steamapps/workshop/content/108600/<workshopId>/}.
     * Returns the item directory (which contains {@code mods/}).
     */
    public File download(String workshopId, File cacheDir, Consumer<String> log) throws IOException {
        if (workshopId == null || !workshopId.matches("\\d+")) {
            throw new IOException("refusing to download invalid workshop id: " + workshopId);
        }
        // ensureInstalled bootstraps from Valve's CDN when needed (background thread).
        String exe = ensureInstalled(log);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new IOException("cannot create cache dir: " + cacheDir);
        }
        List<String> cmd = new ArrayList<>(LAUNCH_PREFIX);
        cmd.add(exe);
        // NB: +force_install_dir must come before +login, or steamcmd errors out.
        cmd.add("+force_install_dir");
        cmd.add(cacheDir.getAbsolutePath());
        cmd.add("+login");
        cmd.add("anonymous");
        cmd.add("+workshop_download_item");
        cmd.add(APP_ID);
        cmd.add(workshopId);
        cmd.add("+quit");

        log.accept("Running: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        final Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("failed to launch steamcmd: " + e.getMessage()
                    + posixSpawnHint(e.getMessage()), e);
        }
        StringBuilder output = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    final String clean = stripAnsi(line);
                    synchronized (output) {
                        if (output.length() < 200_000) {
                            output.append(clean).append('\n');
                        }
                    }
                    log.accept("[steamcmd] " + clean);
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "workshopbridge-steamcmd-drain");
        drainer.setDaemon(true);
        drainer.start();
        boolean finished;
        try {
            finished = p.waitFor(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted while downloading " + workshopId, e);
        }
        if (!finished) {
            p.destroyForcibly();
            try {
                // bound the overlap window: with serialized downloads the
                // next job must not start while this process is still exiting
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    System.out.println("[WorkshopBridge] warning: steamcmd for "
                            + workshopId + " did not exit after destroy");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("steamcmd timed out downloading " + workshopId);
        }
        try {
            drainer.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        File itemDir = new File(cacheDir,
                "steamapps/workshop/content/" + APP_ID + "/" + workshopId);
        File modsDir = new File(itemDir, "mods");
        String tail;
        synchronized (output) {
            String o = output.toString();
            tail = o.substring(Math.max(0, o.length() - 2000));
        }
        int exit = p.exitValue();
        if (exit != 0) {
            // never trust a stale cache dir: a failed update must not
            // install the previous download as if it were fresh
            throw new IOException("steamcmd failed for " + workshopId
                    + " (exit=" + exit + "). Output tail:\n" + tail);
        }
        if (!modsDir.isDirectory()) {
            throw new IOException("steamcmd produced no mods/ for " + workshopId
                    + " (exit=" + exit + "). Output tail:\n" + tail);
        }
        return itemDir;
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    /**
     * steamcmd colors its output with ANSI SGR sequences; the game's log
     * renderer drops the ESC byte and leaves "[0m" garbage behind, so strip
     * them before logging. Package-private for tests.
     */
    static String stripAnsi(String s) {
        if (s == null) {
            return null;
        }
        return ANSI_SGR.matcher(s).replaceAll("");
    }

    /**
     * Suggests the FORK launch-mechanism flag when process spawning itself is
     * blocked (posix_spawn + EACCES, seen inside steam-run's bubblewrap
     * sandbox). The mod tries to set this itself at load (see Main), but the
     * JDK may have read the default already. Package-private for tests.
     */
    static String posixSpawnHint(String reason) {
        String low = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (low.contains("posix_spawn")
                && (low.contains("permission denied") || low.contains("error=13")
                        || low.contains("error: 13"))) {
            return " The JDK's posix_spawn launcher is blocked in this environment"
                    + " (seen inside sandboxes such as steam-run's bubblewrap); retry with"
                    + " -Djdk.lang.Process.launchMechanism=FORK on the game's Java command line.";
        }
        return "";
    }

    /**
     * A null reason means usable. A validation *timeout* also means usable: it
     * almost always indicates steamcmd's first-run self-update, which the real
     * download that follows completes (generous timeout, output tail on
     * failure). Treating it as "not found" caused pointless re-bootstraps.
     * Package-private for tests.
     */
    static boolean validationOk(String reason) {
        return reason == null || reason.startsWith("timed out");
    }

    /**
     * Targeted hint appended to validation failures that look like missing
     * 32-bit runtime libraries (the classic Linux problem: steamcmd.sh execs
     * a 32-bit binary, which dies with exit=127 / "No such file or directory"
     * when the 32-bit loader is absent, even though every file exists).
     * Returns "" when the failure looks like something else.
     * Package-private so tests can cover the signatures.
     */
    static String missing32BitHint(String reason) {
        return missing32BitHint(reason, isNixOS());
    }

    // package-private for tests
    static String missing32BitHint(String reason, boolean nixos) {
        String low = reason.toLowerCase(Locale.ROOT);
        if (nixos && low.contains("error=2") && low.contains("no such file")) {
            // The script itself is present and executable, but the kernel
            // cannot find its interpreter (/bin/bash) - the classic NixOS
            // "no FHS" symptom, surfacing one step earlier than the 32-bit
            // loader problem below.
            return " On NixOS, Valve's steamcmd needs an FHS environment to launch:"
                    + " install steam-run from nixpkgs (or enable nix-ld), make sure it is"
                    + " on PATH when you start the game, and retry.";
        }
        if (low.contains("shared librar") || low.contains("exit=127")) {
            if (nixos) {
                return " NixOS cannot run Valve's prebuilt steamcmd directly: install"
                        + " steam-run from nixpkgs (or enable nix-ld) and retry.";
            }
            return " This usually means the 32-bit runtime libraries are missing."
                    + " Debian/Ubuntu: sudo apt install lib32gcc-s1 lib32stdc++6 |"
                    + " Arch: sudo pacman -S lib32-gcc-libs |"
                    + " Fedora: sudo dnf install glibc.i686 libstdc++.i686 -- then retry.";
        }
        return "";
    }

    private static List<String> computeLaunchPrefix() {
        if (isNixOS() && commandExists("steam-run")) {
            System.out.println("[WorkshopBridge] NixOS detected: running steamcmd via steam-run");
            return List.of("steam-run");
        }
        return List.of();
    }

    private static boolean commandExists(String name) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v " + name)
                    .redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** True on NixOS (no FHS /lib: foreign binaries need steam-run or nix-ld). */
    private static boolean isNixOS() {
        try {
            return isNixOSRelease(
                    Files.readString(Path.of("/etc/os-release"), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    // package-private for tests
    static boolean isNixOSRelease(String osRelease) {
        for (String line : osRelease.split("\n")) {
            String t = line.trim();
            if (t.equals("ID=nixos") || t.equals("ID_LIKE=nixos")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the candidate briefly and checks it behaves like steamcmd.
     * Returns null when acceptable, otherwise a human-readable reason.
     */
    private static String validateExecutable(String path) {
        File f = new File(path);
        if (!f.isFile()) {
            return "not a file";
        }
        if (!isWindows()) {
            try {
                if (!Files.isExecutable(f.toPath())) {
                    return "not executable";
                }
            } catch (SecurityException e) {
                return "cannot check executability: " + e.getMessage();
            }
        }
        Process p;
        try {
            List<String> cmd = new ArrayList<>(LAUNCH_PREFIX);
            cmd.add(path);
            cmd.add("+quit");
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            return "cannot launch: " + e.getMessage();
        }
        StringBuilder output = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() < 64_000) {
                            output.append(stripAnsi(line)).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "workshopbridge-validate-drain");
        drainer.setDaemon(true);
        drainer.start();
        boolean finished;
        try {
            finished = p.waitFor(VALIDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return "interrupted";
        }
        if (!finished) {
            p.destroyForcibly();
            return "timed out after " + VALIDATE_TIMEOUT_SECONDS
                    + "s without identifying as steamcmd (a first-run self-update can take minutes;"
                    + " it completes during a real download)";
        }
        try {
            drainer.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String o;
        synchronized (output) {
            o = output.toString();
        }
        String low = o.toLowerCase(Locale.ROOT);
        if (low.contains("steam console client") || low.contains("steam>")) {
            return null;
        }
        String excerpt = o.length() > 200 ? o.substring(0, 200) + "..." : o;
        return "did not identify as steamcmd (exit=" + p.exitValue()
                + ", output: " + excerpt.trim() + ")";
    }

    // ------------------------------------------------------------------
    // bootstrap from Valve's CDN
    // ------------------------------------------------------------------

    private static String cdnUrl(boolean win) {
        // System property hook exists so tests can point at a local server;
        // production default is Valve's CDN.
        String base = System.getProperty("workshopbridge.cdn",
                "https://client-update.steamstatic.com/installer/");
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base + (win ? "steamcmd.zip" : "steamcmd_linux.tar.gz");
    }

    /**
     * Downloads and extracts a private steamcmd copy into a mod-managed dir.
     * Deliberately does NOT exec-validate the fresh copy: the first launch
     * triggers steamcmd's self-update, which would blow the validation timeout.
     * Existence + executability is checked here; the real download right after
     * will surface any deeper problem (e.g. missing 32-bit libs) with its
     * output tail.
     *
     * Tries each candidate dir in order: when the primary dir is on a
     * filesystem that refuses to execute files (noexec), validation fails
     * with "permission denied" and we retry in the fallback dir.
     */
    private String bootstrap(Consumer<String> log) throws IOException {
        boolean win = isWindows();
        if (isMac()) {
            throw new IOException("Valve does not distribute steamcmd for macOS, so WorkshopBridge "
                    + "cannot bootstrap it. Install a community build and point steamcmd.path at it in "
                    + new File(zomboidDir, "workshopbridge.properties"));
        }
        List<File> dirs = steamCmdDirs();
        IOException lastFailure = null;
        for (int i = 0; i < dirs.size(); i++) {
            File dir = dirs.get(i);
            try {
                return bootstrapIn(dir, win, log);
            } catch (IOException e) {
                boolean hasNext = i < dirs.size() - 1;
                if (hasNext && isExecDenied(e.getMessage())) {
                    log.accept("Cannot execute files in " + dir + " (permission denied -"
                            + " the drive may be mounted with 'noexec'); trying " + dirs.get(i + 1)
                            + " for the steamcmd binary instead.");
                    lastFailure = e;
                } else {
                    throw e;
                }
            }
        }
        throw lastFailure != null ? lastFailure
                : new IOException("no steamcmd install location available");
    }

    /** True when a failure message smells like EACCES on exec. Package-private for tests. */
    static boolean isExecDenied(String message) {
        if (message == null) {
            return false;
        }
        String low = message.toLowerCase(Locale.ROOT);
        return low.contains("permission denied")
                || low.contains("error=13")
                || low.contains("error: 13");
    }

    private String bootstrapIn(File dir, boolean win, Consumer<String> log) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        String url = cdnUrl(win);
        log.accept("steamcmd not found - downloading from Valve (" + url + ")...");
        File tmp = new File(dir, "steamcmd-download.tmp");
        final String exe;
        try {
            downloadFile(url, tmp, log);
            log.accept("Extracting steamcmd to " + dir + "...");
            exe = installArchive(tmp, win, dir, log);
        } finally {
            tmp.delete();
        }
        // Fast-fail check with a targeted hint for the classic Linux problem.
        // A timeout here just means the first-run self-update kicked in; the
        // real download that follows will complete it.
        String reason = validateExecutable(exe);
        if (!validationOk(reason)) {
            throw new IOException("Bootstrapped steamcmd failed validation in " + dir + ": " + reason
                    + missing32BitHint(reason) + posixSpawnHint(reason));
        }
        cachedExe = exe;
        log.accept("steamcmd ready at " + exe
                + " (its first run self-updates, which can take a few minutes)");
        return exe;
    }

    /**
     * Extracts a downloaded steamcmd archive into {@code dir} and returns the
     * executable path. Package-private so tests can exercise it without network.
     */
    String installArchive(File archive, boolean win, File dir, Consumer<String> log) throws IOException {
        if (win) {
            unzip(archive, dir);
        } else {
            untarGz(archive, dir);
        }
        String exe = new File(dir, win ? "steamcmd.exe" : "steamcmd.sh").getAbsolutePath();
        File exeFile = new File(exe);
        if (!exeFile.isFile()) {
            throw new IOException("steamcmd archive extracted but produced no executable"
                    + " (corrupt download?) in " + dir);
        }
        if (!win && !exeFile.setExecutable(true)) {
            log.accept("warning: could not set executable bit on " + exe);
        }
        return exe;
    }

    private static void downloadFile(String url, File dest, Consumer<String> log) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "WorkshopBridge/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + url, e);
        } catch (IOException e) {
            throw new IOException(Net.friendlyMessage(e), e);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("download failed: HTTP " + resp.statusCode() + " for " + url);
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        try (InputStream in = resp.body();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            long read = 0;
            long lastLog = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0 && read - lastLog > 2_000_000) {
                    lastLog = read;
                    log.accept(String.format(Locale.ROOT,
                            "Downloading steamcmd... %.1f MB / %.1f MB", read / 1e6, total / 1e6));
                }
            }
            log.accept(String.format(Locale.ROOT, "Downloaded steamcmd (%.1f MB)", read / 1e6));
        }
    }

    /** Zip extraction with zip-slip protection (stdlib only). */
    private static void unzip(File zipFile, File destDir) throws IOException {
        destDir.mkdirs();
        String canonicalDest = destDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[8192];
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(canonicalDest)) {
                    throw new IOException("archive entry escapes target dir: " + e.getName());
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (OutputStream o = new FileOutputStream(out)) {
                        int n;
                        while ((n = zin.read(buf)) != -1) {
                            o.write(buf, 0, n);
                        }
                    }
                }
                zin.closeEntry();
            }
        }
    }

    /**
     * Minimal tar.gz reader (stdlib only): regular files and directories,
     * ustar names incl. prefix, octal sizes, exec-bit preservation.
     * Anything exotic (symlinks, pax headers, ...) is skipped safely.
     */
    private static void untarGz(File tgz, File destDir) throws IOException {
        destDir.mkdirs();
        String canonicalDest = destDir.getCanonicalPath() + File.separator;
        try (InputStream gz = new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(tgz)))) {
            byte[] header = new byte[512];
            while (true) {
                int got = readFully(gz, header);
                if (got == 0) {
                    break; // clean EOF between entries: accept as end of archive
                }
                if (got < 512) {
                    throw new EOFException("truncated tar header");
                }
                if (isZeroBlock(header)) {
                    break;
                }
                String name = readCString(header, 0, 100);
                String prefix = readCString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = parseOctal(header, 124, 12);
                char type = (char) (header[156] & 0xFF);
                int mode = (int) parseOctal(header, 100, 8);
                File out = new File(destDir, name);
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(canonicalDest)) {
                    throw new IOException("archive entry escapes target dir: " + name);
                }
                if (type == '5') {
                    out.mkdirs();
                    // directories have no data; fall through to padding skip below
                    size = 0;
                } else if (type == '0' || type == 0) {
                    File parent = out.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (OutputStream o = new FileOutputStream(out)) {
                        copyN(gz, o, size);
                    }
                    if ((mode & 0111) != 0 && !out.setExecutable(true, false)) {
                        // non-fatal; validation later will catch a truly broken binary
                    }
                }
                // skip file data already consumed for regular files; for anything
                // else skip the whole entry payload
                long consumed = (type == '0' || type == 0) ? size : 0;
                long remaining = size - consumed;
                long pad = (512 - (size % 512)) % 512;
                skipFully(gz, remaining + pad);
            }
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte v : b) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readCString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] b, int off, int len) throws IOException {
        long v = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') {
                continue;
            }
            if (c < '0' || c > '7') {
                throw new IOException("bad octal field in tar header");
            }
            any = true;
            v = (v << 3) + (c - '0');
        }
        if (!any) {
            throw new IOException("empty octal field in tar header");
        }
        return v;
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int want = (int) Math.min(buf.length, n);
            int got = in.read(buf, 0, want);
            if (got == -1) {
                throw new EOFException("truncated tar entry data");
            }
            out.write(buf, 0, got);
            n -= got;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                // skip() may return 0; fall back to reading
                if (in.read() == -1) {
                    throw new EOFException("truncated tar entry data");
                }
                n--;
            } else {
                n -= skipped;
            }
        }
    }

    // ------------------------------------------------------------------
    // override config
    // ------------------------------------------------------------------

    private String readOverride() {
        File props = new File(zomboidDir, "workshopbridge.properties");
        if (!props.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(props)) {
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("steamcmd.path");
            return v == null || v.isBlank() ? null : v.trim();
        } catch (IOException e) {
            System.out.println("[WorkshopBridge] cannot read " + props + ": " + e);
            return null;
        }
    }
}
