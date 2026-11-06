# Changelog

All notable changes to this branch are documented in this file. Versions are listed newest first.

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
