package dev.ducky.modmanager;

import net.minecraft.client.option.KeyBinding;
import java.lang.reflect.*;
import java.util.*;

/**
 * Discovers bindings registered with Minecraft's KeyBinding registry.
 * This intentionally does not inspect arbitrary mod-private key systems.
 */
public final class KeybindScanner {
    private static final Map<String, KeyBinding> FOUND = new LinkedHashMap<>();
    private KeybindScanner() {}

    public static synchronized void scan() {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Field field : KeyBinding.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;

            Class<?> type = field.getType();
            if (!Map.class.isAssignableFrom(type)
                    && !Collection.class.isAssignableFrom(type)
                    && !type.isArray()) continue;

            try {
                field.setAccessible(true);
                collectContainer(field.get(null), visited);
            } catch (ReflectiveOperationException | RuntimeException error) {
                System.err.println("[Mod Manager] Could not inspect KeyBinding registry field "
                        + field.getName() + ": " + error.getMessage());
            }
        }

        for (KeyBinding binding : FOUND.values()) {
            KeybindStore.remember(binding);
        }
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
}
