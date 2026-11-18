package dev.ducky.modmanager;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

public final class BackupStore {
    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
    private static final Path BACKUP_ROOT = CONFIG_DIR.resolve("mod-manager-backups").normalize();
    private static final long MAX_FILE_SIZE = 20L * 1024L * 1024L;
    private static final long MAX_TOTAL_SIZE = 128L * 1024L * 1024L;

    private BackupStore() {}

    public static synchronized boolean forceSave(MinecraftClient client) {
        String result = forceSaveAll(client);
        return result.startsWith("VERIFIED:");
    }

    /** Invokes each installed mod's detectable save API, then snapshots only discovered config files. */
    public static synchronized String forceSaveAll(MinecraftClient client) {
        LinkedHashMap<Path, ClientConfigPersistence.SaveResult> fileOwners = new LinkedHashMap<>();
        int methodsFound = 0;
        int methodsInvoked = 0;
        int methodsSucceeded = 0;
        LinkedHashSet<Path> changedPaths = new LinkedHashSet<>();
        int changedLines = 0;
        int modsScanned = 0;

        try {
            List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
            mods.sort(Comparator.comparing(mod -> mod.getMetadata().getId()));
            for (ModContainer mod : mods) {
                String id;
                try {
                    id = mod.getMetadata().getId();
                } catch (Throwable ignored) {
                    continue;
                }
                if (id == null || id.isBlank()
                    || Set.of("minecraft", "java", "fabricloader", "fabric-api", "modmanager").contains(id)) {
                    continue;
                }

                ClientConfigPersistence.SaveResult result = ClientConfigPersistence.saveAndDiscover(id);
                modsScanned++;
                methodsFound += result.methodsFound();
                methodsInvoked += result.methodsInvoked();
                methodsSucceeded += result.methodsSucceeded();
                changedPaths.addAll(result.changedFiles());
                changedLines += result.changedLines();
                for (Path file : result.files()) fileOwners.put(file, result);
            }

            if (fileOwners.isEmpty()) {
                return "SAVE NOT VERIFIED: scanned " + modsScanned
                    + " mods, invoked " + methodsSucceeded + " client save method(s), but found no client config files.";
            }

            List<Path> files = new ArrayList<>(fileOwners.keySet());
            SnapshotResult snapshot = writeSnapshot(
                BACKUP_ROOT.resolve("latest"), "all", "all", "All detected client configs",
                files, changedPaths, changedLines, methodsFound, methodsInvoked, methodsSucceeded
            );

            if (snapshot.changedFiles() > 0) {
                return "VERIFIED: saved " + methodsSucceeded + "/" + methodsInvoked
                    + " detected save method(s); " + snapshot.changedFiles() + " changed config file(s), "
                    + snapshot.copiedFiles() + " file(s) backed up and hash-verified.";
            }
            return "BACKED UP, NOT VERIFIED: copied and verified " + snapshot.copiedFiles()
                + " detected config file(s), but no file changed after save calls. Latest module values are unconfirmed.";
        } catch (IOException | RuntimeException error) {
            logFailure("Force Save All", error);
            return "FAILED: " + error.getClass().getSimpleName() + ". No verified backup was confirmed.";
        }
    }

    /** Saves and discovers a selected client's config, including generic-named files and config roots. */
    public static synchronized String forceSaveMod(MinecraftClient client, String modId) {
        if (modId == null || modId.isBlank() || modId.equals("minecraft/unassigned")) {
            return "Select an installed client/mod first.";
        }

        try {
            ClientConfigPersistence.SaveResult result = ClientConfigPersistence.saveAndDiscover(modId);
            if (result.files().isEmpty()) {
                return "SAVE NOT VERIFIED: found " + result.methodsFound() + " save method(s), invoked "
                    + result.methodsInvoked() + ", but detected no config files for " + result.modName()
                    + ". Check latest.log for CONFIG DISCOVERY details.";
            }

            String safeId = safeId(modId);
            SnapshotResult snapshot = writeSnapshot(
                BACKUP_ROOT.resolve("mods").resolve(safeId).resolve("latest"),
                modId, result.modId(), result.modName(), result.files(),
                result.changedFiles(), result.changedLines(),
                result.methodsFound(), result.methodsInvoked(), result.methodsSucceeded()
            );

            if (snapshot.changedFiles() > 0) {
                return "VERIFIED: " + result.modName() + " saved. "
                    + result.methodsSucceeded() + "/" + result.methodsInvoked()
                    + " save method(s) succeeded; " + snapshot.changedFiles()
                    + " changed config file(s) and " + snapshot.copiedFiles()
                    + " total config file(s) were hash-verified.";
            }
            return "BACKED UP, NOT VERIFIED: " + snapshot.copiedFiles()
                + " client config file(s) were hash-verified, but no file changed after the save call. "
                + "The newest in-memory setting is not confirmed on disk.";
        } catch (IOException | RuntimeException error) {
            logFailure("Force Save Selected for " + modId, error);
            return "FAILED: Could not save/backup " + modId + ". Check latest.log.";
        }
    }

    /** Restore only the exact files listed in this selected client's manifest. */
    public static synchronized String restoreLatestMod(String modId) {
        if (modId == null || modId.isBlank() || modId.equals("minecraft/unassigned")) {
            return "Select an installed client/mod first.";
        }

        Path snapshot = BACKUP_ROOT.resolve("mods").resolve(safeId(modId)).resolve("latest").normalize();
        Path manifestPath = snapshot.resolve("manifest.properties");
        Path filesRoot = snapshot.resolve("files");
        if (!Files.isRegularFile(manifestPath) || !Files.isDirectory(filesRoot)) {
            return "No backup exists for this client yet. Use Force Save Selected first.";
        }

        Properties manifest = new Properties();
        try (var reader = Files.newBufferedReader(manifestPath)) {
            manifest.load(reader);
        } catch (IOException error) {
            logFailure("Read restore manifest for " + modId, error);
            return "FAILED: The backup manifest could not be read.";
        }

        String manifestId = manifest.getProperty("modId", "");
        if (!manifestId.equals(modId)) {
            return "FAILED: This backup belongs to a different mod. Nothing was restored.";
        }

        int expected;
        try {
            expected = Integer.parseInt(manifest.getProperty("fileCount", "0"));
        } catch (NumberFormatException error) {
            return "FAILED: Backup manifest file count is invalid.";
        }

        int restored = 0;
        long bytes = 0;
        try {
            for (int index = 0; index < expected; index++) {
                String listedPath = manifest.getProperty("file." + index);
                if (listedPath == null || listedPath.isBlank()) continue;

                Path relative;
                try {
                    relative = Paths.get(listedPath).normalize();
                } catch (InvalidPathException invalid) {
                    continue;
                }
                if (relative.isAbsolute() || relative.startsWith("..") || relative.getNameCount() == 0) continue;

                Path source = filesRoot.resolve(relative).normalize();
                Path destination = GAME_DIR.resolve(relative).normalize();
                if (!source.startsWith(filesRoot) || !destination.startsWith(GAME_DIR)
                    || destination.startsWith(BACKUP_ROOT) || isProtectedMinecraftSetting(relative)
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(source)) {
                    continue;
                }

                String expectedHash = manifest.getProperty("sha256." + index, "");
                if (expectedHash.isBlank() || !sha256(source).equals(expectedHash)) {
                    throw new IOException("Backup hash check failed for " + relative);
                }
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                if (Files.mismatch(source, destination) != -1L) {
                    throw new IOException("Restore verification failed for " + relative);
                }
                restored++;
                bytes += Files.size(source);
            }

            if (restored == 0) return "FAILED: No safe client config files were available to restore.";
            return "RESTORED: " + restored + " file(s) (" + formatBytes(bytes)
                + ") for " + manifest.getProperty("modName", modId)
                + ". Minecraft options and unrelated global settings were left untouched. Restart the client.";
        } catch (IOException | RuntimeException error) {
            logFailure("Restore backup for " + modId, error);
            return "FAILED: Restore stopped after " + restored + " file(s). Check latest.log.";
        }
    }

    private record SnapshotResult(int copiedFiles, int changedFiles, long bytes) {}

    private static SnapshotResult writeSnapshot(
        Path target, String scope, String modId, String modName, Collection<Path> requestedFiles,
        Collection<Path> changedPaths, int changedLines, int methodsFound, int methodsInvoked, int methodsSucceeded
    ) throws IOException {
        Path staging = target.resolveSibling(target.getFileName() + ".tmp");
        Path filesRoot = staging.resolve("files");
        deleteTree(staging);
        Files.createDirectories(filesRoot);

        int copied = 0;
        int verifiedChanged = 0;
        long totalBytes = 0;
        Properties manifest = new Properties();
        List<String> relativePaths = new ArrayList<>();

        try {
            LinkedHashSet<Path> unique = new LinkedHashSet<>(requestedFiles);
            List<Path> sorted = new ArrayList<>(unique);
            sorted.sort(Comparator.comparing(path -> GAME_DIR.relativize(path.toAbsolutePath().normalize()).toString()));

            for (Path original : sorted) {
                Path source = original.toAbsolutePath().normalize();
                if (!source.startsWith(GAME_DIR) || isProtectedMinecraftSetting(GAME_DIR.relativize(source))
                    || Files.isSymbolicLink(source)
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;

                long size = Files.size(source);
                if (size > MAX_FILE_SIZE || totalBytes + size > MAX_TOTAL_SIZE) continue;

                Path relative = GAME_DIR.relativize(source).normalize();
                if (relative.isAbsolute() || relative.startsWith("..")) continue;
                Path destination = filesRoot.resolve(relative).normalize();
                if (!destination.startsWith(filesRoot)) continue;

                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                if (Files.mismatch(source, destination) != -1L) {
                    throw new IOException("Backup verification failed for " + relative);
                }

                String relativeText = relative.toString();
                manifest.setProperty("file." + copied, relativeText);
                manifest.setProperty("sha256." + copied, sha256(destination));
                relativePaths.add(relativeText);
                copied++;
                totalBytes += size;
                if (changedPaths.contains(source)) verifiedChanged++;
            }

            if (copied == 0) {
                deleteTree(staging);
                throw new IOException("No safe config files could be copied.");
            }

            manifest.setProperty("scope", scope);
            manifest.setProperty("modId", modId);
            manifest.setProperty("modName", modName);
            manifest.setProperty("created", Instant.now().toString());
            manifest.setProperty("fileCount", Integer.toString(copied));
            manifest.setProperty("byteCount", Long.toString(totalBytes));
            manifest.setProperty("changedFileCount", Integer.toString(verifiedChanged));
            manifest.setProperty("changedLineCount", Integer.toString(changedLines));
            manifest.setProperty("saveMethodsFound", Integer.toString(methodsFound));
            manifest.setProperty("saveMethodsInvoked", Integer.toString(methodsInvoked));
            manifest.setProperty("saveMethodsSucceeded", Integer.toString(methodsSucceeded));

            Files.createDirectories(staging);
            try (Writer writer = Files.newBufferedWriter(staging.resolve("manifest.properties"))) {
                manifest.store(writer, "Mod Manager verified client config snapshot");
            }

            Files.createDirectories(target.getParent());
            deleteTree(target);
            move(staging, target);
            System.err.println("[Mod Manager] BACKUP VERIFIED scope=" + scope
                + " mod=" + modId + " files=" + copied + " changed=" + verifiedChanged
                + " bytes=" + totalBytes);
            return new SnapshotResult(copied, verifiedChanged, totalBytes);
        } catch (IOException | RuntimeException error) {
            deleteTree(staging);
            throw error;
        }
    }

    private static boolean isProtectedMinecraftSetting(Path relative) {
        if (relative == null || relative.getNameCount() == 0) return true;
        String normalized = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Set.of(
            "options.txt", "optionsof.txt", "servers.dat", "servers.dat_old",
            "launcher_profiles.json", "usercache.json", "realms_persistence.json",
            "mod-manager.properties", "mod-manager-client-keys.properties"
        ).contains(name)) return true;
        return normalized.startsWith("config/mod-manager-backups/")
            || normalized.contains("/mod-manager-backups/");
    }

    private static String safeId(String modId) {
        return modId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String sha256(Path file) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
