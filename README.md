# Mod Manager v7

A client-side Fabric mod for Minecraft **1.21.11**.

## Controls

- Open the manager with **Right Control** by default.
- Change the manager key in **Options → Controls → Key Binds**.
- Scroll through the installed-mod list.
- Select a registered keybind row and press a key to rebind it.
- Press **Escape** while assigning to cancel.
- Use **Restore default** to reset the selected registered keybind.
- Use **Force Save Selected** to back up config files whose paths or names match the selected mod ID.
- Use **Force Save All** to write Minecraft options and create a best-effort snapshot of settings/config files.
- Use **Rescan** after changing the installed mod set or when mods register keybinds late.

## Features

- Lists every mod exposed by Fabric Loader, including mods with no registered keybind.
- Shows each mod's display name, ID, and version.
- Shows registered Minecraft keybinds associated with a mod ID, or "No registered keybind".
- Allows rebinding normal Minecraft-registered keybinds and stores changes in `config/mod-manager.properties`.
- Attempts to reapply saved key assignments after launch.
- Reports backup success/failure instead of silently claiming a save worked.
- Uses a scrollable list and avoids the game's blur pass in its screen renderer.

## Force-save behavior

- **Force Save Selected** is best-effort. It backs up config files whose relative path or filename contains the selected mod ID to `config/mod-manager-backups/mods/<modid>/latest`. If the mod uses a generic filename or stores settings elsewhere, the manager may not identify those files.
- **Force Save All** asks Minecraft to write its options, then snapshots small settings/config files under the game directory to `config/mod-manager-backups/latest`. It excludes worlds, mods, assets, resource packs, logs, and other large game data.
- A backup is a copy of files currently on disk. It cannot force a private client to serialize settings that exist only in memory, use encryption, or use an unknown storage location.

## Compatibility limits

This manager can rebind keybindings registered with Minecraft's standard `KeyBinding` registry. It lists all Fabric Loader mods, but it cannot universally discover or change a private hack-client menu key if that client uses its own unregistered key system. That requires a client-specific adapter or inspection of the client's JAR/config format.

The automated build must pass before the JAR is treated as ready. A successful build does not replace testing in the user's actual Minecraft instance and client.
