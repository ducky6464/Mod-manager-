package dev.ducky.modmanager;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public final class KeybindManagerScreen extends Screen {
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int selected = -1;
    private int page = 0;
    private int rowsPerPage = 1;
    private boolean waiting;
    private int customCaptureStage = 0;
    private int capturedOriginalKey = -1;
    private String saveMessage = "Select a mod row. Rows are real Minecraft widgets.";
    private ButtonWidget infoWidget;
    private ButtonWidget messageWidget;

    private record Row(String modId, String modName, String version, KeyBinding binding) {}
    private record ModInfo(String id, String name, String version) {}

    public KeybindManagerScreen(Screen parent) {
        super(Text.literal("Mod Manager v9"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        KeybindScanner.scan();
        rebuildRows();

        int fullWidth = Math.max(120, width - 16);
        infoWidget = addDrawableChild(ButtonWidget.builder(
            Text.literal("MOD MANAGER v9 | Fabric mods: " + KeybindScanner.getLastModCount()
                + " | keybinds: " + KeybindScanner.getLastKeybindCount()),
            button -> {}
        ).dimensions(8, 8, fullWidth, 22).build());
        infoWidget.active = false;

        messageWidget = addDrawableChild(ButtonWidget.builder(
            Text.literal(trim(saveMessage, fullWidth - 16)),
            button -> {}
        ).dimensions(8, 34, fullWidth, 20).build());
        messageWidget.active = false;

        int listTop = 60;
        int pageControlsY = height - 108;
        rowsPerPage = Math.max(1, Math.min(10, (pageControlsY - listTop - 4) / 31));
        int pageCount = Math.max(1, (rows.size() + rowsPerPage - 1) / rowsPerPage);
        page = Math.max(0, Math.min(page, pageCount - 1));

        if (rows.isEmpty()) {
            String empty = KeybindScanner.getLastModCount() == 0
                ? "NO MODS RETURNED BY FABRIC LOADER"
                : "NO MOD ROWS. SCAN: " + KeybindScanner.getLastScanStatus();
            ButtonWidget emptyRow = addDrawableChild(ButtonWidget.builder(
                Text.literal(trim(empty, fullWidth - 20)), button -> {}
            ).dimensions(8, listTop, fullWidth, 28).build());
            emptyRow.active = false;
        } else {
            int start = page * rowsPerPage;
            int end = Math.min(rows.size(), start + rowsPerPage);
            for (int index = start; index < end; index++) {
                final int rowIndex = index;
                Row row = rows.get(index);
                String label = row.modName() + " [" + row.modId() + "] " + row.version();
                CustomClientKeybinds.Mapping custom = CustomClientKeybinds.get(row.modId());
                if (row.binding() == null) {
                    label += " | No Minecraft keybind";
                } else {
                    label += " | " + row.binding().getId() + " = "
                        + row.binding().getBoundKeyLocalizedText().getString();
                }
                if (custom != null) {
                    label += " | Client GUI remap: " + custom.replacementKey();
                }
                if (selected == index) label = "> " + label;
                ButtonWidget rowButton = addDrawableChild(ButtonWidget.builder(
                    Text.literal(trim(label, fullWidth - 22)),
                    button -> selectRow(rowIndex)
                ).dimensions(8, listTop + (index - start) * 31, fullWidth, 28).build());
                rowButton.active = true;
            }
        }

        ButtonWidget previous = addDrawableChild(ButtonWidget.builder(Text.literal("Previous"), button -> {
            if (page > 0) {
                page--;
                clearAndInit();
            }
        }).dimensions(8, pageControlsY, 88, 20).build());
        previous.active = page > 0;

        ButtonWidget pageLabel = addDrawableChild(ButtonWidget.builder(
            Text.literal("Page " + (page + 1) + " / " + pageCount + " | Rows: " + rows.size()),
            button -> {}
        ).dimensions(102, pageControlsY, Math.max(90, width - 212), 20).build());
        pageLabel.active = false;

        ButtonWidget next = addDrawableChild(ButtonWidget.builder(Text.literal("Next"), button -> {
            if (page + 1 < pageCount) {
                page++;
                clearAndInit();
            }
        }).dimensions(width - 96, pageControlsY, 88, 20).build());
        next.active = page + 1 < pageCount;

        int buttonY = height - 78;
        int buttonY2 = height - 52;
        addDrawableChild(ButtonWidget.builder(Text.literal("Set Client GUI Key"), button -> beginCustomCapture())
            .dimensions(8, buttonY, 112, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Force Save Selected"), button -> forceSaveSelected())
            .dimensions(124, buttonY, Math.max(110, width - 132), 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Restore Key / Backup"), button -> restore())
            .dimensions(8, buttonY2, 112, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Force Save All"), button -> forceSaveAll())
            .dimensions(214, buttonY2, 112, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Rescan"), button -> {
            selected = -1;
            page = 0;
            saveMessage = "Rescanning installed Fabric mods and registered keybinds...";
            clearAndInit();
        }).dimensions(124, buttonY2, 82, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
            .dimensions(width - 78, buttonY2, 70, 20).build());
    }

    private void rebuildRows() {
        rows.clear();

        // Mod enumeration is the primary list source. It must not depend on keybind reflection.
        List<ModInfo> installed = new ArrayList<>();
        try {
            for (var mod : KeybindScanner.getInstalledMods()) {
                String id;
                try {
                    id = mod.getMetadata().getId();
                    if (id == null || id.isBlank()) id = "unknown-mod";
                } catch (Throwable error) {
                    System.err.println("[Mod Manager] Could not read mod ID: " + error);
                    continue;
                }

                String name;
                try {
                    name = mod.getMetadata().getName();
                    if (name == null || name.isBlank()) name = id;
                } catch (Throwable error) {
                    name = id;
                    System.err.println("[Mod Manager] Could not read display name for " + id + ": " + error);
                }

                String version;
                try {
                    version = mod.getMetadata().getVersion().getFriendlyString();
                } catch (Throwable error) {
                    version = "version unavailable";
                    System.err.println("[Mod Manager] Could not read version for " + id + ": " + error);
                }

                installed.add(new ModInfo(id, name, version));
            }
        } catch (Throwable error) {
            saveMessage = "MOD ENUMERATION FAILED: " + error.getClass().getSimpleName()
                + ": " + String.valueOf(error.getMessage());
            System.err.println("[Mod Manager] " + saveMessage);
            error.printStackTrace(System.err);
        }

        // Keybind discovery is optional. A failure here must never remove installed mod rows.
        Map<String, List<KeyBinding>> byMod = new HashMap<>();
        List<KeyBinding> unassigned = new ArrayList<>();
        List<KeyBinding> bindings = List.of();
        try {
            bindings = KeybindScanner.getBindings();
        } catch (Throwable error) {
            saveMessage = "Mods loaded; keybind scan failed: " + error.getClass().getSimpleName();
            System.err.println("[Mod Manager] " + saveMessage + ": " + error.getMessage());
            error.printStackTrace(System.err);
        }

        Set<String> modIds = new HashSet<>();
        for (ModInfo mod : installed) modIds.add(mod.id());

        for (KeyBinding binding : bindings) {
            try {
                String keyId = binding.getId();
                String owner = findOwner(keyId, modIds);
                if (owner == null) unassigned.add(binding);
                else byMod.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(binding);
            } catch (Throwable error) {
                System.err.println("[Mod Manager] Skipping unreadable keybind: " + error);
                error.printStackTrace(System.err);
            }
        }

        for (ModInfo mod : installed) {
            List<KeyBinding> owned = byMod.getOrDefault(mod.id(), List.of());
            if (owned.isEmpty()) {
                rows.add(new Row(mod.id(), mod.name(), mod.version(), null));
            } else {
                for (KeyBinding binding : owned) {
                    rows.add(new Row(mod.id(), mod.name(), mod.version(), binding));
                }
            }
        }

        for (KeyBinding binding : unassigned) {
            rows.add(new Row("minecraft/unassigned", "Minecraft / Unassigned", "", binding));
        }

        if (installed.isEmpty() && saveMessage.isBlank()) {
            saveMessage = "Fabric Loader returned no installed mods. Check the loader status above.";
        }

        System.err.println("[Mod Manager] V9 UI rows=" + rows.size()
            + " FabricMods=" + KeybindScanner.getLastModCount()
            + " Keybinds=" + KeybindScanner.getLastKeybindCount());
    }

    private String findOwner(String keyId, Set<String> modIds) {
        if (keyId == null || !keyId.startsWith("key.")) return null;
        String remainder = keyId.substring(4);
        String best = null;
        for (String id : modIds) {
            if (remainder.startsWith(id + ".") && (best == null || id.length() > best.length())) {
                best = id;
            }
        }
        return best;
    }

    private String trim(String text, int maxWidth) {
        if (text == null) return "";
        return textRenderer.trimToWidth(text, Math.max(40, maxWidth));
    }

    private void selectRow(int index) {
        if (index < 0 || index >= rows.size()) return;
        selected = index;
        Row row = rows.get(index);
        if (row.binding() == null) {
            waiting = false;
            saveMessage = "Selected " + row.modId() + ". Use Set Client GUI Key to remap its existing shortcut.";
        } else {
            waiting = true;
            saveMessage = "Selected " + row.modId() + ". Press a key now. ESC cancels.";
        }
        updateMessage();
        if (infoWidget != null) infoWidget.setMessage(Text.literal(
            "MOD MANAGER v9 | Fabric mods: " + KeybindScanner.getLastModCount()
                + " | keybinds: " + KeybindScanner.getLastKeybindCount()));
    }

    private void updateMessage() {
        if (messageWidget != null) messageWidget.setMessage(Text.literal(trim(saveMessage, width - 32)));
    }

    private void beginCustomCapture() {
        if (selected < 0 || selected >= rows.size()) {
            saveMessage = "Select a client/mod row first.";
            updateMessage();
            return;
        }
        customCaptureStage = 1;
        waiting = false;
        saveMessage = "Step 1/2: Press the client's CURRENT GUI key.";
        updateMessage();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (customCaptureStage == 1) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                customCaptureStage = 0;
                saveMessage = "Client key remapping cancelled.";
            } else {
                capturedOriginalKey = key;
                customCaptureStage = 2;
                saveMessage = "Step 2/2: Press the NEW key you want to use.";
            }
            updateMessage();
            return true;
        }
        if (customCaptureStage == 2) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                customCaptureStage = 0;
                saveMessage = "Client key remapping cancelled.";
            } else if (selected >= 0 && selected < rows.size()) {
                CustomClientKeybinds.set(rows.get(selected).modId(), capturedOriginalKey, key);
                customCaptureStage = 0;
                saveMessage = "Saved client GUI remap. New key forwards the old shortcut while in-game.";
                clearAndInit();
            }
            updateMessage();
            return true;
        }
        if (waiting) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                waiting = false;
                saveMessage = "Key assignment cancelled.";
                updateMessage();
                return true;
            }
            if (selected >= 0 && selected < rows.size()) {
                KeyBinding binding = rows.get(selected).binding();
                if (binding != null) {
                    KeybindStore.set(binding, key);
                    waiting = false;
                    saveMessage = "Saved " + binding.getId() + " as " + binding.getBoundKeyLocalizedText().getString();
                    clearAndInit();
                    return true;
                }
            }
            waiting = false;
            updateMessage();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    private void restore() {
        if (selected < 0 || selected >= rows.size()) {
            saveMessage = "Select a mod/client row first.";
            updateMessage();
            return;
        }
        Row row = rows.get(selected);
        saveMessage = BackupStore.restoreLatestMod(row.modId());
        updateMessage();
    }

    private void forceSaveSelected() {
        if (selected < 0 || selected >= rows.size()) {
            saveMessage = "Select a mod row first.";
            updateMessage();
            return;
        }
        saveMessage = BackupStore.forceSaveMod(client, rows.get(selected).modId());
        updateMessage();
    }

    private void forceSaveAll() {
        saveMessage = BackupStore.forceSaveAll(client);
        updateMessage();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0101010);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int pageCount = Math.max(1, (rows.size() + rowsPerPage - 1) / rowsPerPage);
        if (verticalAmount < 0 && page + 1 < pageCount) {
            page++;
            clearAndInit();
        } else if (verticalAmount > 0 && page > 0) {
            page--;
            clearAndInit();
        }
        return true;
    }

    @Override
    public void close() {
        if (waiting || customCaptureStage != 0) {
            waiting = false;
            customCaptureStage = 0;
            saveMessage = "Key assignment cancelled.";
            updateMessage();
            return;
        }
        if (client != null) client.setScreen(parent);
    }
}
