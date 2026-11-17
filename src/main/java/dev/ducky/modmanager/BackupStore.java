package dev.ducky.modmanager;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

public final class BackupStore {
    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    private static final Path BACKUP_ROOT = CONFIG_DIR.resolve("mod-manager-backups");
    private static final Set<String> EXCLUDED_TOP_LEVEL = Set.of(
        "assets", "libraries", "versions", "mods", "saves", "screenshots",
        "resourcepacks", "shaderpacks", "logs", "crash-reports",
        "server-resource-packs", "webcache", "cache", "natives", "mod-manager-backups"
    );
    private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;

    private BackupStore() {}

    /** Back up Minecraft options and small settings/config files for all installed mods. */
    public static synchronized boolean forceSave(MinecraftClient client) {
        return forceSaveAll(client).startsWith("SUCCESS:");
    }

    public static synchronized String forceSaveAll(MinecraftClient client) {
        Path staging = BACKUP_ROOT.resolve("latest.tmp");
        Path latest = BACKUP_ROOT.resolve("latest");
        try {
            if (client != null && client.options != null) client.options.write();
            Files.createDirectories(BACKUP_ROOT);
            deleteTree(staging);
            Files.createDirectories(staging);
            int copied = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(GAME_DIR)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (EXCLUDED_TOP_LEVEL.contains(name)) continue;
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        copied += copyDirectory(entry, staging.resolve(name));
                    } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        if (copyFile(entry, staging.resolve(name))) copied++;
                    }
                }
            }
            deleteTree(latest);
            move(staging, latest);
            return "SUCCESS: Full settings backup saved (" + copied + " files).";
        } catch (IOException | RuntimeException error) {
            System.err.println("[Mod Manager] Force Save All failed: " + error);
            try { deleteTree(staging); } catch (IOException ignored) {}
            return "FAILED: " + error.getClass().getSimpleName() + ". Check latest.log.";
        }
    }

    /** Best-effort per-mod backup: copies config files whose path/name contains the mod ID. */
    public static synchronized String forceSaveMod(MinecraftClient client, String modId) {
        if (modId == null || modId.isBlank() || modId.equals("minecraft/unassigned")) {
            return "Select an installed mod, not the unassigned keybind group.";
        }
        try {
            if (client != null && client.options != null) client.options.write();
            String safeId = modId.replaceAll("[^A-Za-z0-9._-]", "_");
            Path target = BACKUP_ROOT.resolve("mods").resolve(safeId).resolve("latest");
            Path staging = target.resolveSibling("latest.tmp");
            deleteTree(staging);
            Files.createDirectories(staging);
            String needle = modId.toLowerCase(Locale.ROOT);
            int[] copied = {0};
            if (Files.exists(CONFIG_DIR)) {
                Files.walkFileTree(CONFIG_DIR, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (Files.isSymbolicLink(dir) || dir.normalize().startsWith(BACKUP_ROOT.normalize()))
                            return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_SIZE || Files.isSymbolicLink(file))
                            return FileVisitResult.CONTINUE;
                        String relative = CONFIG_DIR.relativize(file).toString().toLowerCase(Locale.ROOT);
                        if (relative.contains(needle)) {
                            Path destination = staging.resolve(CONFIG_DIR.relativize(file));
                            Files.createDirectories(destination.getParent());
                            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            copied[0]++;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            if (copied[0] == 0) {
                deleteTree(staging);
                return "No config files matching '" + modId + "' were found. Private client settings may use another name.";
            }
            Files.createDirectories(target.getParent());
            deleteTree(target);
            move(staging, target);
            return "SUCCESS: Backed up " + copied[0] + " matching config file(s) for " + modId + ".";
        } catch (IOException | RuntimeException error) {
            System.err.println("[Mod Manager] Force Save Selected failed for " + modId + ": " + error);
            return "FAILED: Could not back up " + modId + ". Check latest.log.";
        }
    }

    private static int copyDirectory(Path source, Path target) throws IOException {
        if (source.normalize().startsWith(BACKUP_ROOT.normalize())) return 0;
        int[] copied = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir) || dir.normalize().startsWith(BACKUP_ROOT.normalize()))
                    return FileVisitResult.SKIP_SUBTREE;
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
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    copied[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return copied[0];
    }

    private static boolean copyFile(Path source, Path target) throws IOException {
        if (Files.size(source) > MAX_FILE_SIZE) return false;
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return true;
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
