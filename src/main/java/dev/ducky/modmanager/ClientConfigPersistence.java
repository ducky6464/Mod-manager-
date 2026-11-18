package dev.ducky.modmanager;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds a mod's own config/save API without assuming that its file name contains its mod ID.
 * Only save-like methods on config/settings/module/keybind/profile/storage classes are invoked.
 */
public final class ClientConfigPersistence {
    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();

    private static final Set<String> EXCLUDED_ROOTS = Set.of(
        "assets", "libraries", "versions", "mods", "saves", "screenshots",
        "resourcepacks", "shaderpacks", "logs", "crash-reports",
        "server-resource-packs", "webcache", "cache", "natives", "downloads"
    );
    private static final Set<String> SETTINGS_DIRS = Set.of(
        "config", "configs", "settings", "profiles", "modules", "clients",
        "client", "presets", "options", "module", "values", "features",
        "clickgui", "gui", "ui", "data"
    );
    private static final Set<String> SETTINGS_EXTENSIONS = Set.of(
        ".json", ".json5", ".jsonc", ".toml", ".cfg", ".properties",
        ".ini", ".yaml", ".yml", ".xml", ".txt"
    );
    private static final Set<String> SAVE_METHODS = Set.of(
        "save", "saveconfig", "saveconfigs", "savesettings", "savemodules",
        "saveall", "saveprofile", "savestate", "write", "writeconfig",
        "writeconfigs", "writesettings", "store", "persist", "flush",
        "flushconfig", "flushsettings"
    );
    private static final int MAX_CLASSES_TO_INSPECT = 1800;
    private static final int MAX_INDEXED_FILES = 12000;
    private static final long MAX_INDEX_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_FILE_SIZE = 20L * 1024L * 1024L;
    private static final long MAX_TEXT_SIZE = 1024L * 1024L;

    private ClientConfigPersistence() {}

    public record SaveResult(
        String modId,
        String modName,
        List<Path> files,
        List<Path> changedFiles,
        int methodsFound,
        int methodsInvoked,
        int methodsSucceeded,
        int changedLines
    ) {}

    private record Fingerprint(String hash, long size, long modified, String text) {}
    private record MethodStats(
        int found, int invoked, int succeeded, List<Path> configRoots
    ) {}

    public static SaveResult saveAndDiscover(String modId) {
        ModContainer mod = FabricLoader.getInstance().getModContainer(modId).orElse(null);
        if (mod == null) {
            System.err.println("[Mod Manager] No Fabric container found for config scan: " + modId);
            return new SaveResult(modId, modId, List.of(), List.of(), 0, 0, 0, 0);
        }

        String modName;
        try {
            modName = mod.getMetadata().getName();
        } catch (Throwable error) {
            modName = modId;
        }

        List<String> tokens = makeTokens(modId, modName);
        Map<Path, Fingerprint> before = indexConfigFiles();
        MethodStats methods = invokeClientSaveMethods(mod);
        Map<Path, Fingerprint> after = indexConfigFiles();

        LinkedHashSet<Path> selected = new LinkedHashSet<>();
        LinkedHashSet<Path> changed = new LinkedHashSet<>();
        int changedLines = 0;

        for (Map.Entry<Path, Fingerprint> entry : after.entrySet()) {
            Path file = entry.getKey();
            Fingerprint previous = before.get(file);
            Fingerprint current = entry.getValue();
            boolean didChange = changed(previous, current);
            if (didChange) {
                changed.add(file);
                changedLines += countAddedLines(previous == null ? null : previous.text(), current.text());
            }
            if (matchesPath(file, tokens) || containsToken(file, current, tokens) || didChange) {
                selected.add(file);
            }
        }

        // A private client may hold the actual target path in a Config/Settings manager field.
        for (Path root : methods.configRoots()) {
            Path safe = root.toAbsolutePath().normalize();
            if (!safe.startsWith(GAME_DIR) || isProtectedPath(safe)) continue;
            if (Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS)) {
                if (after.containsKey(safe)) selected.add(safe);
            } else if (Files.isDirectory(safe, LinkOption.NOFOLLOW_LINKS)) {
                for (Path file : after.keySet()) if (file.startsWith(safe)) selected.add(file);
            }
        }

        // If a generic-named config file changed inside a dedicated client subfolder,
        // include its companion files as part of that client's complete configuration.
        List<Path> changedParents = new ArrayList<>();
        for (Path file : changed) {
            Path parent = file.getParent();
            if (parent == null || parent.equals(GAME_DIR) || parent.equals(CONFIG_DIR)) continue;
            Path relative = GAME_DIR.relativize(parent);
            if (relative.getNameCount() >= 2 && isPotentialClientFolder(relative)) {
                changedParents.add(parent);
            }
        }
        for (Path parent : changedParents) {
            for (Path file : after.keySet()) if (file.startsWith(parent)) selected.add(file);
        }

        selected.removeIf(path -> !after.containsKey(path)
            || !path.startsWith(GAME_DIR)
            || isProtectedPath(path)
            || !isPotentialConfigFile(GAME_DIR.relativize(path)));

        List<Path> files = new ArrayList<>(selected);
        files.sort(Comparator.comparing(path -> GAME_DIR.relativize(path).toString()));
        List<Path> changedFiles = new ArrayList<>(changed);
        changedFiles.retainAll(selected);
        changedFiles.sort(Comparator.comparing(path -> GAME_DIR.relativize(path).toString()));

        System.err.println("[Mod Manager] CONFIG DISCOVERY mod=" + modId
            + " name=" + modName
            + " saveMethods=" + methods.succeeded() + "/" + methods.invoked()
            + " discoveredFiles=" + files.size()
            + " changedFiles=" + changedFiles.size()
            + " changedLines=" + changedLines);

        for (Path file : changedFiles) {
            Fingerprint fp = after.get(file);
            System.err.println("[Mod Manager] CONFIG CHANGED " + GAME_DIR.relativize(file)
                + " bytes=" + fp.size() + " sha256=" + fp.hash().substring(0, 12));
        }

        return new SaveResult(
            modId, modName, List.copyOf(files), List.copyOf(changedFiles),
            methods.found(), methods.invoked(), methods.succeeded(), changedLines
        );
    }

    private static Map<Path, Fingerprint> indexConfigFiles() {
        Map<Path, Fingerprint> indexed = new LinkedHashMap<>();
        long[] totalBytes = {0L};
        try {
            Files.walkFileTree(GAME_DIR, EnumSet.noneOf(FileVisitOption.class), 8,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                        if (dir.equals(GAME_DIR)) return FileVisitResult.CONTINUE;
                        Path relative = GAME_DIR.relativize(dir);
                        if (relative.getNameCount() > 0
                            && EXCLUDED_ROOTS.contains(relative.getName(0).toString().toLowerCase(Locale.ROOT))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        String normalized = dir.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                        if (normalized.contains("/mod-manager-backups")) return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (indexed.size() >= MAX_INDEXED_FILES || totalBytes[0] >= MAX_INDEX_BYTES) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_SIZE || Files.isSymbolicLink(file)) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path relative = GAME_DIR.relativize(file).normalize();
                        if (relative.isAbsolute() || relative.startsWith("..")
                            || isProtectedPath(file) || !isPotentialConfigFile(relative)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (totalBytes[0] + attrs.size() > MAX_INDEX_BYTES) return FileVisitResult.CONTINUE;
                        try {
                            String hash = sha256(file);
                            String text = isTextFile(relative) && attrs.size() <= MAX_TEXT_SIZE
                                ? readTextSafely(file) : null;
                            indexed.put(file.toAbsolutePath().normalize(),
                                new Fingerprint(hash, attrs.size(), attrs.lastModifiedTime().toMillis(), text));
                            totalBytes[0] += attrs.size();
                        } catch (IOException error) {
                            System.err.println("[Mod Manager] Could not inspect config file "
                                + relative + ": " + error.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException | RuntimeException error) {
            System.err.println("[Mod Manager] Config directory scan failed: " + error);
            error.printStackTrace(System.err);
        }
        return indexed;
    }

    private static boolean isPotentialConfigFile(Path relative) {
        if (relative.getNameCount() == 0) return false;
        if (insideSettingsDirectory(relative)) return true;
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : SETTINGS_EXTENSIONS) if (name.endsWith(extension)) return true;
        return false;
    }

    private static boolean insideSettingsDirectory(Path relative) {
        for (Path part : relative) {
            String value = part.toString().toLowerCase(Locale.ROOT);
            if (SETTINGS_DIRS.contains(value)) return true;
        }
        return false;
    }

    private static boolean isTextFile(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : SETTINGS_EXTENSIONS) if (name.endsWith(extension)) return true;
        return false;
    }

    private static boolean isProtectedPath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.startsWith(CONFIG_DIR.resolve("mod-manager-backups").normalize())) return true;
        String name = absolute.getFileName() == null ? "" : absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Set.of(
            "options.txt", "optionsof.txt", "servers.dat", "servers.dat_old",
            "launcher_profiles.json", "usercache.json", "realms_persistence.json",
            "mod-manager.properties", "mod-manager-client-keys.properties"
        ).contains(name)) return true;
        String path = absolute.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return path.contains("/mod-manager-backups/");
    }

    private static boolean isPotentialClientFolder(Path relative) {
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (SETTINGS_DIRS.contains(name)) continue;
            if (name.length() >= 3) return true;
        }
        return false;
    }

    private static boolean changed(Fingerprint before, Fingerprint after) {
        return before == null || !before.hash().equals(after.hash())
            || before.size() != after.size() || before.modified() != after.modified();
    }

    private static int countAddedLines(String oldText, String newText) {
        if (newText == null || newText.isEmpty()) return 0;
        Set<String> oldLines = oldText == null ? Set.of()
            : new HashSet<>(Arrays.asList(oldText.split("\\R", -1)));
        int added = 0;
        for (String line : newText.split("\\R", -1)) if (!oldLines.contains(line)) added++;
        return added;
    }

    private static boolean matchesPath(Path file, List<String> tokens) {
        String normalized = normalize(file.toString());
        for (String token : tokens) if (normalized.contains(token)) return true;
        return false;
    }

    private static boolean containsToken(Path file, Fingerprint fp, List<String> tokens) {
        if (fp.text() == null) return false;
        String text = normalize(fp.text());
        for (String token : tokens) if (text.contains(token)) return true;
        return false;
    }

    private static List<String> makeTokens(String id, String name) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addToken(tokens, id);
        addToken(tokens, name);
        for (String part : (id + " " + name).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) addToken(tokens, part);
        return new ArrayList<>(tokens);
    }

    private static void addToken(Set<String> tokens, String value) {
        String normalized = normalize(value == null ? "" : value);
        if (normalized.length() >= 4) tokens.add(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String readTextSafely(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);
            int suspicious = 0;
            for (int i = 0; i < Math.min(text.length(), 2048); i++) {
                char c = text.charAt(i);
                if (c == 0) suspicious++;
            }
            return suspicious > 0 ? null : text;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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

    private static MethodStats invokeClientSaveMethods(ModContainer mod) {
        Set<String> classNames = discoverCandidateClasses(mod);
        Set<String> invokedKeys = new HashSet<>();
        LinkedHashSet<Path> configRoots = new LinkedHashSet<>();
        int found = 0;
        int invoked = 0;
        int succeeded = 0;

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ClientConfigPersistence.class.getClassLoader();

        for (String className : classNames) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                if (!isSaveClass(type.getName())) continue;

                List<Object> owners = new ArrayList<>();
                inspectFields(type, null, true, owners, configRoots);

                for (Method method : type.getDeclaredMethods()) {
                    if (!isAccessor(method)) continue;
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(null);
                        if (value != null && !isScalar(value)) {
                            owners.add(value);
                            inspectFields(value.getClass(), value, false, owners, configRoots);
                        }
                    } catch (Throwable error) {
                        System.err.println("[Mod Manager] Client config accessor failed "
                            + type.getName() + "#" + method.getName() + ": " + rootMessage(error));
                    }
                }

                for (Method method : type.getDeclaredMethods()) {
                    if (!isSaveMethod(method) || method.isBridge() || method.isSynthetic()) continue;
                    found++;
                    String key = type.getName() + "#" + method.getName();
                    if (!invokedKeys.add(key)) continue;

                    try {
                        method.setAccessible(true);
                        if (Modifier.isStatic(method.getModifiers())) {
                            invoked++;
                            Object result = method.invoke(null);
                            if (!(result instanceof Boolean flag) || flag) succeeded++;
                        } else {
                            Object owner = null;
                            for (Object candidate : owners) {
                                if (candidate != null && type.isInstance(candidate)) {
                                    owner = candidate;
                                    break;
                                }
                            }
                            if (owner != null) {
                                invoked++;
                                Object result = method.invoke(owner);
                                if (!(result instanceof Boolean flag) || flag) succeeded++;
                            }
                        }
                    } catch (Throwable error) {
                        System.err.println("[Mod Manager] Client save method failed "
                            + key + ": " + rootMessage(error));
                        error.printStackTrace(System.err);
                    }
                }
            } catch (Throwable error) {
                System.err.println("[Mod Manager] Could not inspect client config class "
                    + className + ": " + rootMessage(error));
            }
        }

        return new MethodStats(found, invoked, succeeded, List.copyOf(configRoots));
    }

    private static boolean isSaveClass(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("config") || value.contains("setting")
            || value.contains("modulemanager") || value.contains("keybind")
            || value.contains("profile") || value.contains("storage")
            || value.contains("valuemanager") || value.contains("featuremanager")
            || value.contains("preset") || value.contains("clientdata");
    }

    private static boolean isSaveMethod(Method method) {
        return method.getParameterCount() == 0
            && SAVE_METHODS.contains(method.getName().toLowerCase(Locale.ROOT));
    }

    private static boolean isAccessor(Method method) {
        if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) return false;
        String name = method.getName().toLowerCase(Locale.ROOT);
        if (!(name.equals("getinstance") || name.equals("instance")
            || name.equals("getconfig") || name.equals("getconfigmanager")
            || name.equals("getmanager") || name.equals("getstorage"))) return false;
        return hasClassHint(method.getReturnType().getName());
    }

    private static boolean hasClassHint(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("config") || value.contains("setting")
            || value.contains("module") || value.contains("keybind")
            || value.contains("profile") || value.contains("storage")
            || value.contains("manager") || value.contains("preset")
            || value.contains("clientdata") || value.contains("value");
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
            || value instanceof Character || value instanceof Enum<?> || value instanceof Path
            || value instanceof File;
    }

    private static void inspectFields(
        Class<?> type, Object owner, boolean staticOnly,
        List<Object> owners, Set<Path> configRoots
    ) {
        for (Field field : type.getDeclaredFields()) {
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            if (staticOnly != isStatic) continue;
            String fieldName = field.getName().toLowerCase(Locale.ROOT);
            boolean locationField = fieldName.contains("config") || fieldName.contains("setting")
                || fieldName.contains("path") || fieldName.contains("file")
                || fieldName.contains("directory") || fieldName.contains("folder")
                || fieldName.contains("storage") || fieldName.equals("dir");
            boolean objectField = fieldName.contains("instance") || fieldName.contains("singleton")
                || fieldName.contains("manager") || fieldName.contains("config")
                || fieldName.contains("setting") || fieldName.contains("storage")
                || fieldName.contains("module") || fieldName.contains("profile");
            if (!locationField && !objectField) continue;

            try {
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value == null) continue;
                if (locationField) addConfigLocation(value, configRoots);
                if (objectField && !isScalar(value)
                    && owners.stream().noneMatch(existing -> existing == value)) {
                    owners.add(value);
                    inspectFields(value.getClass(), value, false, owners, configRoots);
                }
            } catch (Throwable error) {
                System.err.println("[Mod Manager] Could not inspect config field "
                    + type.getName() + "#" + field.getName() + ": " + rootMessage(error));
            }
        }
    }

    private static void addConfigLocation(Object value, Set<Path> configRoots) {
        Path candidate = null;
        try {
            if (value instanceof Path path) candidate = path;
            else if (value instanceof File file) candidate = file.toPath();
            else if (value instanceof String text && !text.isBlank() && text.length() <= 512) {
                String cleaned = text.trim();
                if (cleaned.contains("/") || cleaned.contains("\\") || cleaned.contains(".")) {
                    candidate = Paths.get(cleaned);
                }
            }
        } catch (InvalidPathException ignored) {
            return;
        }
        if (candidate == null) return;
        if (!candidate.isAbsolute()) {
            Path configRelative = CONFIG_DIR.resolve(candidate).normalize();
            Path gameRelative = GAME_DIR.resolve(candidate).normalize();
            if (Files.exists(configRelative, LinkOption.NOFOLLOW_LINKS)) candidate = configRelative;
            else candidate = gameRelative;
        }
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(GAME_DIR) || candidate.equals(GAME_DIR)
            || candidate.equals(CONFIG_DIR) || isProtectedPath(candidate)) return;
        configRoots.add(candidate);
        System.err.println("[Mod Manager] CLIENT CONFIG LOCATION " + GAME_DIR.relativize(candidate));
    }

    private static Set<String> discoverCandidateClasses(ModContainer mod) {
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        try {
            Method rootsMethod = mod.getClass().getMethod("getRootPaths");
            Object rootsValue = rootsMethod.invoke(mod);
            if (rootsValue instanceof Iterable<?> roots) {
                for (Object rootValue : roots) {
                    if (!(rootValue instanceof Path root)) continue;
                    if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                        collectDirectoryClasses(root, classes);
                    } else if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)
                        && root.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        collectJarClasses(root, classes);
                    }
                    if (classes.size() >= MAX_CLASSES_TO_INSPECT) break;
                }
            }
        } catch (Throwable error) {
            System.err.println("[Mod Manager] Could not enumerate client classes for "
                + mod.getMetadata().getId() + ": " + rootMessage(error));
        }
        return classes;
    }

    private static void collectJarClasses(Path jarPath, Set<String> classes) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements() && classes.size() < MAX_CLASSES_TO_INSPECT) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")
                    || name.contains("$") || name.equals("module-info.class")) continue;
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                if (isSaveClass(className)) classes.add(className);
            }
        } catch (IOException error) {
            System.err.println("[Mod Manager] Could not inspect client JAR " + jarPath + ": " + error);
        }
    }

    private static void collectDirectoryClasses(Path root, Set<String> classes) {
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 12,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (classes.size() >= MAX_CLASSES_TO_INSPECT) return FileVisitResult.TERMINATE;
                        if (!attrs.isRegularFile() || !file.toString().endsWith(".class")
                            || file.getFileName().toString().contains("$")) return FileVisitResult.CONTINUE;
                        String relative = root.relativize(file).toString().replace(File.separatorChar, '.');
                        if (!relative.endsWith(".class")) return FileVisitResult.CONTINUE;
                        String className = relative.substring(0, relative.length() - 6);
                        if (isSaveClass(className)) classes.add(className);
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException error) {
            System.err.println("[Mod Manager] Could not inspect client class directory " + root + ": " + error);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }
}
