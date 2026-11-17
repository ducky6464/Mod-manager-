package dev.ducky.modmanager;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class ModManagerClient implements ClientModInitializer {
    private static KeyBinding openManagerKey;
    @Override public void onInitializeClient() {
        KeybindStore.load();
        KeybindScanner.scan();
        openManagerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.modmanager.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            KeybindScanner.scanIfNeeded();
            KeybindStore.reapplySavedBindings();
            if (openManagerKey.wasPressed() && client.currentScreen == null) client.setScreen(new KeybindManagerScreen(null));
        });
    }
}
