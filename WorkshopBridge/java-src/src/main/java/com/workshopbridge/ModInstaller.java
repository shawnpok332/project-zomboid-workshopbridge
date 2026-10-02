package com.workshopbridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Moves a downloaded workshop item's mods into the game's mod folder.
 * Each inner {@code mods/<ModDir>/} is clean-replaced into
 * {@code <Zomboid>/mods/<ModDir>/} so stale files from older versions
 * can never linger.
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
        List<String> installed = new ArrayList<>();
        File[] modDirs = src.listFiles(File::isDirectory);
        if (modDirs == null || modDirs.length == 0) {
            throw new IOException("downloaded item has no mod folders: " + src);
        }
        for (File modDir : modDirs) {
            String modId = readModId(modDir);
            File dest = new File(modsDir, modDir.getName());
            log.accept("Installing " + modId + " -> " + dest.getAbsolutePath());
            deleteRecursive(dest.toPath());
            copyRecursive(modDir.toPath(), dest.toPath());
            installed.add(modId);
        }
        return installed;
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
                Files.copy(file, dest.resolve(src.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new RuntimeException("cannot delete " + p + ": " + e.getMessage(), e);
                    }
                });
    }
}
