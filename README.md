# Optional Packs

Optional Packs is a build-time MCreator 2026.1 plugin that bundles user supplied resource pack and data pack zip files
with a generated mod. The packs are registered as optional, disabled-by-default entries by the target loader.

The plugin has no elements, screens, buttons, or configuration entries in the MCreator user interface. It only reacts
to the workspace build event.

## Supported generators

| Loader | Generator names |
| --- | --- |
| Forge | `forge-1.16.5`, `forge-1.17.1`, `forge-1.18.2`, `forge-1.19.2`, `forge-1.19.4`, `forge-1.20.1` |
| NeoForge | `neoforge-1.20.1`, `neoforge-1.20.4`, `neoforge-1.20.6`, `neoforge-1.21.1`, `neoforge-1.21.4`, `neoforge-1.21.8` |

Fabric support was removed from this 2026.1 branch. The NeoForge 1.20.1 generator uses the same
`net.minecraftforge` API surface as Forge 1.20.1, so it shares the Forge hook.

## How it works

On every MCreator workspace build, the plugin runs in the `WorkspaceBuildStartedEvent` listener, after MCreator has
regenerated the workspace base and before Gradle starts.

1. It creates `src/main/resources/resourcepacks/` and `src/main/resources/datapacks/` when they are missing.
2. It scans only direct `.zip` files in those folders. Folders, non-zip files, and nested files are ignored.
3. It normalizes every detected file name to lowercase ASCII letters, digits, underscores, and dots, collapsing
   repeated underscores and adding `_2`, `_3`, ... for collisions.
4. It copies the untouched archives to `.optional_packs/generated/resources/optional_packs/`.
5. It writes a loader-specific hook and a shared reflection runtime to `.optional_packs/generated/java/`.
6. It appends a managed block to the generated `build.gradle` so the generated sources and resources are compiled and
   included in the mod jar, while the source drop folders are excluded from the jar.

Forge hooks are generated as compile-time source for each API generation. ForgeGradle/NeoGradle reobfuscates the
Minecraft references for production, so the exported jar keeps working after leaving the development environment.
Reflection is used only by NeoForge 1.20.4 and later, where the runtime names are stable.

* Forge 1.17.1 through 1.19.2: `new FolderRepositorySource(File, PackSource.BUILT_IN)`.
* Forge 1.19.4, Forge 1.20.1, and NeoForge 1.20.1: `new FolderRepositorySource(Path, event.getPackType(),
  PackSource.BUILT_IN)`.
* Forge 1.16.5: a direct `IPackFinder` lambda that creates `ResourcePackInfo` entries backed by extracted `FolderPack`
  directories.

The generated runtime resolves nested mod-jar resources through Forge/NeoForge `ModList` and `ModFile.findResource`
first, then through the class code source, and finally through the class loader. This keeps resource lookups working
in production jars, where class-loader resource lookup can be filtered.

The generated pack identifiers use the normalized file names. The source zip files are never renamed, rewritten, or
unpacked in place.

## Installation

1. Enable Java plugins in MCreator if they are disabled.
2. Build this project or use `build/libs/optional_packs.zip`.
3. In MCreator open **Workspace settings -> Plugins -> Manage plugins -> Load plugin from file** and select the
   package, or copy it into the MCreator `plugins` folder.
4. Restart MCreator.
5. Select one of the supported generators for the workspace.

The plugin package must be installed in MCreator. It is not a Minecraft mod.

## Usage

Build the workspace once. The two drop folders are created under `src/main/resources/`:

```text
src/main/resources/resourcepacks/
src/main/resources/datapacks/
```

Place one or more `.zip` archives into the matching folder and build again. Nested folders and files with other
extensions are ignored.

Example archives are provided in [examples/](examples/). Copy their contents into the drop folders:

```text
examples/resourcepacks/Example Resource Pack.zip  -> src/main/resources/resourcepacks/
examples/datapacks/Example Data Pack.zip          -> src/main/resources/datapacks/
```

The normalized names are `example_resource_pack.zip` and `example_data_pack.zip`.

## Build output

The plugin package is written to:

```text
plugin-branch-2026.1/build/libs/optional_packs.zip
```

The generated mod receives the normalized archives in its jar:

```text
optional_packs/resourcepacks/<normalized>.zip
optional_packs/datapacks/<normalized>.zip
```

## Building this plugin

Requirements:

* JDK 25 as the compiler (the project uses a Java 25 toolchain and targets Java 21 bytecode for the MCreator runtime).
* The local Gradle distribution at `../reference_resources/gradle-9.8.0-bin.zip`.
* The vendored `libs/mcreator-api.jar` and `libs/log4j-api-2.25.3.jar` (compile-time only).

Set the Gradle cache to the workspace local directory:

```powershell
$env:GRADLE_USER_HOME = "B:\QA\Documents\App\Mods\DSH\Optional_Packs\caches\gradle"
.\gradlew.bat --no-daemon build
```

The wrapper resolves Gradle from the local file URL in `gradle/wrapper/gradle-wrapper.properties`; no network
download is required. `build.gradle` reads the version from `version.properties`, so the plugin descriptor, Gradle
project version, and package version stay aligned.

## Acceptance checks

1. Open a workspace with a supported generator and run **Build workspace**. The two drop folders exist under
   `src/main/resources/`.
2. Copy the example archives into the drop folders and build again.
3. Inspect the mod jar. It contains `optional_packs/resourcepacks/example_resource_pack.zip` and
   `optional_packs/datapacks/example_data_pack.zip`, and it does not contain the raw source zips.
4. Start the client. The resource pack list contains the normalized pack name and the pack is disabled by default.
5. Export the mod jar, install it in a normal Forge/NeoForge instance, and repeat step 4. This is the required check
   because Forge production remaps Minecraft member names.
6. Create a world. The data pack list contains the normalized data pack name and the pack is disabled by default.
7. Confirm that MCreator's workspace panels and element lists are unchanged.
8. Add a file with a non-zip extension and a broken zip. The build still succeeds; the broken archive is ignored or
   skipped by the loader without failing the build.

Do not test by running `gradlew build` directly in the generated workspace. The pack processing is driven by
MCreator's `WorkspaceBuildStartedEvent`; a direct Gradle invocation only sees the files left by the last MCreator
build.

## Versioning

The single version source is `version.properties`; the format is `major.minor.patch.build-type`. Build numbers only
increase. The current version is also written into `plugin.json` through `processResources`.

## License

CC BY-NC 4.0. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
