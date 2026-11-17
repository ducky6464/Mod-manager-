# Mod Manager

A client-side Fabric mod for Minecraft **1.21.11**.

## Current implementation

- Registry scanning now handles nested collections and isolates reflection failures per field.

## Current prototype

- Opens the manager with **Right Shift**.
- Scans registered Minecraft `KeyBinding` collections to discover keybinds supplied by loaded mods.
- Lets you select a discovered binding and assign a keyboard key.
- Saves assignments in `config/mod-manager.properties`.
- Periodically reapplies saved assignments, which may help when another mod resets a registered keybind.
- Includes restore-default and rescan actions.

## Build

Requires JDK 21.

```sh
gradle build
```

The JAR is written to `build/libs/`. GitHub Actions also builds the project on pushes and pull requests.

## Compatibility limits

This prototype discovers **registered Minecraft keybindings**, not arbitrary private key systems. A client that handles its menu/module keys through a private system without registering a normal Minecraft `KeyBinding` will not automatically appear as an editable binding. The manager cannot safely rewrite every mod's private settings format.

The manager list is compact and scrollable. **Third-party client GUI resizing is not implemented yet**; that needs client-specific GUI hooks/adapters. There is no universal safe hook for every private client interface.

This is a prototype until the automated build succeeds and it has been tested in an actual Minecraft 1.21.11 instance.
