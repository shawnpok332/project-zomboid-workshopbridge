package com.workshopbridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Moves a downloaded workshop item's mods into the game's mod folder.
 *
 * Each mod is installed with an atomic swap: the new tree is copied to a
 * staging dir first, then two renames swing it live ({@code dest -> backup},
 * {@code staging -> dest}). A rename is a single filesystem operation, so
 * {@code <mods>/<ModDir>/} is ever fully the old version or fully the new
 * one - never half-deleted or half-copied, even if the game is killed
 * mid-install.
 *
 * Staging and backup dirs live OUTSIDE the mods folder (in a stage dir under
 * the workshop cache): the game's own file watcher walks the mods tree, and
 * watching a backup appear and be deleted mid-walk throws scary errors in
 * the game log. Leftover staging dirs from a crashed run are repaired by
 * {@link #recoverInterruptedInstalls} before every install.
 */
public final class ModInstaller {
    private ModInstaller() {}

    /**
     * Installs every mod found in {@code <itemDir>/mods/>}.
     * {@code stageDir} holds the transient staging/backup dirs; it must be
     * on the same filesystem as {@code modsDir} for the renames to stay
     * atomic (a subdirectory of the workshop cache satisfies this).
     * Returns the installed PZ mod ids (from each mod.info {@code id=} line).
     */
    public static List<String> install(File itemDir, File modsDir, File stageDir,
            Consumer<String> log) throws IOException {
        File src = new File(itemDir, "mods");
        if (!src.isDirectory()) {
            throw new IOException("no mods/ in downloaded item: " + itemDir);
        }
        if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
            throw new IOException("cannot create mods dir: " + modsDir);
        }
        if (!stageDir.isDirectory() && !stageDir.mkdirs()) {
            throw new IOException("cannot create stage dir: " + stageDir);
        }
        recoverInterruptedInstalls(modsDir.toPath(), stageDir.toPath(), log);
        List<String> installed = new ArrayList<>();
        File[] modDirs = src.listFiles(File::isDirectory);
        if (modDirs == null || modDirs.length == 0) {
            throw new IOException("downloaded item has no mod folders: " + src);
        }
        for (File modDir : modDirs) {
            String modId = readModId(modDir);
            File dest = new File(modsDir, modDir.getName());
            log.accept("Installing " + modId + " -> " + dest.getAbsolutePath());
            try {
                atomicReplace(modDir.toPath(), dest.toPath(), stageDir.toPath(), log);
            } catch (IOException e) {
                throw withLongPathHint(e, dest);
            }
            installed.add(modId);
        }
        return installed;
    }

    /**
     * On Windows, paths past the legacy 260-char MAX_PATH fail depending on
     * the JRE's manifest and a registry key neither of which we control. When
     * a very long destination path looks like the cause, say so instead of
     * leaving the user with a bare IOException.
     */
    private static IOException withLongPathHint(IOException e, File dest) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                && dest.getAbsolutePath().length() > 240) {
            return new IOException(e.getMessage()
                    + " (this path is very long - Windows' 260-char path limit may be"
                    + " the cause; enable long paths via"
                    + " HKLM\\SYSTEM\\CurrentControlSet\\Control\\FileSystem\\LongPathsEnabled)",
                    e);
        }
        return e;
    }

    /**
     * Replaces {@code dest} with the tree at {@code src} atomically:
     * copy aside into the stage dir, then rename old->backup and
     * staging->dest. On failure the old tree is restored when possible;
     * anything unrecoverable is left for {@link #recoverInterruptedInstalls}
     * on the next run.
     */
    private static void atomicReplace(Path src, Path dest, Path stageDir,
            Consumer<String> log) throws IOException {
        String tag = Long.toHexString(System.nanoTime());
        String base = dest.getFileName().toString();
        Path staging = stageDir.resolve(base + ".new-" + tag);
        Path backup = stageDir.resolve(base + ".old-" + tag);
        copyRecursive(src, staging);
        boolean hadDest = Files.exists(dest);
        try {
            if (hadDest) {
                moveAtomic(dest, backup);
            }
            try {
                moveAtomic(staging, dest);
            } catch (IOException e) {
                if (hadDest) {
                    try {
                        moveAtomic(backup, dest);
                    } catch (IOException rollbackFailed) {
                        e.addSuppressed(rollbackFailed);
                    }
                }
                throw e;
            }
            // new tree is live; the backup is now garbage
            if (hadDest) {
                deleteRecursiveQuiet(backup, log);
            }
        } finally {
            deleteRecursiveQuiet(staging, log); // no-op once it was renamed live
        }
    }

    private static void moveAtomic(Path from, Path to) throws IOException {
        // A few retries: on Windows, Defender real-time scanning or the
        // game's file watcher can briefly hold handles inside the tree being
        // moved (transient AccessDeniedException). Harmless on Linux, where
        // the first attempt almost always succeeds.
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                try {
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to);
                }
                return;
            } catch (IOException e) {
                last = e;
                sleepQuiet(150);
            }
        }
        throw last;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Collects {@code <base>.new-TAG} / {@code <base>.old-TAG} dir pairs from one directory. */
    private static void collectPairs(Path dir, Map<String, Path> news, Map<String, Path> olds,
            Map<String, String> baseNames) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                String n = p.getFileName().toString();
                int ni = n.lastIndexOf(".new-");
                int oi = n.lastIndexOf(".old-");
                if (ni > 0) {
                    String tag = n.substring(ni + 5);
                    news.put(tag, p);
                    baseNames.put(tag, n.substring(0, ni));
                } else if (oi > 0) {
                    String tag = n.substring(oi + 5);
                    olds.put(tag, p);
                    baseNames.put(tag, n.substring(0, oi));
                }
            }
        } catch (IOException e) {
            // best effort; a later install will retry
        }
    }

    /**
     * Repairs staging dirs left by a crashed install. Pairing is by the
     * {@code .new-TAG} / {@code .old-TAG} suffix written by {@link #atomicReplace}.
     * Scans the stage dir (current layout) and the mods dir itself (leftovers
     * from older versions, which staged next to the destination).
     */
    static void recoverInterruptedInstalls(Path modsDir, Path stageDir, Consumer<String> log) {
        Map<String, Path> news = new HashMap<>();
        Map<String, Path> olds = new HashMap<>();
        Map<String, String> baseNames = new HashMap<>();
        collectPairs(modsDir, news, olds, baseNames);
        collectPairs(stageDir, news, olds, baseNames);
        for (String tag : baseNames.keySet()) {
            Path staging = news.get(tag);
            Path backup = olds.get(tag);
            Path dest = modsDir.resolve(baseNames.get(tag));
            try {
                if (staging != null && backup != null && !Files.exists(dest)) {
                    // crash between the two renames: staging is a complete
                    // new tree (it was fully copied before the first rename)
                    log.accept("Completing interrupted install of " + dest.getFileName());
                    moveAtomic(staging, dest);
                    deleteRecursiveQuiet(backup, log);
                } else if (staging != null && backup != null) {
                    // dest exists alongside both: swap must have completed
                    // (the renames are single operations); drop the backup
                    deleteRecursiveQuiet(backup, log);
                    deleteRecursiveQuiet(staging, log);
                } else if (backup != null) {
                    if (Files.exists(dest)) {
                        // swap completed, backup cleanup never ran
                        deleteRecursiveQuiet(backup, log);
                    } else {
                        // only the old tree survived: restore it, the mod
                        // being old beats the mod being absent
                        log.accept("Restoring " + dest.getFileName() + " from interrupted install");
                        moveAtomic(backup, dest);
                    }
                } else if (staging != null) {
                    // crash during the copy phase (or of a fresh install):
                    // the staging tree may be partial, drop it
                    deleteRecursiveQuiet(staging, log);
                }
            } catch (IOException e) {
                log.accept("warning: could not repair interrupted install of "
                        + dest.getFileName() + ": " + e.getMessage());
            }
        }
    }

    /** The {@code id=} value from mod.info, falling back to the folder name. */
    static String readModId(File modDir) {
        // B42 layouts first, then legacy flat mod.info. B42 ships versioned
        // dirs as "42.0" (observed on real installs), keep "42" as well.
        String[] candidates = {
                "common/mod.info", "42.0/mod.info", "42/mod.info",
                "41/mod.info", "40/mod.info", "mod.info"
        };
        for (String rel : candidates) {
            File f = new File(modDir, rel);
            if (!f.isFile()) {
                continue;
            }
            try (BufferedReader br = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    // a BOM at the start of mod.info (same Notepad theme as
                    // workshopbridge.properties) would otherwise defeat the
                    // id= match when it is the first line
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                    if (line.startsWith("id=")) {
                        String id = line.substring(3).trim();
                        if (!id.isEmpty()) {
                            return id;
                        }
                    }
                }
            } catch (IOException ignored) {
                // try next candidate
            }
        }
        return modDir.getName();
    }

    private static void copyRecursive(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(src.relativize(file));
                try {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException e) {
                    // attribute copy can fail across filesystems; content matters most
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Best-effort recursive delete. Retries a few times before giving up:
     * Windows Defender / file-watcher holds are transient, and a scary
     * "could not delete" warning for a backup dir nobody needs is worse than
     * a 300ms wait. Anything left behind is repaired by
     * {@link #recoverInterruptedInstalls} on the next run.
     */
    private static void deleteRecursiveQuiet(Path path, Consumer<String> log) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                return;
            } catch (RuntimeException e) {
                if (attempt == 2 || !Files.exists(path)) {
                    Throwable cause = e.getCause();
                    log.accept("warning: could not delete " + path + ": "
                            + (cause == null ? e : cause.getMessage()));
                    return;
                }
                sleepQuiet(150);
            } catch (IOException e) {
                // Files.walk itself failed
                if (attempt == 2) {
                    log.accept("warning: could not delete " + path + ": " + e.getMessage());
                    return;
                }
                sleepQuiet(150);
            }
        }
    }
}
