package dev.ducky.modmanager;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class ModManagerClient implements ClientModInitializer {
    private static KeyBinding openManagerKey;
    private static boolean initialScanComplete;

    @Override
    public void onInitializeClient() {
        KeybindStore.load();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> BackupStore.forceSave(client));

        openManagerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.modmanager.open",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_CONTROL,
            KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!initialScanComplete && client.world != null && client.player != null) {
                try {
                    KeybindScanner.scan();
                    KeybindStore.reapplySavedBindings();
                    initialScanComplete = true;
                } catch (RuntimeException error) {
                    System.err.println("[Mod Manager] Initial scan failed; will retry: " + error.getMessage());
                }
            }
            if (openManagerKey.wasPressed() && client.currentScreen == null) {
                client.setScreen(new KeybindManagerScreen(null));
            }
        });
    }
}
