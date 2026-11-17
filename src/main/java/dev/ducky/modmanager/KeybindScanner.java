package dev.ducky.modmanager;

import net.minecraft.client.option.KeyBinding;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.lang.reflect.*;
import java.util.*;

public final class KeybindScanner {
    private static final Map<String, KeyBinding> FOUND = new LinkedHashMap<>();
    private KeybindScanner() {}

    public static synchronized void scan() {
        FOUND.clear();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Field field : KeyBinding.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (!Map.class.isAssignableFrom(type) && !Collection.class.isAssignableFrom(type) && !type.isArray()) continue;
            try {
                field.setAccessible(true);
                collectContainer(field.get(null), visited);
            } catch (ReflectiveOperationException | RuntimeException error) {
                System.err.println("[Mod Manager] Could not inspect KeyBinding registry field "
                    + field.getName() + ": " + error.getMessage());
            }
        }
        for (KeyBinding binding : FOUND.values()) KeybindStore.remember(binding);
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

    public static List<ModContainer> getInstalledMods() {
        List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        mods.sort(Comparator.comparing(m -> m.getMetadata().getName(), String.CASE_INSENSITIVE_ORDER));
        return mods;
    }

    public static String findOwnerModId(String keyId) {
        if (keyId == null || !keyId.startsWith("key.")) return null;
        String remainder = keyId.substring(4);
        String match = null;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (remainder.startsWith(id + ".") && (match == null || id.length() > match.length())) match = id;
        }
        return match;
    }
}
