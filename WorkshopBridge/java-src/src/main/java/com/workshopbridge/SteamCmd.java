package com.workshopbridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Finds steamcmd and runs workshop downloads with it.
 *
 * Detection order: explicit override (<Zomboid>/workshopbridge.properties,
 * key "steamcmd.path") -> PATH -> common install locations.
 */
public final class SteamCmd {
    /** Project Zomboid's Steam app id. */
    public static final String APP_ID = "108600";

    /** Generous per-item timeout; big mods are slow. */
    private static final long DOWNLOAD_TIMEOUT_MINUTES = 20;

    private final File zomboidDir;

    public SteamCmd(File zomboidDir) {
        this.zomboidDir = zomboidDir;
    }

    /** Absolute path to the steamcmd executable/script, or null when not found. */
    public String findExecutable() {
        String override = readOverride();
        if (override != null && new File(override).isFile()) {
            return new File(override).getAbsolutePath();
        }
        boolean win = isWindows();
        // PATH lookup (both common executable names)
        for (String name : win
                ? new String[]{"steamcmd", "steamcmd.exe"}
                : new String[]{"steamcmd", "steamcmd.sh"}) {
            String hit = which(name, win);
            if (hit != null) {
                return hit;
            }
        }
        // common install locations
        String home = System.getProperty("user.home", "");
        List<String> candidates = new ArrayList<>();
        if (win) {
            candidates.add("C:\\steamcmd\\steamcmd.exe");
            candidates.add(home + "\\steamcmd\\steamcmd.exe");
        } else {
            candidates.add(home + "/steamcmd/steamcmd.sh");
            candidates.add("/opt/steamcmd/steamcmd.sh");
            candidates.add("/usr/local/bin/steamcmd");
        }
        for (String c : candidates) {
            if (new File(c).isFile()) {
                return c;
            }
        }
        return null;
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
        String exe = findExecutable();
        if (exe == null) {
            throw new IOException(
                    "steamcmd not found. Install it (Windows: unzip Valve's steamcmd.zip; "
                    + "Linux: steamcmd_linux.tar.gz + 32-bit libs), or set steamcmd.path in "
                    + new File(zomboidDir, "workshopbridge.properties"));
        }
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new IOException("cannot create cache dir: " + cacheDir);
        }
        List<String> cmd = new ArrayList<>();
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
            throw new IOException("failed to launch steamcmd: " + e.getMessage(), e);
        }
        StringBuilder output = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() < 200_000) {
                            output.append(line).append('\n');
                        }
                    }
                    log.accept("[steamcmd] " + line);
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
        if (!modsDir.isDirectory()) {
            String tail;
            synchronized (output) {
                String o = output.toString();
                tail = o.substring(Math.max(0, o.length() - 2000));
            }
            throw new IOException("steamcmd produced no mods/ for " + workshopId
                    + " (exit=" + p.exitValue() + "). Output tail:\n" + tail);
        }
        return itemDir;
    }

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

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** First line of `where`/`which`, or null. */
    private static String which(String name, boolean win) {
        try {
            Process p = new ProcessBuilder(win ? "where" : "which", name)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = br.readLine();
                boolean ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
                if (ok && line != null && !line.isBlank() && new File(line.trim()).isFile()) {
                    return new File(line.trim()).getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }
}
