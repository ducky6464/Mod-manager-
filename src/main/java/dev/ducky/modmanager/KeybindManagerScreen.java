package dev.ducky.modmanager;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public final class KeybindManagerScreen extends Screen {
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scroll, selected = -1;
    private boolean waiting;
    private String saveMessage = "";
    private static final int ROW = 38;
    private record Row(String modId, String modName, String version, KeyBinding binding, boolean firstForMod) {}

    public KeybindManagerScreen(Screen parent) {
        super(Text.literal("Mod Manager v5"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        KeybindScanner.scan();
        rebuildRows();
        int buttonY = height - 76;
        int buttonY2 = height - 50;
        addDrawableChild(ButtonWidget.builder(Text.literal("Restore default"), b -> restore())
            .dimensions(8, buttonY, 104, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Force Save Selected"), b -> forceSaveSelected())
            .dimensions(116, buttonY, Math.max(110, width - 124), 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Force Save All"), b -> forceSaveAll())
            .dimensions(8, buttonY2, 112, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Rescan"), b -> {
            KeybindScanner.scan();
            rebuildRows();
            scroll = 0;
            saveMessage = "Rescan: " + KeybindScanner.getLastScanStatus();
        }).dimensions(124, buttonY2, 82, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(width - 78, buttonY2, 70, 20).build());
    }

    private void rebuildRows() {
        rows.clear();
        List<KeyBinding> bindings = KeybindScanner.getBindings();
        Map<String, List<KeyBinding>> byMod = new HashMap<>();
        List<KeyBinding> unassigned = new ArrayList<>();
        for (KeyBinding binding : bindings) {
            String owner = KeybindScanner.findOwnerModId(binding.getId());
            if (owner == null) unassigned.add(binding);
            else byMod.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(binding);
        }

        for (ModContainer mod : KeybindScanner.getInstalledMods()) {
            String id = mod.getMetadata().getId();
            String name = mod.getMetadata().getName();
            String version = mod.getMetadata().getVersion().getFriendlyString();
            List<KeyBinding> owned = byMod.getOrDefault(id, List.of());
            if (owned.isEmpty()) rows.add(new Row(id, name, version, null, true));
            else {
                boolean first = true;
                for (KeyBinding binding : owned) {
                    rows.add(new Row(id, name, version, binding, first));
                    first = false;
                }
            }
        }
        if (!unassigned.isEmpty()) {
            boolean first = true;
            for (KeyBinding binding : unassigned) {
                rows.add(new Row("minecraft/unassigned", "Minecraft / Unassigned", "", binding, first));
                first = false;
            }
        }
        if (selected >= rows.size()) selected = -1;
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, 0xA0101010);
        c.drawCenteredTextWithShadow(textRenderer, title, width / 2, 5, 0xFFFFFF);
        c.drawTextWithShadow(textRenderer, "Fabric mods found: " + KeybindScanner.getLastModCount()
            + "   |   Registered keybinds: " + KeybindScanner.getLastKeybindCount(), 10, 19, 0xFFFFFF);
        c.drawTextWithShadow(textRenderer, "Scan: " + KeybindScanner.getLastScanStatus(), 10, 31, 0x80FF80);
        String scanError = KeybindScanner.getLastScanError();
        if (!"None".equals(scanError)) {
            c.drawTextWithShadow(textRenderer, "ERROR: " + textRenderer.trimToWidth(scanError, width - 62), 10, 43, 0xFF7070);
        }
        c.drawTextWithShadow(textRenderer, "Select a keybind row, then press a key to rebind it.", 10, 55, 0xAAAAAA);
        if (!saveMessage.isEmpty()) c.drawTextWithShadow(textRenderer,
            textRenderer.trimToWidth(saveMessage, width - 20), 10, 67, 0x80FF80);

        int top = 82;
        int bottom = height - 82;
        int visible = Math.max(1, (bottom - top) / ROW);
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
            Row row = rows.get(i);
            int y = top + (i - scroll) * ROW;
            c.fill(8, y, width - 8, y + ROW - 2, i == selected ? 0xB0506080 : 0x80303030);
            String heading = row.firstForMod()
                ? row.modName() + "  (" + row.modId() + ")  " + row.version()
                : "  " + row.modName() + "  (" + row.modId() + ")";
            c.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(heading, width - 28), 14, y + 3, 0xFFFFFF);
            String detail;
            if (row.binding() == null) detail = "No registered keybind";
            else {
                KeyBinding key = row.binding();
                detail = key.getId() + "  |  Now: " + key.getBoundKeyLocalizedText().getString()
                    + "  |  Default: " + key.getDefaultKey().getLocalizedText().getString();
                if (hasConflict(key)) detail += "  [CONFLICT]";
            }
            c.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(detail, width - 28),
                14, y + 19, row.binding() == null ? 0xAAAAAA : 0xFFFF80);
        }
        if (waiting) {
            c.fill(width / 2 - 115, height / 2 - 18, width / 2 + 115, height / 2 + 18, 0xE0202020);
            c.drawCenteredTextWithShadow(textRenderer, "Press a key (ESC cancels)", width / 2, height / 2 - 4, 0xFFFFFF);
        }
        super.render(c, mx, my, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double x = click.x(), y = click.y();
        int top = 82, bottom = height - 82;
        if (y >= top && y < bottom && x >= 8 && x < width - 8) {
            int rowIndex = (int) ((y - top) / ROW) + scroll;
            if (rowIndex >= 0 && rowIndex < rows.size()) {
                Row row = rows.get(rowIndex);
                selected = rowIndex;
                if (row.binding() != null) {
                    KeybindStore.setSelectedId(row.binding().getId());
                    waiting = true;
                } else {
                    saveMessage = "This mod has no registered Minecraft keybind to change.";
                }
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (waiting) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { waiting = false; return true; }
            if (selected >= 0 && selected < rows.size() && rows.get(selected).binding() != null) {
                KeybindStore.set(rows.get(selected).binding(), key);
                saveMessage = "Keybind changed and saved to Mod Manager config.";
            }
            waiting = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        int top = 62, bottom = height - 82;
        int visible = Math.max(1, (bottom - top) / ROW);
        int max = Math.max(0, rows.size() - visible);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(vertical) * 3));
        return true;
    }

    private boolean hasConflict(KeyBinding target) {
        int code = KeybindStore.boundCode(target);
        if (code < 0) return false;
        for (KeyBinding other : KeybindScanner.getBindings())
            if (other != target && KeybindStore.boundCode(other) == code) return true;
        return false;
    }

    private void restore() {
        if (selected >= 0 && selected < rows.size() && rows.get(selected).binding() != null) {
            KeybindStore.restore(rows.get(selected).binding());
            saveMessage = "Selected keybind restored to its default.";
        } else saveMessage = "Select a registered keybind first.";
    }

    private void forceSaveSelected() {
        if (selected < 0 || selected >= rows.size()) {
            saveMessage = "Select a mod row first.";
            return;
        }
        Row row = rows.get(selected);
        saveMessage = BackupStore.forceSaveMod(client, row.modId());
    }

    private void forceSaveAll() {
        saveMessage = BackupStore.forceSaveAll(client);
    }

    @Override
    public void close() {
        if (waiting) { waiting = false; return; }
        if (client != null) client.setScreen(parent);
    }
}
