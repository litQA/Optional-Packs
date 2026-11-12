# Changelog

All notable changes to this branch are documented in this file. Versions are listed newest first.

# 1.2.0.8-beta

## Changes

- When neither source drop folder contains a zip, no optional pack classes, manifests, or version resources are
  generated. The managed `build.gradle` block is still injected so non-zip files in the drop folders are not packaged.
- Stale plugin classes, resources, and jars are still removed before this no-pack decision, so a workspace that previously
  had packs is cleaned correctly.
- Version metadata was advanced to minor version 2, patch 0, with the existing build number retained.

## Known Limitations

- Forge 1.16.5 data packs are still bundled but cannot be added to the world creation data pack list without a
  coremod or mixin. See [MIGRATION-REPORT.md](MIGRATION-REPORT.md).
- Pack contents are not validated, repaired, or rewritten. Invalid structures are left for the loader to skip.
- Cleanup deletes `build/libs`; if the game holds an old jar open on Windows, close the game and retry.

# 1.1.1.8

## Changes

- MCreator 2026.1 formal release. The `1.1.0.7-beta` export-safe preparation, targeted cleanup, and version-marker
  behavior is promoted without functional changes.
- `version.type` is empty for this release; the plugin descriptor and package carry `1.1.1.8` without a beta suffix.

## Known Limitations

- Forge 1.16.5 data packs are still bundled but cannot be added to the world creation data pack list without a
  coremod or mixin. See [MIGRATION-REPORT.md](MIGRATION-REPORT.md).
- Cleanup deletes `build/libs`; if the game holds an old jar open on Windows, close the game and retry.
- Pack contents are not validated, repaired, or rewritten. Invalid structures are left for the loader to skip.

# 1.1.0.7-beta

## New Content

- Mod-base template event preparation, so MCreator's export action regenerates optional pack sources, resources, and
  the managed build script before Gradle runs even though the export action does not fire `WorkspaceBuildStartedEvent`.
- A generated version marker in `optional_packs/version.txt` and `OptionalPacksRuntime.VERSION`.
- Targeted cleanup of plugin-owned build outputs before every preparation.

## Changes

- The workspace `.optional_packs` directory is removed and rebuilt on every preparation.
- Stale `build/classes/**/optionalpacks`, `build/resources/main/optional_packs`, old derived Fabric resource
  directories, `build/libs`, and `build/tmp/jar` are removed before Gradle.
- A short preparation window prevents the template event and the following build event from processing the same
  workspace twice.

## Fixes

Fixed 1 bug(s).

- Exported Forge jars could reuse stale optional pack classes and resources after a workspace generator conversion or
  an export without a preceding explicit build. The export action calls `generateBase()` but not the build-started
  event, so preparation now also runs from the mod-base template event.

## Known Limitations

- Cleanup deletes `build/libs`; if the game is holding an old jar open on Windows, deletion can fail and must be
  retried after closing the game.
- Preparation runs synchronously in MCreator's template event with the standard ten-second listener timeout.
- A direct Gradle task that does not call `generateBase()` is not covered by the template event path.

# 1.0.2.6-beta

## Changes

- Forge pack registration hooks are generated as compile-time source for the 1.17.1-1.19.2, 1.19.4-1.20.1, and
  1.16.5 API generations. ForgeGradle now reobfuscates the Minecraft references together with the mod, so exported
  jars no longer depend on development mapping names.
- Forge 1.16.5 uses extracted `FolderPack` directories and direct `ResourcePackInfo` entries instead of reflective
  Minecraft member access.
- Mod-jar resource reads now use Forge/NeoForge `ModList` and `ModFile.findResource` first, with code-source and class
  loader fallbacks.

## Fixes

Fixed 1 bug(s).

- Exported Forge jars did not show optional resource packs even though the run client did. Forge production remaps
  Minecraft method and field names, so the previous reflective `PackSource.BUILT_IN`, `getPackType`, and
  `FolderRepositorySource` lookups failed outside the development environment. The generated Forge hooks now call the
  loader APIs directly.

## Known Limitations

- Forge 1.16.5 data packs are still bundled but cannot be added to the world creation data pack list without a
  coremod or mixin. See [MIGRATION-REPORT.md](MIGRATION-REPORT.md).
- Build processing is triggered by MCreator's workspace build event, not by a direct `gradlew build` invocation.
- Pack contents are not validated, repaired, or rewritten. Invalid structures are left for the loader to skip.

# 1.0.1.4-beta

## New Content

- NeoForge 1.20.1 generator support. It shares the Forge 1.20.1 hook and `net.minecraftforge` API surface.
- Forge 1.16.5 resource pack registration through the legacy client `IPackFinder` and `ResourcePackInfo` API.

## Changes

- The runtime selected the version-appropriate `FolderRepositorySource` constructor, including the
  `(File, PackSource)` form used by Forge 1.17.1, 1.18.2, and 1.19.2.
- Fabric 1.21.8 support was removed from this 2026.1 branch.
- The generator allowlist now contains six Forge and six NeoForge generators.

## Fixes

Fixed 1 bug(s).

- Resource packs were not inserted into the resource pack list on Forge 1.16.5 through 1.19.2. The old loader API does
  not expose the `Path`/`PackType` constructor used by 1.19.4 and later, and Forge 1.16.5 predates
  `AddPackFindersEvent`. The plugin now uses the correct constructor form for 1.17.1 through 1.19.2 and the legacy
  client pack finder for 1.16.5.

## Known Limitations

- Forge 1.16.5 data packs are still bundled but cannot be added to the world creation data pack list without a
  coremod or mixin. See [MIGRATION-REPORT.md](MIGRATION-REPORT.md).
- Build processing is triggered by MCreator's workspace build event, not by a direct `gradlew build` invocation.
- Pack contents are not validated, repaired, or rewritten. Invalid structures are left for the loader to skip.
- The managed `build.gradle` block is rewritten on every build; manual edits inside it are lost.

# 1.0.0.3-beta

## New Content

- Build-time creation of `src/main/resources/resourcepacks/` and `src/main/resources/datapacks/` for every supported
  MCreator 2026.1 build.
- Direct `.zip` discovery, ASCII name normalization, collision suffixes, and copying into a generated resource tree.
- Forge 1.17.1 through 1.20.1, NeoForge 1.20.4 through 1.21.8, and Fabric 1.21.8 runtime hooks.
- Optional, disabled-by-default registration through `AddPackFindersEvent` and Fabric's built-in resource pack API.
- Fabric directory derivation required by the built-in pack API.
- Example resource pack and data pack archives, acceptance steps, and a detailed migration report.
- A managed `build.gradle` block that excludes source drop folders from the mod jar and includes generated sources
  and resources.

## Changes

- Plugin bytecode targets Java 21 while the build uses a JDK 25 toolchain, matching the bundled MCreator 2026.1 runtime.
- The Gradle wrapper resolves Gradle 9.8.0 from the workspace local reference distribution.
- The plugin version is read from `version.properties` and expanded into `plugin.json` during the build.

## Known Limitations

- Forge 1.16.5 has no `AddPackFindersEvent`; resource packs are copied into the game directory, while automatic data
  pack registration in the world creation list is not available without a coremod. See
  [MIGRATION-REPORT.md](MIGRATION-REPORT.md).
- Build processing is triggered by MCreator's workspace build event, not by a direct `gradlew build` invocation.
- Fabric registration requires a root `pack.mcmeta`; archives without it stay in the jar but are not registered.
