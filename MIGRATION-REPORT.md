# Migration and Verification Report

## MCreator 2026.3 API confirmation

The implementation was checked against the MCreator EAP 2026.3.44621 application classes embedded in
`reference_resources/MCreatorEAP2026344621/mcreator.exe` and against the bundled generator plugins.

| Item | Result |
| --- | --- |
| MCreator full version | `2026.3.44621` |
| Snapshot version identifier | `202600344621` |
| Stable major identifier | `2026003` |
| Runtime Java | JBR 25; plugin bytecode targets Java 25 |
| Plugin base class | `net.mcreator.plugin.JavaPlugin` |
| Template event | `net.mcreator.plugin.events.ModifyTemplateResultEvent` |
| Build event | `net.mcreator.plugin.events.workspace.WorkspaceBuildStartedEvent` |
| Build event position | After `Generator.generateBase()` and before Gradle |
| Export action | Calls `generateBase()`, then Gradle `build`, then copies `build/libs`; it does not fire the build event |
| Workspace folder access | `Workspace#getFolderManager()#getWorkspaceFolder()` |
| Workspace package access | `WorkspaceSettings#getModElementsPackage()` |
| Generator name access | `Generator#getGeneratorName()` |

`JavaPlugin`, `Plugin`, `PluginInfo`, `ModifyTemplateResultEvent`, `WorkspaceBuildStartedEvent`, `Workspace`,
`WorkspaceSettings`, `WorkspaceFolderManager`, and the methods used by this plugin keep the same signatures between
2026.1 and 2026.3. `Generator.generateBase(boolean)` changed from `boolean` to `void` in 2026.3, but the plugin never
calls that method directly.

The 2026.3 application is a snapshot, so `plugin.json` must contain the complete version identifier `202600344621`.
The stable identifier `2026003` is included for later non-snapshot 2026.3 builds.

## Supported target matrix

| Loader | Generators |
| --- | --- |
| Forge | `forge-1.16.5`, `forge-1.17.1`, `forge-1.18.2`, `forge-1.19.2`, `forge-1.19.4`, `forge-1.20.1` |
| NeoForge | `neoforge-1.20.1`, `neoforge-1.20.4`, `neoforge-1.20.6`, `neoforge-1.21.1`, `neoforge-1.21.4`, `neoforge-1.21.8` |
| Forge | `forge-1.20.4`, `forge-1.20.6`, `forge-1.21.1`, `forge-26.1.2`, `forge-26.2`, `forge-26.3` |
| NeoForge | `neoforge-26.1.2`, `neoforge-26.2`, `neoforge-26.3` |
| Fabric | `fabric-1.20.1`, `fabric-1.21.1`, `fabric-1.21.8`, `fabric-1.21.11`, `fabric-26.1.2`, `fabric-26.2`, `fabric-26.3` |

The first two rows are retained from the 2026.1 branch. Some of these generators are not included in the 2026.3 EAP
and may be supplied by third-party generator plugins.

The NeoForge 1.20.1 generator uses the Forge 1.20.1 code shape and the `net.minecraftforge` package. It therefore
uses the Forge Modern hook instead of the `net.neoforged` hook used by NeoForge 1.20.4 and later.

## Implementation summary

Preparation runs synchronously from two hooks so files exist before Gradle starts:

* `ModifyTemplateResultEvent` for `modbase/mod.java.ftl`, which runs inside `generateBase()` and therefore also runs
  for MCreator's **Export mod** action.
* `WorkspaceBuildStartedEvent`, retained as a fallback for build actions that do not render the mod-base template.

Fabric additionally uses `ModifyTemplateResultEvent` for `modbase/fabric.mod.json.ftl` to add the generated hook to
the `main` entrypoint array.

Each preparation:

1. Removes the workspace `.optional_packs` directory and plugin-owned build outputs.
2. Creates the source drop folders.
3. Scans only direct `.zip` files.
4. Normalizes names and copies archives to `.optional_packs/generated/resources/optional_packs/`.
5. For Fabric, extracts each archive into
   `.optional_packs/generated/resources/optional_packs/fabric/{resourcepacks,datapacks}/<name>/`.
6. Writes the loader hook and the shared runtime to `.optional_packs/generated/java/`.
7. Writes `optional_packs/version.txt` and `OptionalPacksRuntime.VERSION`.
8. Adds a managed block to the generated `build.gradle`.

The cleanup removes `.optional_packs`, every `build/classes/java/main/**/optionalpacks` directory,
`build/resources/main/optional_packs`, old derived Fabric resource directories, `build/libs`, and `build/tmp/jar`.
This covers generator conversion and stale export output without deleting `.gradle` caches or the whole build tree.

A short in-memory preparation timestamp prevents the template event and the immediately following build event from
processing the same workspace twice.

## Loader hook design

### Forge 1.17.1 through 1.20.1

Forge hooks are generated as compile-time source for each API generation. ForgeGradle reobfuscates the Minecraft
references for production, so the exported jar keeps working after leaving the development environment.

* Forge 1.17.1, 1.18.2, and 1.19.2 use `new FolderRepositorySource(File, PackSource.BUILT_IN)`.
* Forge 1.19.4, Forge 1.20.1, and NeoForge 1.20.1 use
  `new FolderRepositorySource(Path, event.getPackType(), PackSource.BUILT_IN)`.

### Forge 1.20.4 and later

Forge 1.20.2 and later use official runtime mappings, so the reflective path is production-safe again. The generated
hook uses `@Mod.EventBusSubscriber(modid = ..., bus = Mod.EventBusSubscriber.Bus.MOD)` and forwards the event to the
shared runtime. The runtime selects the `FolderRepositorySource` constructor by parameter type, which supports both
the three-argument form and the four-argument form with `DirectoryValidator`. If the named `PackSource.BUILT_IN`
field is remapped, the runtime falls back to scanning public static `PackSource` fields and prefers a value whose
string form contains `built`.

### NeoForge 1.20.4 through 26.x

NeoForge keeps stable runtime names. The generated hook uses the event bus annotation appropriate to the generator:

* 1.20.4: `@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)`.
* 1.20.6: `@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)`.
* 1.21.1 and later, including 26.x: `@EventBusSubscriber`.

The runtime reflection path handles `FolderRepositorySource`, `PackSource.BUILT_IN`, and `DirectoryValidator` across
the NeoForge versions.

### Fabric 1.20.1 through 26.x

Fabric's built-in pack API reads directories inside the mod jar. The build stage extracts each normalized archive and
the hook registers the extracted directories with
`ResourceManagerHelper.registerBuiltinResourcePack(ResourceLocation, String, ModContainer, boolean)` using
`enabledByDefault = false`.

The call is reflective so the plugin does not need a compile-time Fabric API dependency. The overload is present in
Fabric API 0.92.x (1.20.1), 0.116.x (1.21.1), and the 26.x releases. `ResourceLocation` construction tries
`fromNamespaceAndPath`, `tryBuild`, the two-argument constructor, and the single-string constructor in that order.

The generated hook implements `net.fabricmc.api.ModInitializer`. Its fully qualified name is inserted into the
generated `fabric.mod.json` `entrypoints.main` array. The insertion is a bracket-aware string operation so it does
not depend on a JSON library at build time.

### Forge 1.16.5

Forge 1.16.5 has no `AddPackFindersEvent`. Its generated hook runs at `FMLClientSetupEvent`, adds an `IPackFinder`
lambda to the client `ResourcePackRepository`, and creates `ResourcePackInfo` entries backed by extracted `FolderPack`
directories. The required `FolderPack`, `ResourcePackInfo`, and `IPackNameDecorator` references are compile-time
references and are reobfuscated with the mod.

## Verification performed

* `javac --release 25 -Xlint:all` against the vendored MCreator 2026.3 API: 0 errors.
* `gradlew build`: `BUILD SUCCESSFUL`.
* Generated runtime compiled with `--release 25`: 0 errors.
* Generated Forge modern reflective hook, NeoForge 26.x-style hook, and Fabric `ModInitializer` hook compiled against
  loader API stubs: 0 errors.
* Build-handler harness for `fabric-1.21.8`, `forge-1.20.4`, and `neoforge-26.3`: drop folders created, only direct
  zips selected, names normalized, manifest and target marker written, Fabric directory trees extracted, and the
  managed build script inserted.
* Fabric `main` entrypoint injection produced valid JSON and preserved the existing main and client entrypoints.
* Plugin package inspection: `plugin.json` at the archive root, expanded version, compiled classes, no compile-time
  libraries.
* The generated runtime's Fabric path was checked against the Fabric API 0.92.x, 0.116.x, and 0.162.x
  `ResourceManagerHelper` signatures; the selected four-argument boolean overload is present in all of them.

Workspace-level in-game verification on each target installation remains a manual test because it requires a real
MCreator workspace, the matching Minecraft dependencies, and a graphical client.

## Known Limitations

### Third-party generators are not locally available

The 2026.3 EAP does not bundle every requested generator. `forge-26.1.2`, `forge-26.2`, `forge-26.3`,
`neoforge-26.1.2`, `neoforge-26.2`, and `fabric-26.2` in particular are not present in the local EAP plugin set. Their
hooks follow the confirmed Forge/NeoForge/Fabric API shapes, but they require real-machine verification with the
third-party generator plugins.

### Forge 26.x event bus shape

The Forge 26.x hook follows the Forge 1.20.4-1.21.1 shape: `net.minecraftforge.event.AddPackFindersEvent`,
`net.minecraftforge.eventbus.api.SubscribeEvent`, and `net.minecraftforge.fml.common.Mod.EventBusSubscriber`. If a
third-party Forge 26.x generator changes the event bus or annotation package, the generated hook will need the
corresponding branch.

### Forge 1.16.5 data packs

Forge 1.16.5 does not provide `AddPackFindersEvent`. Resource packs are registered through the client `IPackFinder`
path. The world creation data pack list cannot be extended from a regular Forge mod without a coremod or mixin. The
archive is still normalized and bundled into the jar, and the known limitation is limited to automatic data pack
registration on Forge 1.16.5.

If a Forge 1.16.5 data pack must be selectable, the user can copy it into the world's `datapacks/` directory manually.
No archive content is changed by this plugin.

### Fabric pack metadata

Fabric's built-in pack reader requires a root `pack.mcmeta`. An archive without it is still copied into the jar but is
not extracted into the Fabric registration tree, and the build does not fail. Invalid `pack.mcmeta` content is left
for Fabric to reject.

### Export action and direct Gradle

The template event covers MCreator's build and export actions. A direct `gradlew build` invocation in the generated
workspace still does not run MCreator's template generation, so it reuses the last prepared tree. The plugin does not
try to patch MCreator's private export action.

### Build output cleanup

`build/libs` and the plugin-owned class/resource directories are deleted before each preparation. If an old jar is
locked by a running game on Windows, deletion can fail; close the game and retry. The cleanup intentionally does not
delete `.gradle` caches or the entire `build` directory.

### Strict pack structure

The plugin deliberately does not validate, repair, or rewrite pack contents. Forge and NeoForge use the loader's
normal metadata reader; archives with missing or malformed `pack.mcmeta` are skipped by the loader. A malformed zip
archive is logged and skipped without failing the build.

### Managed build script block

The block appended to `build.gradle` is regenerated on each MCreator preparation. Manual edits inside the managed
markers are lost. MCreator regenerates `build.gradle` from its generator base before template rendering, so the plugin
appends the current block during the template event, before Gradle starts.

### Synchronous event timeout

MCreator invokes plugin events synchronously with a ten-second listener timeout. Very large archives or slow disks can
exceed that window. Typical resource pack and data pack archives are well below the limit. If a build exceeds the
timeout, MCreator may start Gradle before the copy or extraction completes.

### Java target

The build uses JDK 25 and emits Java 25 bytecode because the bundled MCreator 2026.3 runtime is JBR 25. The plugin is
not intended to load in MCreator 2026.1, whose runtime is Java 21.

### Generator allowlist

Only the generator names listed above are processed. A datapack-only or resourcepack-only MCreator workspace, or an
unlisted generator version, is not modified. The plugin removes its managed block and generated tree when it observes
an unsupported generator name.

### Dedicated servers

Forge and NeoForge register the data pack repository source on both logical sides through the loader event. Resource
pack registration on a dedicated server is harmless but unused. Fabric registers both pack types through the
built-in API; only the type whose directory contains matching namespaces is created. All packs remain optional and
disabled by default.
