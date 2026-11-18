package dev.ducky.modmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Resolves CorzClient's selected local profile and asks CorzClient's own
 * configuration API to load it once when a world is joined.
 *
 * This deliberately does not copy a profile over CorzClient's live settings
 * file: the client may already have deserialized those settings into memory.
 */
public final class CorzClientAutoLoader {
    private static final Path CONFIG_ROOT = FabricLoader.getInstance().getConfigDir()
        .resolve("corzclient").toAbsolutePath().normalize();
    private static final Path CONFIGS_DIR = CONFIG_ROOT.resolve("configs");
    private static final Path ACTIVE_FILE = CONFIG_ROOT.resolve("active.txt");
    private static final Path SOURCES_FILE = CONFIG_ROOT.resolve("sources.json");

    private static final int MAX_PROFILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CLASSES = 2500;

    private static final Set<String> ZERO_ARGUMENT_LOADERS = Set.of(
        "loadactiveconfig", "loadactiveprofile", "reloadactiveconfig",
        "reloadactiveprofile", "loadcurrentconfig", "loadcurrentprofile"
    );

    private CorzClientAutoLoader() {}

    private record Profile(String name, Path file) {}
    private record LoaderCandidate(Method method, Object owner, Object[] args, int score, String key) {}

    /** Called once for each newly joined client world. */
    public static synchronized void loadSelectedProfile() {
        try {
            if (!Files.isDirectory(CONFIG_ROOT, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(CONFIG_ROOT)) {
                log("Corz config folder was not found; nothing to load.");
                return;
            }

            Profile profile = resolveSelectedProfile();
            if (profile == null) {
                log("No valid LOCAL Corz profile was found. No live config files were changed.");
                return;
            }

            ModContainer corz = findCorzContainer();
            if (corz == null) {
                log("Profile " + profile.name() + " was found, but no CorzClient Fabric mod container was detected.");
                return;
            }

            if (invokeCorzLoader(corz, profile)) {
                log("AUTO_LOAD APPLIED profile=" + profile.name() + " file=" + CONFIG_ROOT.relativize(profile.file()));
            } else {
                log("Profile " + profile.name() + " was resolved, but no compatible CorzClient load API was found. "
                    + "The profile file was left untouched and was not falsely reported as loaded.");
            }
        } catch (Throwable error) {
            log("Automatic Corz config load failed: " + rootMessage(error));
            error.printStackTrace(System.err);
        }
    }

    private static Profile resolveSelectedProfile() throws IOException {
        JsonObject sources = readSources();
        if (sources == null) {
            log("sources.json is missing or invalid; cannot safely identify LOCAL profiles.");
            return null;
        }

        String activeName = readActiveName();
        if (activeName != null && isRegisteredLocal(sources, activeName)) {
            Path activeFile = profilePath(activeName);
            if (isValidProfile(activeFile)) {
                log("Selected LOCAL profile: " + activeName);
                return new Profile(activeName, activeFile);
            }
            log("Active profile is missing or invalid: " + activeName + ". Searching for a LOCAL fallback.");
        }

        List<Profile> available = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : sources.entrySet()) {
            String name = safeProfileName(entry.getKey());
            if (name == null || !isLocalSource(entry.getValue())) continue;
            Path file = profilePath(name);
            if (isValidProfile(file)) available.add(new Profile(name, file));
        }

        available.sort((left, right) -> {
            try {
                int modified = Files.getLastModifiedTime(right.file()).compareTo(
                    Files.getLastModifiedTime(left.file()));
                if (modified != 0) return modified;
            } catch (IOException ignored) {
                // Fall back to a stable name order if timestamps cannot be read.
            }
            return left.name().compareToIgnoreCase(right.name());
        });

        if (available.isEmpty()) return null;

        Profile fallback = available.get(0);
        writeActiveName(fallback.name());
        log("Active profile fallback selected: " + fallback.name());
        return fallback;
    }

    private static JsonObject readSources() {
        try {
            if (!Files.isRegularFile(SOURCES_FILE, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(SOURCES_FILE)
                || Files.size(SOURCES_FILE) > 1024 * 1024) return null;
            JsonElement parsed = JsonParser.parseString(Files.readString(SOURCES_FILE, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable error) {
            log("Could not read sources.json: " + rootMessage(error));
            return null;
        }
    }

    private static String readActiveName() {
        try {
            if (!Files.isRegularFile(ACTIVE_FILE, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(ACTIVE_FILE)
                || Files.size(ACTIVE_FILE) > 1024) return null;
            String value = Files.readString(ACTIVE_FILE, StandardCharsets.UTF_8).trim();
            if (value.endsWith(".corz.json")) {
                value = value.substring(0, value.length() - ".corz.json".length());
            }
            return safeProfileName(value);
        } catch (IOException error) {
            log("Could not read active.txt: " + error.getMessage());
            return null;
        }
    }

    private static boolean isRegisteredLocal(JsonObject sources, String name) {
        return sources.has(name) && isLocalSource(sources.get(name));
    }

    private static boolean isLocalSource(JsonElement value) {
        if (value == null || !value.isJsonObject()) return false;
        JsonElement source = value.getAsJsonObject().get("source");
        return source != null && source.isJsonPrimitive()
            && "LOCAL".equalsIgnoreCase(source.getAsString().trim());
    }

    private static String safeProfileName(String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.isEmpty() || name.length() > 80 || name.equals(".") || name.equals("..")
            || name.contains("..") || name.contains("/") || name.contains("\\")
            || name.contains(":") || name.endsWith(".corz.json")) return null;
        return name;
    }

    private static Path profilePath(String name) {
        return CONFIGS_DIR.resolve(name + ".corz.json").normalize();
    }

    private static boolean isValidProfile(Path file) {
        try {
            if (!file.startsWith(CONFIGS_DIR) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
            long size = Files.size(file);
            if (size <= 0 || size > MAX_PROFILE_BYTES) return false;
            JsonElement rootElement = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8));
            if (!rootElement.isJsonObject()) return false;
            JsonObject root = rootElement.getAsJsonObject();
            if (!root.has("content") || !root.get("content").isJsonObject()) return false;
            JsonObject content = root.getAsJsonObject("content");
            if (!content.has("modules") || !content.get("modules").isJsonObject()) return false;
            return !content.getAsJsonObject("modules").entrySet().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeActiveName(String name) {
        Path temporary = CONFIG_ROOT.resolve("active.txt.tmp");
        try {
            Files.createDirectories(CONFIG_ROOT);
            Files.writeString(temporary, name + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, ACTIVE_FILE, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, ACTIVE_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            log("Could not update active.txt to fallback profile " + name + ": " + error.getMessage());
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    private static ModContainer findCorzContainer() {
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = safe(mod.getMetadata().getId()).toLowerCase(Locale.ROOT);
            String name = safe(mod.getMetadata().getName()).toLowerCase(Locale.ROOT);
            if (id.contains("corz") || name.contains("corz")) return mod;
            try {
                for (Path path : mod.getOrigin().getPaths()) {
                    Path fileName = path.getFileName();
                    if (fileName != null && fileName.toString().toLowerCase(Locale.ROOT).contains("corz")) {
                        return mod;
                    }
                }
            } catch (Throwable ignored) {
                // Synthetic and nested mod origins need not expose filesystem paths.
            }
        }
        return null;
    }

    private static boolean invokeCorzLoader(ModContainer mod, Profile profile) {
        Set<String> classes = discoverConfigClasses(mod);
        if (classes.isEmpty()) {
            log("No Corz config/profile classes were discovered in its Fabric roots.");
            return false;
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = CorzClientAutoLoader.class.getClassLoader();

        List<LoaderCandidate> candidates = new ArrayList<>();
        for (String className : classes) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                List<Object> owners = findOwners(type);
                int classScore = classScore(type.getName());

                for (Method method : allMethods(type)) {
                    LoaderCandidate candidate = makeCandidate(method, owners, classScore, profile);
                    if (candidate != null) candidates.add(candidate);
                }
            } catch (Throwable error) {
                log("Could not inspect Corz config class " + className + ": " + rootMessage(error));
            }
        }

        candidates.sort(Comparator.comparingInt(LoaderCandidate::score).reversed()
            .thenComparing(LoaderCandidate::key));

        if (candidates.isEmpty()) {
            log("No supported Corz loadConfig/loadProfile method signature was found.");
            return false;
        }

        LoaderCandidate selected = candidates.get(0);
        try {
            selected.method().setAccessible(true);
            Object result = selected.method().invoke(selected.owner(), selected.args());
            if (result instanceof Boolean success && !success) {
                log("Corz load method returned false: " + selected.key());
                return false;
            }
            log("Corz load method invoked: " + selected.key());
            return true;
        } catch (Throwable error) {
            log("Corz load method failed: " + selected.key() + " (" + rootMessage(error) + ")");
            return false;
        }
    }

    private static LoaderCandidate makeCandidate(
        Method method, List<Object> owners, int classScore, Profile profile
    ) {
        if (method.isBridge() || method.isSynthetic() || method.getReturnType() == Void.TYPE
            && method.getParameterCount() > 1) return null;

        String name = normalize(method.getName());
        if (name.isEmpty()) return null;

        boolean activeNoArg = method.getParameterCount() == 0 && ZERO_ARGUMENT_LOADERS.contains(name);
        Object[] args;
        int score;

        if (activeNoArg) {
            args = new Object[0];
            score = 90;
        } else {
            if (!(name.startsWith("load") || name.startsWith("reload")
                || name.startsWith("import") || name.startsWith("apply"))) return null;
            if (!(name.contains("config") || name.contains("profile") || name.contains("preset"))) return null;
            if (method.getParameterCount() != 1) return null;

            Class<?> parameter = method.getParameterTypes()[0];
            Object argument;
            if (parameter == String.class) {
                argument = (name.contains("file") || name.contains("path") || name.startsWith("import"))
                    ? profile.file().toAbsolutePath().toString() : profile.name();
            } else if (parameter == Path.class) {
                argument = profile.file().toAbsolutePath();
            } else if (parameter == File.class) {
                argument = profile.file().toFile();
            } else {
                return null;
            }

            args = new Object[] { argument };
            score = 50;
            if (name.equals("loadconfig") || name.equals("loadprofile")) score += 25;
            if (name.contains("local")) score += 5;
            if (name.contains("file") || name.contains("path")) score += 3;
        }

        score += classScore;
        if (Modifier.isStatic(method.getModifiers())) {
            return new LoaderCandidate(method, null, args, score + 2,
                method.getDeclaringClass().getName() + "#" + method.getName());
        }

        Object owner = null;
        for (Object candidate : owners) {
            if (candidate != null && method.getDeclaringClass().isInstance(candidate)) {
                owner = candidate;
                break;
            }
        }
        if (owner == null) return null;
        return new LoaderCandidate(method, owner, args, score,
            method.getDeclaringClass().getName() + "#" + method.getName());
    }

    private static int classScore(String className) {
        String name = normalize(className);
        int score = 0;
        if (name.contains("configmanager")) score += 15;
        else if (name.contains("config")) score += 10;
        if (name.contains("profile")) score += 6;
        if (name.contains("preset")) score += 4;
        return score;
    }

    private static List<Object> findOwners(Class<?> type) {
        List<Object> owners = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !isManagerField(field)) continue;
            try {
                field.setAccessible(true);
                addOwner(owners, field.get(null));
            } catch (Throwable ignored) {}
        }

        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) continue;
            String name = normalize(method.getName());
            if (!(name.equals("getinstance") || name.equals("instance")
                || name.equals("getconfigmanager") || name.equals("getprofilemanager")
                || name.equals("getmanager") || name.equals("getconfig")
                || name.equals("getstorage"))) continue;
            if (!hasManagerHint(method.getReturnType().getName())) continue;
            try {
                method.setAccessible(true);
                addOwner(owners, method.invoke(null));
            } catch (Throwable ignored) {}
        }

        List<Object> seeds = new ArrayList<>(owners);
        for (Object seed : seeds) {
            for (Field field : seed.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !isManagerField(field)) continue;
                try {
                    field.setAccessible(true);
                    addOwner(owners, field.get(seed));
                } catch (Throwable ignored) {}
            }
        }
        return owners;
    }

    private static void addOwner(List<Object> owners, Object owner) {
        if (owner == null || owner instanceof String || owner instanceof Number
            || owner instanceof Boolean || owner instanceof Path || owner instanceof File) return;
        if (owners.stream().noneMatch(existing -> existing == owner)) owners.add(owner);
    }

    private static boolean isManagerField(Field field) {
        return hasManagerHint(field.getName()) || hasManagerHint(field.getType().getName());
    }

    private static boolean hasManagerHint(String value) {
        String name = normalize(value);
        return name.contains("config") || name.contains("profile")
            || name.contains("preset") || name.contains("setting")
            || name.contains("manager") || name.contains("storage");
    }

    private static List<Method> allMethods(Class<?> type) {
        LinkedHashMap<String, Method> methods = new LinkedHashMap<>();
        for (Method method : type.getDeclaredMethods()) {
            methods.put(method.toGenericString(), method);
        }
        for (Method method : type.getMethods()) {
            methods.putIfAbsent(method.toGenericString(), method);
        }
        return new ArrayList<>(methods.values());
    }

    private static Set<String> discoverConfigClasses(ModContainer mod) {
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        try {
            for (Path root : mod.getRootPaths()) {
                if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    collectDirectoryClasses(root, classes);
                } else if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)
                    && root.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    collectJarClasses(root, classes);
                }
                if (classes.size() >= MAX_CLASSES) break;
            }
        } catch (Throwable error) {
            log("Could not enumerate CorzClient classes: " + rootMessage(error));
        }
        return classes;
    }

    private static void collectJarClasses(Path jarPath, Set<String> classes) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements() && classes.size() < MAX_CLASSES) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")
                    || name.contains("$") || name.equals("module-info.class")) continue;
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                if (isConfigClass(className)) classes.add(className);
            }
        } catch (IOException error) {
            log("Could not inspect CorzClient jar: " + error.getMessage());
        }
    }

    private static void collectDirectoryClasses(Path root, Set<String> classes) {
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 12,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (classes.size() >= MAX_CLASSES) return FileVisitResult.TERMINATE;
                        if (!attrs.isRegularFile() || !file.toString().endsWith(".class")
                            || file.getFileName().toString().contains("$")) return FileVisitResult.CONTINUE;
                        String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                        if (!relative.endsWith(".class")) return FileVisitResult.CONTINUE;
                        String className = relative.substring(0, relative.length() - 6).replace('/', '.');
                        if (isConfigClass(className)) classes.add(className);
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException error) {
            log("Could not scan CorzClient classes: " + error.getMessage());
        }
    }

    private static boolean isConfigClass(String className) {
        String name = className.toLowerCase(Locale.ROOT);
        return name.contains("config") || name.contains("profile")
            || name.contains("preset") || name.contains("setting")
            || name.contains("storage");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }

    private static void log(String message) {
        System.err.println("[Mod Manager] Corz Auto-Load: " + message);
    }
}
