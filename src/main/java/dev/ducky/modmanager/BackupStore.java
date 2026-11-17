package dev.ducky.modmanager;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Makes a best-effort backup of on-disk client settings. It deliberately
 * excludes large game data such as worlds, mods, assets and resource packs.
 */
public final class BackupStore {
    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir();
    private static final Path BACKUP_ROOT = FabricLoader.getInstance().getConfigDir()
        .resolve("mod-manager-backups");
    private static final Set<String> EXCLUDED_TOP_LEVEL = Set.of(
        "assets", "libraries", "versions", "mods", "saves", "screenshots",
        "resourcepacks", "shaderpacks", "logs", "crash-reports",
        "server-resource-packs", "webcache", "cache", "natives",
        "mod-manager-backups"
    );
    private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;

    private BackupStore() {}

    public static synchronized boolean forceSave(MinecraftClient client) {
        Path staging = BACKUP_ROOT.resolve("latest.tmp");
        Path latest = BACKUP_ROOT.resolve("latest");
        try {
            // Ask Minecraft to write its own options before copying settings.
            if (client != null && client.options != null) {
                client.options.write();
            }

            Files.createDirectories(BACKUP_ROOT);
            deleteTree(staging);
            Files.createDirectories(staging);

            try (DirectoryStream<Path> entries = Files.newDirectoryStream(GAME_DIR)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (EXCLUDED_TOP_LEVEL.contains(name)) continue;
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        copyDirectory(entry, staging.resolve(name));
                    } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        copyFile(entry, staging.resolve(name));
                    }
                }
            }

            // Keep a dedicated copy of the standard config directory.
            Path configDir = FabricLoader.getInstance().getConfigDir();
            if (Files.isDirectory(configDir)) {
                copyDirectory(configDir, staging.resolve("config"));
            }

            Path dated = BACKUP_ROOT.resolve("backup-" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            deleteTree(latest);
            move(staging, latest);
            copyDirectory(latest, dated);
            return true;
        } catch (IOException | RuntimeException error) {
            System.err.println("[Mod Manager] Force Save failed: " + error);
            try { deleteTree(staging); } catch (IOException ignored) {}
            return false;
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        if (source.normalize().startsWith(BACKUP_ROOT.normalize())) return;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                Path relative = source.relativize(dir);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && attrs.size() <= MAX_FILE_SIZE && !Files.isSymbolicLink(file)) {
                    Path relative = source.relativize(file);
                    Path destination = target.resolve(relative);
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyFile(Path source, Path target) throws IOException {
        if (Files.size(source) <= MAX_FILE_SIZE) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
