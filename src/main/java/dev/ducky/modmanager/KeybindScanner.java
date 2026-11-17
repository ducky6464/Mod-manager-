package dev.ducky.modmanager;

import net.minecraft.client.option.KeyBinding;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.lang.reflect.*;
import java.util.*;

public final class KeybindScanner {
    private static final Map<String, KeyBinding> FOUND = new LinkedHashMap<>();
    private static volatile String lastScanStatus = "Not scanned yet";
    private static volatile String lastScanError = "None";
    private static volatile int lastModCount = -1;
    private static volatile int lastKeybindCount = -1;

    private KeybindScanner() {}

    public static synchronized void scan() {
        FOUND.clear();
        lastScanError = "None";
        try {
            List<ModContainer> mods = getInstalledMods();
            lastModCount = mods.size();
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            int inspectedFields = 0;

            for (Field field : KeyBinding.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (!Map.class.isAssignableFrom(type) && !Collection.class.isAssignableFrom(type) && !type.isArray()) continue;
                inspectedFields++;
                try {
                    field.setAccessible(true);
                    collectContainer(field.get(null), visited);
                } catch (Throwable error) {
                    reportError("KeyBinding registry field " + field.getName(), error);
                }
            }

            for (KeyBinding binding : FOUND.values()) {
                try {
                    KeybindStore.remember(binding);
                } catch (Throwable error) {
                    reportError("Remember keybind " + binding.getId(), error);
                }
            }

            lastKeybindCount = FOUND.size();
            lastScanStatus = (lastScanError.equals("None") ? "OK" : "PARTIAL")
                + ": mods=" + lastModCount + ", keybinds=" + lastKeybindCount
                + ", registryFields=" + inspectedFields;
            System.err.println("[Mod Manager] V7 SCAN " + lastScanStatus);
            if (!lastScanError.equals("None")) {
                System.err.println("[Mod Manager] V7 SCAN DETAILS: " + lastScanError);
            }
        } catch (Throwable error) {
            lastScanStatus = "FAILED";
            lastScanError = describe(error);
            lastKeybindCount = FOUND.size();
            System.err.println("[Mod Manager] V7 SCAN FAILED: " + lastScanError);
            error.printStackTrace(System.err);
        }
    }

    private static void reportError(String where, Throwable error) {
        String detail = where + ": " + describe(error);
        lastScanError = lastScanError.equals("None") ? detail : lastScanError + " | " + detail;
        System.err.println("[Mod Manager] " + detail);
        error.printStackTrace(System.err);
    }

    private static String describe(Throwable error) {
        return error.getClass().getName() + ": " + String.valueOf(error.getMessage());
    }

    private static void collectContainer(Object value, Set<Object> visited) {
        if (value == null) return;
        if (value instanceof KeyBinding binding) {
            FOUND.put(binding.getId(), binding);
            return;
        }
        if (!visited.add(value)) return;
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) collectContainer(item, visited);
            for (Object item : map.keySet()) collectContainer(item, visited);
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) collectContainer(item, visited);
        } else if (value instanceof Object[] array) {
            for (Object item : array) collectContainer(item, visited);
        }
    }

    public static synchronized List<KeyBinding> getBindings() {
        List<KeyBinding> result = new ArrayList<>(FOUND.values());
        result.sort(Comparator.comparing(KeyBinding::getId, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static String safeModId(ModContainer mod) {
        try {
            String id = mod.getMetadata().getId();
            return id == null || id.isBlank() ? "unknown-mod" : id;
        } catch (Throwable error) {
            return "unknown-mod";
        }
    }

    private static String safeModName(ModContainer mod) {
        try {
            String name = mod.getMetadata().getName();
            return name == null || name.isBlank() ? safeModId(mod) : name;
        } catch (Throwable error) {
            return safeModId(mod);
        }
    }

    public static List<ModContainer> getInstalledMods() {
        FabricLoader loader;
        try {
            loader = FabricLoader.getInstance();
        } catch (Throwable error) {
            lastScanError = "Fabric Loader instance: " + describe(error);
            lastScanStatus = "FAILED";
            System.err.println("[Mod Manager] " + lastScanError);
            error.printStackTrace(System.err);
            return List.of();
        }

        List<ModContainer> mods = new ArrayList<>();
        try {
            mods.addAll(loader.getAllMods());
        } catch (Throwable error) {
            lastScanError = "FabricLoader.getAllMods failed: " + describe(error);
            lastScanStatus = "PARTIAL";
            System.err.println("[Mod Manager] " + lastScanError);
            error.printStackTrace(System.err);
        }

        // The manager must remain visible even if a custom loader returns an incomplete collection.
        if (mods.stream().noneMatch(mod -> "modmanager".equals(safeModId(mod)))) {
            try {
                loader.getModContainer("modmanager").ifPresent(mods::add);
            } catch (Throwable error) {
                reportError("Fallback lookup for modmanager", error);
            }
        }

        mods.sort(Comparator.comparing(KeybindScanner::safeModName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(KeybindScanner::safeModId, String.CASE_INSENSITIVE_ORDER));
        lastModCount = mods.size();
        if (mods.isEmpty()) {
            lastScanStatus = "EMPTY: Fabric Loader returned no mod containers";
            lastScanError = "FabricLoader.getAllMods() and the modmanager fallback returned no containers";
            System.err.println("[Mod Manager] V7 EMPTY MOD LIST");
        }
        return mods;
    }

    public static String getLastScanStatus() { return lastScanStatus; }
    public static String getLastScanError() { return lastScanError; }
    public static int getLastModCount() { return lastModCount; }
    public static int getLastKeybindCount() { return lastKeybindCount; }

    public static String findOwnerModId(String keyId) {
        if (keyId == null || !keyId.startsWith("key.")) return null;
        String remainder = keyId.substring(4);
        String match = null;
        for (ModContainer mod : getInstalledMods()) {
            String id = safeModId(mod);
            if (remainder.startsWith(id + ".") && (match == null || id.length() > match.length())) {
                match = id;
            }
        }
        return match;
    }
}
