# Mod Manager v9

Client-side Fabric mod for Minecraft 1.21.11.

## Main controls

- Open the manager with Right Control by default.
- Select a mod row, including rows without a Minecraft-registered keybind.
- Set Client GUI Key is the generic keyboard-event remapper. It works only with clients that listen to Minecraft keyboard events; clients with private input handlers still need a client-specific keybind adapter.
- Force Save Selected scans the selected mod's classes and config-related fields, invokes detectable save methods, then snapshots the discovered client config files.
- Force Save All repeats the save/discovery process for installed mods.
- Restore Key / Backup restores only the selected mod's exact manifest-listed files.

## Client configuration persistence

- The manager inspects the selected mod's own JAR/directory for classes associated with config, settings, modules, keybinds, profiles, storage, and client data.
- It looks for zero-argument save/write/store/flush methods on those classes and their discovered singleton/config objects. It does not call arbitrary methods on every class.
- It checks config-related Path, File, and path-like fields to find the client's actual config location.
- It indexes config-like files before and after the save call. Paths that belong to the client, files whose contents identify the client, config roots discovered from client objects, and files changed by the save call are collected. A dedicated client folder is backed up completely, including generic-named files.
- Each backup contains a manifest with the exact relative file paths, hashes, save-method counts, changed-file count, and timestamp. The copied files are compared byte-for-byte with their source files.
- Restore uses only paths in the selected client's manifest. It does not restore options.txt, optionsof.txt, servers.dat, launcher profiles, or Mod Manager's own files, and it does not restore the all-mods snapshot over a selected client.
- The manager reports VERIFIED only when it detects changed client config files and the backup copies verify. If a client exposes no detectable save method or no changed config file is found, the result says NOT VERIFIED instead of claiming the live settings were saved.

## Compatibility limits

There is no universal API for private Minecraft clients. A client may encrypt its config, keep state in memory, write outside the game directory, use obfuscated save methods, or serialize through code that cannot safely be identified by reflection. In those cases, Mod Manager reports the limitation and a client-specific adapter or inspection of that client's JAR is required. A successful Gradle build confirms compilation and artifact packaging, not runtime compatibility with every client.

## Diagnostics

Save/discovery details use the existing [Mod Manager] log prefix and are included by tools/collect-mod-diagnostics.sh in error-extract.txt and latest.log.
