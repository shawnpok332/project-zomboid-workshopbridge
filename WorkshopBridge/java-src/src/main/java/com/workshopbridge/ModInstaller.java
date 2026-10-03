package com.workshopbridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
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
import java.util.Map;
import java.util.function.Consumer;

/**
 * Moves a downloaded workshop item's mods into the game's mod folder.
 *
 * Each mod is installed with an atomic swap: the new tree is copied to a
 * staging dir first, then two renames swing it live ({@code dest -> dest.old-TAG},
 * {@code dest.new-TAG -> dest}). A rename is a single filesystem operation,
 * so {@code <mods>/<ModDir>/} is ever fully the old version or fully the
 * new one - never half-deleted or half-copied, even if the game is killed
 * mid-install. Leftover staging dirs from a crashed run are repaired by
 * {@link #recoverInterruptedInstalls} before every install.
 */
public final class ModInstaller {
    private ModInstaller() {}

    /**
     * Installs every mod found in {@code <itemDir>/mods/}.
     * Returns the installed PZ mod ids (from each mod.info {@code id=} line).
     */
    public static List<String> install(File itemDir, File modsDir, Consumer<String> log) throws IOException {
        File src = new File(itemDir, "mods");
        if (!src.isDirectory()) {
            throw new IOException("no mods/ in downloaded item: " + itemDir);
        }
        if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
            throw new IOException("cannot create mods dir: " + modsDir);
        }
        recoverInterruptedInstalls(modsDir.toPath(), log);
        List<String> installed = new ArrayList<>();
        File[] modDirs = src.listFiles(File::isDirectory);
        if (modDirs == null || modDirs.length == 0) {
            throw new IOException("downloaded item has no mod folders: " + src);
        }
        for (File modDir : modDirs) {
            String modId = readModId(modDir);
            File dest = new File(modsDir, modDir.getName());
            log.accept("Installing " + modId + " -> " + dest.getAbsolutePath());
            atomicReplace(modDir.toPath(), dest.toPath(), log);
            installed.add(modId);
        }
        return installed;
    }

    /**
     * Replaces {@code dest} with the tree at {@code src} atomically:
     * copy aside, then rename old->backup and staging->dest. On failure the
     * old tree is restored when possible; anything unrecoverable is left for
     * {@link #recoverInterruptedInstalls} on the next run.
     */
    private static void atomicReplace(Path src, Path dest, Consumer<String> log) throws IOException {
        String tag = Long.toHexString(System.nanoTime());
        Path staging = dest.resolveSibling(dest.getFileName() + ".new-" + tag);
        Path backup = dest.resolveSibling(dest.getFileName() + ".old-" + tag);
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
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /**
     * Repairs staging dirs left by a crashed install. Pairing is by the
     * {@code .new-TAG} / {@code .old-TAG} suffix written by {@link #atomicReplace}.
     */
    static void recoverInterruptedInstalls(Path modsDir, Consumer<String> log) {
        Map<String, Path> news = new HashMap<>();
        Map<String, Path> olds = new HashMap<>();
        Map<String, String> baseNames = new HashMap<>();
        try (var stream = Files.list(modsDir)) {
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
            return; // best effort; a later install will retry
        }
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
        // B42 layouts first, then legacy flat mod.info
        String[] candidates = {
                "common/mod.info", "42/mod.info", "41/mod.info", "40/mod.info", "mod.info"
        };
        for (String rel : candidates) {
            File f = new File(modDir, rel);
            if (!f.isFile()) {
                continue;
            }
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
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

    /** Best-effort recursive delete; logs and swallows failures. */
    private static void deleteRecursiveQuiet(Path path, Consumer<String> log) {
        if (path == null || !Files.exists(path)) {
            return;
        }
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
        } catch (RuntimeException e) {
            log.accept("warning: could not delete " + path + ": " + e.getCause());
        } catch (IOException e) {
            log.accept("warning: could not delete " + path + ": " + e.getMessage());
        }
    }
}
