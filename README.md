# Mod Manager

A lightweight client-side Fabric mod for Minecraft **1.21.11**.

## Controls

- Open the manager with **Right Shift**.
- Click a keybind row, then press the key you want to assign.
- Press **Escape** while assigning to cancel.
- Use **Restore default** to restore the selected binding.
- Use **Rescan** after another mod registers keybinds late.
- Scroll through the list when the screen is short.

## What it does

- Finds keybinds registered in Minecraft's `KeyBinding` registry by loaded mods.
- Displays the owning mod name when it can be identified, the keybind ID, current key, and default key.
- Saves key assignments and the selected row in `config/mod-manager.properties`.
- Reapplies saved assignments periodically in case another mod resets a registered keybind.
- Flags key conflicts in the list.
- Keeps the manager list scrollable so it remains usable at small GUI resolutions.

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Install Fabric API for Minecraft 1.21.11.
3. Put the Mod Manager JAR in your Minecraft `mods` folder.
4. Launch the Fabric profile and press **Right Shift** in-game.

## Build

Requires JDK 21.

```sh
gradle build
```

The JAR is written to `build/libs/`. The GitHub Actions workflow also builds the project and uploads the JAR as an artifact.

## Important compatibility limits

This mod manages **normal Minecraft-registered keybindings**. It cannot automatically change a client's private menu/module key system when that client does not register it as a Minecraft `KeyBinding`. There is no safe universal way to rewrite every third-party client's private configuration format.

The manager's own list is compact and scrollable. It does **not** resize the internal GUI of every third-party client. That requires a separate adapter or supported scaling hook for each client, and must be tested against those clients individually.

The automated build has passed. Runtime behavior still needs testing in a real Minecraft 1.21.11 instance with the user's specific mod/client collection.
