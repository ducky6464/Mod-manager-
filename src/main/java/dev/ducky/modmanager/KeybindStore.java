package dev.ducky.modmanager;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public final class KeybindStore {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mod-manager.properties");
    private static final Map<String,Integer> SAVED = new LinkedHashMap<>();
    private static final Map<String,Integer> ORIGINAL = new LinkedHashMap<>();
    private static Field boundKeyField;
    private static boolean loaded;
    private static String selectedId = "";
    private KeybindStore() {}
    public static synchronized void load() {
        if (loaded) return; loaded = true;
        Properties p = new Properties();
        if (Files.exists(FILE)) try (Reader r = Files.newBufferedReader(FILE)) { p.load(r); }
        catch (IOException e) { System.err.println("[Mod Manager] Config read failed: " + e.getMessage()); }
        selectedId = p.getProperty("selected", "");
        for (String n : p.stringPropertyNames()) try {
            if (n.startsWith("key.")) SAVED.put(n.substring(4), Integer.parseInt(p.getProperty(n)));
            if (n.startsWith("default.")) ORIGINAL.put(n.substring(8), Integer.parseInt(p.getProperty(n)));
        } catch (NumberFormatException ignored) {}
        try {
            String fieldName = FabricLoader.getInstance().getMappingResolver().mapFieldName(
                "intermediary", "net/minecraft/class_304", "field_1655",
                "Lnet/minecraft/class_3675$class_306;");
            boundKeyField=KeyBinding.class.getDeclaredField(fieldName);
            boundKeyField.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[Mod Manager] Could not access the current keybind: "+e.getMessage());
        }
    }
    public static synchronized int boundCode(KeyBinding b) {
        try { return ((InputUtil.Key)boundKeyField.get(b)).getCode(); }
        catch (ReflectiveOperationException|NullPointerException e) { return -1; }
    }
    private static void refreshIndex() { KeyBinding.updateKeysByCode(); }
    public static synchronized void remember(KeyBinding b) {
        String id=b.getId();
        ORIGINAL.putIfAbsent(id,b.getDefaultKey().getCode());
        SAVED.putIfAbsent(id,boundCode(b));
    }
    public static synchronized void set(KeyBinding b,int code) {
        remember(b); SAVED.put(b.getId(),code);
        b.setBoundKey(InputUtil.fromKeyCode(new KeyInput(code,0,0))); refreshIndex(); save();
    }
    public static synchronized void restore(KeyBinding b) {
        Integer v=ORIGINAL.get(b.getId()); if(v!=null) set(b,v);
    }
    public static synchronized void reapplySavedBindings() {
        boolean changed=false;
        for(KeyBinding b:KeybindScanner.getBindings()) {
            Integer code=SAVED.get(b.getId());
            if(code!=null && boundCode(b)!=code) { b.setBoundKey(InputUtil.fromKeyCode(new KeyInput(code,0,0))); changed=true; }
        }
        if(changed) refreshIndex();
    }
    public static synchronized String getSelectedId() { return selectedId; }
    public static synchronized void setSelectedId(String id) { selectedId = id == null ? "" : id; save(); }
    public static synchronized void save() {
        Properties p=new Properties();
        p.setProperty("selected", selectedId);
        SAVED.forEach((k,v)->p.setProperty("key."+k,Integer.toString(v)));
        ORIGINAL.forEach((k,v)->p.setProperty("default."+k,Integer.toString(v)));
        try { Files.createDirectories(FILE.getParent()); try(Writer w=Files.newBufferedWriter(FILE)){p.store(w,"Mod Manager keybinds");} }
        catch(IOException e){System.err.println("[Mod Manager] Config save failed: "+e.getMessage());}
    }
}
