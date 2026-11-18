package dev.ducky.modmanager;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class BackupStore {
    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
    private static final Path BACKUP_ROOT = CONFIG_DIR.resolve("mod-manager-backups").normalize();

    private static final Set<String> EXCLUDED_DIRS = Set.of(
        "assets", "libraries", "versions", "mods", "saves", "screenshots",
        "resourcepacks", "shaderpacks", "logs", "crash-reports",
        "server-resource-packs", "webcache", "cache", "natives",
        "mod-manager-backups", "downloads", "libraries"
    );
    private static final Set<String> SETTINGS_DIRS = Set.of(
        "config", "configs", "settings", "profiles", "modules",
        "clients", "client", "presets", "options"
    );
    private static final Set<String> SETTINGS_EXTENSIONS = Set.of(
        ".json", ".json5", ".jsonc", ".toml", ".cfg", ".properties",
        ".ini", ".yaml", ".yml", ".xml"
    );
    private static final long MAX_FILE_SIZE = 20L * 1024L * 1024L;
    private static final long MAX_TOTAL_SIZE = 128L * 1024L * 1024L;

    private BackupStore() {}

    public static synchronized boolean forceSave(MinecraftClient client) {
        return forceSaveAll(client).startsWith("SUCCESS:");
    }

    /**
     * Copies Minecraft options and all recognized settings/config directories.
     * This is a disk snapshot, not a command to serialize private in-memory client objects.
     */
    public static synchronized String forceSaveAll(MinecraftClient client) {
        try {
            writeMinecraftOptions(client);
            Path latest = BACKUP_ROOT.resolve("latest");
            int[] copied = snapshotSettings(latest, null, "all");
            if (copied[0] == 0) {
                return "FAILED: No settings files were found to back up. Check latest.log.";
            }
            return "SUCCESS: Saved " + copied[0] + " settings file(s) (" + formatBytes(copied[1])
                + ") to config/mod-manager-backups/latest.";
        } catch (IOException | RuntimeException error) {
            logFailure("Force Save All", error);
            return "FAILED: " + error.getClass().getSimpleName() + ". No successful backup was confirmed.";
        }
    }

    /**
     * For an individual client, capture the complete config/settings trees as well as files
     * whose paths include the mod ID. Private clients often use generic filenames, so matching
     * only the mod ID would miss their settings.
     */
    public static synchronized String forceSaveMod(MinecraftClient client, String modId) {
        if (modId == null || modId.isBlank() || modId.equals("minecraft/unassigned")) {
            return "Select an installed client/mod first.";
        }
        try {
            writeMinecraftOptions(client);
            String safeId = safeId(modId);
            Path target = BACKUP_ROOT.resolve("mods").resolve(safeId).resolve("latest");
            int[] copied = snapshotSettings(target, modId.toLowerCase(Locale.ROOT), modId);
            if (copied[0] == 0) {
                return "FAILED: No client/config files found. The client may keep settings only in memory.";
            }
            return "SUCCESS: Saved " + copied[0] + " settings file(s) (" + formatBytes(copied[1])
                + ") for " + modId + ". Backup includes the full config/settings folders.";
        } catch (IOException | RuntimeException error) {
            logFailure("Force Save Selected for " + modId, error);
            return "FAILED: Could not back up " + modId + ". Check latest.log.";
        }
    }

    /** Restore a selected mod's most recent disk snapshot into the game directory. */
    public static synchronized String restoreLatestMod(String modId) {
        if (modId == null || modId.isBlank() || modId.equals("minecraft/unassigned")) {
            return "Select an installed client/mod first.";
        }
        Path source = BACKUP_ROOT.resolve("mods").resolve(safeId(modId)).resolve("latest").resolve("files");
        if (!Files.isDirectory(source)) {
            return "No saved backup exists for " + modId + ". Use Force Save Selected first.";
        }
        try {
            int[] restored = {0};
            long[] bytes = {0L};
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                    Path relative = source.relativize(file).normalize();
                    if (relative.isAbsolute() || relative.startsWith("..")) return FileVisitResult.CONTINUE;
                    Path destination = GAME_DIR.resolve(relative).normalize();
                    if (!destination.startsWith(GAME_DIR) || destination.startsWith(BACKUP_ROOT)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    restored[0]++;
                    bytes[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
            if (restored[0] == 0) return "FAILED: The backup contained no restorable files.";
            return "SUCCESS: Restored " + restored[0] + " file(s) (" + formatBytes(bytes[0])
                + "). Restart the client to reload its settings.";
        } catch (IOException | RuntimeException error) {
            logFailure("Restore backup for " + modId, error);
            return "FAILED: Could not restore the backup. Check latest.log.";
        }
    }

    private static void writeMinecraftOptions(MinecraftClient client) {
        if (client != null && client.options != null) client.options.write();
    }

    private static int[] snapshotSettings(Path target, String modIdNeedle, String scope) throws IOException {
        Path staging = target.resolveSibling(target.getFileName() + ".tmp");
        Path filesRoot = staging.resolve("files");
        deleteTree(staging);
        Files.createDirectories(filesRoot);

        AtomicLong totalBytes = new AtomicLong();
        int[] copied = {0};
        try {
            Files.walkFileTree(GAME_DIR, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                    if (dir.equals(GAME_DIR)) return FileVisitResult.CONTINUE;
                    Path relative = GAME_DIR.relativize(dir);
                    if (relative.getNameCount() > 0
                        && EXCLUDED_DIRS.contains(relative.getName(0).toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (dir.normalize().startsWith(BACKUP_ROOT)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(file) || attrs.size() > MAX_FILE_SIZE) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = GAME_DIR.relativize(file).normalize();
                    if (relative.isAbsolute() || relative.startsWith("..")) return FileVisitResult.CONTINUE;
                    String lowerPath = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                    if (lowerPath.startsWith("config/mod-manager-backups/")) return FileVisitResult.CONTINUE;

                    boolean settingsTree = isInsideSettingsDirectory(relative);
                    boolean settingsFile = isRecognizedSettingsFile(relative);
                    boolean selectedById = modIdNeedle != null && lowerPath.contains(modIdNeedle);
                    if (!settingsTree && !settingsFile && !selectedById) return FileVisitResult.CONTINUE;

                    long current = totalBytes.get();
                    if (current + attrs.size() > MAX_TOTAL_SIZE) return FileVisitResult.CONTINUE;

                    Path destination = filesRoot.resolve(relative).normalize();
                    if (!destination.startsWith(filesRoot)) return FileVisitResult.CONTINUE;
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    copied[0]++;
                    totalBytes.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }
            });

            Properties manifest = new Properties();
            manifest.setProperty("scope", scope);
            manifest.setProperty("created", Instant.now().toString());
            manifest.setProperty("files", Integer.toString(copied[0]));
            manifest.setProperty("bytes", Long.toString(totalBytes.get()));
            Files.createDirectories(staging);
            try (Writer writer = Files.newBufferedWriter(staging.resolve("manifest.properties"))) {
                manifest.store(writer, "Mod Manager settings snapshot");
            }

            Files.createDirectories(target.getParent());
            deleteTree(target);
            move(staging, target);
            return new int[]{copied[0], Math.toIntExact(Math.min(Integer.MAX_VALUE, totalBytes.get()))};
        } catch (IOException | RuntimeException error) {
            deleteTree(staging);
            throw error;
        }
    }

    private static boolean isInsideSettingsDirectory(Path relative) {
        for (Path part : relative) {
            if (SETTINGS_DIRS.contains(part.toString().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isRecognizedSettingsFile(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("options.txt") || name.equals("optionsof.txt") || name.equals("servers.dat")) return true;
        for (String extension : SETTINGS_EXTENSIONS) {
            if (name.endsWith(extension)) return true;
        }
        return false;
    }

    private static String safeId(String modId) {
        return modId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void logFailure(String operation, Throwable error) {
        System.err.println("[Mod Manager] " + operation + " failed: " + error);
        error.printStackTrace(System.err);
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
