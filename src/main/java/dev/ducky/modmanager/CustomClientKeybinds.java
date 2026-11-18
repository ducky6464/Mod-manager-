package dev.ducky.modmanager;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Event-based shortcut remapper for clients that listen to Minecraft keyboard events. */
public final class CustomClientKeybinds {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mod-manager-client-keys.properties");
    private static final Map<String, Mapping> MAPPINGS = new LinkedHashMap<>();
    private static final Map<String, Boolean> DOWN = new HashMap<>();
    private static boolean loaded;

    public record Mapping(String modId, int originalKey, int replacementKey) {}
    private CustomClientKeybinds() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Properties p = new Properties();
        if (Files.exists(FILE)) try (Reader r = Files.newBufferedReader(FILE)) { p.load(r); }
        catch (IOException e) { System.err.println("[Mod Manager] Custom key config read failed: " + e); }
        for (String id : p.stringPropertyNames()) {
            String[] v = p.getProperty(id, "").split(",", -1);
            if (v.length != 2) continue;
            try {
                int original = Integer.parseInt(v[0]), replacement = Integer.parseInt(v[1]);
                if (valid(original) && valid(replacement)) MAPPINGS.put(id, new Mapping(id, original, replacement));
            } catch (NumberFormatException ignored) {}
        }
    }

    public static synchronized void set(String id, int original, int replacement) {
        load();
        if (id == null || id.isBlank() || !valid(original) || !valid(replacement)) return;
        MAPPINGS.put(id, new Mapping(id, original, replacement));
        DOWN.remove(id);
        save();
    }
    public static synchronized Mapping get(String id) { load(); return MAPPINGS.get(id); }
    public static synchronized void remove(String id) { load(); MAPPINGS.remove(id); DOWN.remove(id); save(); }
    public static synchronized List<Mapping> all() { load(); return new ArrayList<>(MAPPINGS.values()); }

    public static synchronized void tick(MinecraftClient client) {
        load();
        if (client == null || client.getWindow() == null || client.currentScreen != null) return;
        long window = client.getWindow().getHandle();
        for (Mapping m : MAPPINGS.values()) {
            boolean pressed = GLFW.glfwGetKey(window, m.replacementKey()) == GLFW.GLFW_PRESS;
            boolean wasDown = DOWN.getOrDefault(m.modId(), false);
            if (pressed && !wasDown) dispatch(client, m.originalKey(), GLFW.GLFW_PRESS);
            else if (!pressed && wasDown) dispatch(client, m.originalKey(), GLFW.GLFW_RELEASE);
            DOWN.put(m.modId(), pressed);
        }
    }

    private static void dispatch(MinecraftClient client, int key, int action) {
        try {
            Field keyboardField = null;
            for (Field f : MinecraftClient.class.getDeclaredFields()) {
                if (f.getType().getName().endsWith(".Keyboard")) { keyboardField = f; break; }
            }
            if (keyboardField == null) throw new NoSuchFieldException("MinecraftClient keyboard field");
            keyboardField.setAccessible(true);
            Object keyboard = keyboardField.get(client);
            Method callback = null;
            for (Method m : keyboard.getClass().getDeclaredMethods()) {
                if (m.getName().equals("onKey") && m.getParameterCount() == 5) { callback = m; break; }
            }
            if (callback == null) throw new NoSuchMethodException("Keyboard.onKey");
            callback.setAccessible(true);
            callback.invoke(keyboard, client.getWindow().getHandle(), key, 0, action, 0);
        } catch (Throwable e) {
            System.err.println("[Mod Manager] Custom key forwarding failed: " + e);
            e.printStackTrace(System.err);
        }
    }
    private static boolean valid(int key) { return key >= 32 && key <= GLFW.GLFW_KEY_LAST; }
    private static synchronized void save() {
        Properties p = new Properties();
        for (Mapping m : MAPPINGS.values()) p.setProperty(m.modId(), m.originalKey() + "," + m.replacementKey());
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE)) { p.store(w, "Mod Manager custom client GUI key remaps"); }
        } catch (IOException e) { System.err.println("[Mod Manager] Custom key config save failed: " + e); }
    }
}
