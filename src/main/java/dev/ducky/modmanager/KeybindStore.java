package dev.ducky.modmanager;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class KeybindStore {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mod-manager.properties");
    private static final Map<String,Integer> SAVED = new LinkedHashMap<>();
    private static final Map<String,Integer> ORIGINAL = new LinkedHashMap<>();
    private static boolean loaded;
    private KeybindStore() {}
    public static synchronized void load() {
        if (loaded) return; loaded = true;
        Properties p = new Properties();
        if (Files.exists(FILE)) try (Reader r = Files.newBufferedReader(FILE)) { p.load(r); }
        catch (IOException e) { System.err.println("[Mod Manager] Config read failed: " + e.getMessage()); }
        for (String n : p.stringPropertyNames()) try {
            if (n.startsWith("key.")) SAVED.put(n.substring(4), Integer.parseInt(p.getProperty(n)));
            if (n.startsWith("default.")) ORIGINAL.put(n.substring(8), Integer.parseInt(p.getProperty(n)));
        } catch (NumberFormatException ignored) {}
    }
    public static synchronized void remember(KeyBinding b) {
        String id=b.getTranslationKey();
        ORIGINAL.putIfAbsent(id,b.getDefaultKey().getCode());
        SAVED.putIfAbsent(id,b.getBoundKey().getCode());
    }
    public static synchronized Integer saved(String id) { return SAVED.get(id); }
    public static synchronized void set(KeyBinding b,int code) {
        remember(b); SAVED.put(b.getTranslationKey(),code);
        b.setBoundKey(InputUtil.fromKeyCode(code,0)); KeyBinding.updateKeysByCode(); save();
    }
    public static synchronized void restore(KeyBinding b) {
        Integer v=ORIGINAL.get(b.getTranslationKey()); if(v!=null) set(b,v);
    }
    public static synchronized void reapplySavedBindings() {
        boolean changed=false;
        for(KeyBinding b:KeybindScanner.getBindings()) {
            Integer code=SAVED.get(b.getTranslationKey());
            if(code!=null && b.getBoundKey().getCode()!=code) { b.setBoundKey(InputUtil.fromKeyCode(code,0)); changed=true; }
        }
        if(changed) KeyBinding.updateKeysByCode();
    }
    public static synchronized void save() {
        Properties p=new Properties();
        SAVED.forEach((k,v)->p.setProperty("key."+k,Integer.toString(v)));
        ORIGINAL.forEach((k,v)->p.setProperty("default."+k,Integer.toString(v)));
        try { Files.createDirectories(FILE.getParent()); try(Writer w=Files.newBufferedWriter(FILE)){p.store(w,"Mod Manager keybinds");} }
        catch(IOException e){System.err.println("[Mod Manager] Config save failed: "+e.getMessage());}
    }
}
