# Optional Packs

Optional Packs is a build-time MCreator 2026.3 plugin that bundles user supplied resource pack and data pack zip files
with a generated mod. The packs are registered as optional, disabled-by-default entries by the target loader.

The plugin has no elements, screens, buttons, or configuration entries in the MCreator user interface. It only reacts
to the workspace build and export actions.

## Supported generators

| Loader | Generator names |
| --- | --- |
| Forge | `forge-1.16.5`, `forge-1.17.1`, `forge-1.18.2`, `forge-1.19.2`, `forge-1.19.4`, `forge-1.20.1` |
| NeoForge | `neoforge-1.20.1`, `neoforge-1.20.4`, `neoforge-1.20.6`, `neoforge-1.21.1`, `neoforge-1.21.4`, `neoforge-1.21.8` |
| Forge | `forge-1.20.4`, `forge-1.20.6`, `forge-1.21.1`, `forge-26.1.2`, `forge-26.2`, `forge-26.3` |
| NeoForge | `neoforge-26.1.2`, `neoforge-26.2`, `neoforge-26.3` |
| Fabric | `fabric-1.20.1`, `fabric-1.21.1`, `fabric-1.21.8`, `fabric-1.21.11`, `fabric-26.1.2`, `fabric-26.2`, `fabric-26.3` |

The first two rows are the generators already supported by the 2026.1 branch. Some of these generator versions are not
shipped with MCreator 2026.3 and are provided by third-party generator plugins. The plugin identifies generators by
their MCreator directory name and does not require a particular generator bundle to be installed.

The NeoForge 1.20.1 generator uses the same `net.minecraftforge` API surface as Forge 1.20.1, so it shares the Forge
hook.

## How it works

The plugin prepares the workspace from the `modbase/mod.java.ftl` template event, which also runs during MCreator's
**Export mod** action, and from the `WorkspaceBuildStartedEvent` listener as a build-action fallback. MCreator's
export action calls `generateBase()` and Gradle directly, but it does not fire the workspace build-started event, so
the template event is the path that prevents stale exports.

Before writing new files, it removes the workspace `.optional_packs` directory and plugin-owned build outputs:
`.optional_packs`, `build/classes/**/optionalpacks`, `build/resources/main/optional_packs`, old derived Fabric
resource directories, `build/libs`, and `build/tmp/jar`. This makes a converted workspace and an exported jar rebuild
from the current source drop folders.

1. It creates `src/main/resources/resourcepacks/` and `src/main/resources/datapacks/` when they are missing.
2. It scans only direct `.zip` files in those folders. Folders, non-zip files, and nested files are ignored.
3. If neither folder contains a direct `.zip`, it removes `.optional_packs`, plugin-owned build outputs, and the
   managed `build.gradle` block, and generates no runtime, hook, manifest, or version marker. The two empty drop
   folders remain available for the next build.
4. It normalizes every detected file name to lowercase ASCII letters, digits, underscores, and dots, collapsing
   repeated underscores and adding `_2`, `_3`, ... for collisions.
5. It copies the untouched archives to `.optional_packs/generated/resources/optional_packs/`.
6. For Fabric it also extracts each archive into
   `.optional_packs/generated/resources/optional_packs/fabric/{resourcepacks,datapacks}/<name>/`, because Fabric's
   built-in pack API reads directories rather than archives.
7. It writes a loader-specific hook and a shared reflection runtime to `.optional_packs/generated/java/`.
8. It writes `optional_packs/version.txt` and a `VERSION` constant into the generated runtime for traceability.
9. It appends a managed block to the generated `build.gradle` so the generated sources and resources are compiled and
   included in the mod jar, while the source drop folders are excluded from the jar.

A short preparation timestamp prevents the template event and the following build event from processing the same
workspace twice.

### Forge and NeoForge

Forge hooks are generated as compile-time source for each API generation. Forge 1.20.2 and later use official runtime
mappings, which lets the shared reflective `FolderRepositorySource` path work again in production. NeoForge 1.20.4 and
later have always had stable runtime names and use the same reflective path.

* Forge 1.17.1 through 1.19.2: `new FolderRepositorySource(File, PackSource.BUILT_IN)`.
* Forge 1.19.4, Forge 1.20.1, and NeoForge 1.20.1: `new FolderRepositorySource(Path, event.getPackType(),
  PackSource.BUILT_IN)`.
* Forge 1.20.4-1.21.1 and NeoForge 1.20.4+: `AddPackFindersEvent` plus a reflective `FolderRepositorySource`
  constructor lookup that supports both the three-argument and the `DirectoryValidator` four-argument forms.
* Forge 26.x: `SubscribeEvent` moved to `net.minecraftforge.eventbus.api.listener`, and `AddPackFindersEvent` is
  registered on the default bus. The hook uses the new import and `@Mod.EventBusSubscriber(modid = ...)` without the
  legacy `bus = MOD` attribute.
* Forge 1.16.5: a direct `IPackFinder` lambda that creates `ResourcePackInfo` entries backed by extracted `FolderPack`
  directories.

### Fabric

Fabric Loader only starts entrypoint classes listed in `fabric.mod.json`. The plugin listens for
`modbase/fabric.mod.json.ftl` and inserts `<package>.optionalpacks.OptionalPacksHook` into the generated `main`
entrypoint array. The hook implements `net.fabricmc.api.ModInitializer`.

The generated hook calls the Fabric API directly, so Loom and Fabric Loader remap the Minecraft references together
with the rest of the mod. The identifier type is `ResourceLocation` for 1.20.1-1.21.8 and `Identifier` for 1.21.11
and later. Each extracted directory is registered with
`ResourceManagerHelper.registerBuiltinResourcePack(id, subPath, container, false)`, which keeps every pack disabled
by default. Pack names are embedded in the generated hook at build time, so no production class-loader resource
lookup is required.

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
plugin-branch-2026.3/build/libs/optional_packs.zip
```

The generated mod receives the normalized archives in its jar:

```text
optional_packs/resourcepacks/<normalized>.zip
optional_packs/datapacks/<normalized>.zip
optional_packs/version.txt
```

Fabric mods additionally receive the extracted directory trees:

```text
optional_packs/fabric/resourcepacks/<normalized>/
optional_packs/fabric/datapacks/<normalized>/
```

The export action calls `generateBase()` and then Gradle. The template event removes `.optional_packs`, plugin-owned
classes/resources, and `build/libs` before Gradle starts, so an export after a generator conversion cannot reuse the
previous jar. Check `optional_packs/version.txt` to confirm the plugin version, generator, and loader target that
produced the jar.

## Building this plugin

Requirements:

* JDK 25 as the compiler and bytecode target, matching the bundled MCreator 2026.3 JBR 25 runtime.
* The local Gradle distribution at `../reference_resources/gradle-9.8.0-bin.zip`.
* The vendored `libs/mcreator-api.jar` (MCreator 2026.3 API classes) and `libs/log4j-api-2.26.1.jar`
  (compile-time only).

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
3. Inspect the mod jar. It contains `optional_packs/resourcepacks/example_resource_pack.zip`,
   `optional_packs/datapacks/example_data_pack.zip`, and `optional_packs/version.txt`, and it does not contain the raw
   source zips.
4. Start the client. The resource pack list contains the normalized pack name and the pack is disabled by default.
5. Use **Export mod** directly, without a preceding **Build workspace**, install the exported jar in a normal
   Forge/NeoForge/Fabric instance, and repeat step 4. This is the required check because the export action does not
   fire the build-started event.
6. Create a world. The data pack list contains the normalized data pack name and the pack is disabled by default.
7. For Fabric, confirm `fabric.mod.json` lists `<package>.optionalpacks.OptionalPacksHook` in `entrypoints.main`.
8. Confirm that MCreator's workspace panels and element lists are unchanged.
9. Add a file with a non-zip extension and a broken zip. The build still succeeds; the broken archive is ignored or
   skipped by the loader without failing the build.

Do not test by running `gradlew build` directly in the generated workspace. The pack processing is driven by MCreator's
template and build events; a direct Gradle invocation only sees the files left by the last MCreator action.

## Versioning

The single version source is `version.properties`; the format is `major.minor.patch.build-type`. Build numbers only
increase. The current version is also written into `plugin.json` through `processResources`. The plugin targets the
MCreator 2026.3 snapshot and stable version identifiers `202600344621` and `2026003`.

## License

CC BY-NC 4.0. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
