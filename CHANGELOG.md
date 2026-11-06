# Changelog

All notable changes to this branch are documented in this file. Versions are listed newest first.

# 1.0.1.4-beta

## New Content

- NeoForge 1.20.1 generator support. It shares the Forge 1.20.1 hook and `net.minecraftforge` API surface.
- Forge 1.16.5 resource pack registration through the legacy client `IPackFinder` and `ResourcePackInfo` API.

## Changes

- The runtime now selects the version-appropriate `FolderRepositorySource` constructor, including the
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
