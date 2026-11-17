package dev.ducky.modmanager;

import net.minecraft.client.option.KeyBinding;
import java.lang.reflect.*;
import java.util.*;

public final class KeybindScanner {
    private static final Map<String,KeyBinding> FOUND=new LinkedHashMap<>();
    private KeybindScanner(){}
    public static synchronized void scan() {
        try {
            for(Field f:KeyBinding.class.getDeclaredFields()) {
                if(!Modifier.isStatic(f.getModifiers())) continue;
                if(!Map.class.isAssignableFrom(f.getType())&&!Collection.class.isAssignableFrom(f.getType())&&!f.getType().isArray()) continue;
                f.setAccessible(true); Object v=f.get(null);
                if(v instanceof Map<?,?> m){ for(Object x:m.values()) collect(x); for(Object x:m.keySet()) collect(x); }
                else if(v instanceof Collection<?> c) for(Object x:c) collect(x);
                else if(v instanceof Object[] a) for(Object x:a) collect(x);
            }
        } catch(ReflectiveOperationException|RuntimeException e){System.err.println("[Mod Manager] Scan warning: "+e.getMessage());}
        for(KeyBinding b:FOUND.values()) KeybindStore.remember(b);
    }
    private static void collect(Object o){if(o instanceof KeyBinding b) FOUND.put(b.getTranslationKey(),b);}
    public static synchronized void scanIfNeeded(){ scan(); }
    public static synchronized List<KeyBinding> getBindings(){
        List<KeyBinding> out=new ArrayList<>(FOUND.values());
        out.sort(Comparator.comparing(KeyBinding::getTranslationKey,String.CASE_INSENSITIVE_ORDER)); return out;
    }
}
