# Migration and Verification Report

## 2026.1 API confirmation

The implementation was checked against the MCreator 2026.1 application classes and the bundled generator plugins.

| Item | Result |
| --- | --- |
| MCreator version constant | `2026001` |
| Runtime Java | Java 21 (bundled JBR 21); plugin bytecode is compiled with `--release 21` |
| Plugin base class | `net.mcreator.plugin.JavaPlugin` |
| Build event | `net.mcreator.plugin.events.workspace.WorkspaceBuildStartedEvent` |
| Build event position | After `Generator.generateBase()` and before `GradleConsole.exec("build")` |
| Template event | `net.mcreator.plugin.events.ModifyTemplateResultEvent` for `modbase/mod.java.ftl` |
| Export action | Calls `generateBase()`, then Gradle `build`, then copies `build/libs`; it does not fire the build event |
| Workspace folder access | `Workspace#getFolderManager()#getWorkspaceFolder()` |
| Workspace package access | `WorkspaceSettings#getModElementsPackage()` |
| Generator name access | `Generator#getGeneratorName()` |

The supported target matrix is six Forge generators (`forge-1.16.5` through `forge-1.20.1`) and six NeoForge
generators (`neoforge-1.20.1`, `neoforge-1.20.4`, `neoforge-1.20.6`, `neoforge-1.21.1`, `neoforge-1.21.4`,
`neoforge-1.21.8`). Fabric support was removed from this 2026.1 branch.

The NeoForge 1.20.1 generator uses the Forge 1.20.1 code shape and the `net.minecraftforge` package. It therefore
uses the same generated hook as Forge 1.20.1 instead of the `net.neoforged` hook used by NeoForge 1.20.4 and later.

## Implementation summary

Preparation runs synchronously from two hooks so files exist before Gradle starts:

* `ModifyTemplateResultEvent` for `modbase/mod.java.ftl`, which runs inside `generateBase()` and therefore also runs
  for MCreator's **Export mod** action.
* `WorkspaceBuildStartedEvent`, retained as a fallback for build actions that do not render the mod-base template.

The export action was decompiled to confirm its order: `runResourceSetupTasks()`, `generateBase()`,
`GradleConsole.exec("build")`, then a copy of `build/libs/modid-1.0.jar`. It does not fire
`WorkspaceBuildStartedEvent`. The template event is the supported extension point that covers it.

Each preparation:

1. Removes the workspace `.optional_packs` directory and plugin-owned build outputs.
2. Creates the source drop folders.
3. Scans only direct `.zip` files.
4. Normalizes names and copies archives to `.optional_packs/generated/resources/optional_packs/`.
5. Writes the loader hook and shared runtime to `.optional_packs/generated/java/`.
6. Writes `optional_packs/version.txt` and `OptionalPacksRuntime.VERSION`.
7. Adds a managed block to the generated `build.gradle`.

If neither source folder yields a copied zip, the generated `.optional_packs` tree is removed and no hook, manifest,
or version resource is emitted. The managed build script block remains so non-zip entries in the drop folders are not
packaged into the mod jar. This also removes any stale generated tree from a previous build.

The cleanup removes `.optional_packs`, every `build/classes/java/main/**/optionalpacks` directory,
`build/resources/main/optional_packs`, old derived Fabric resource directories, `build/libs`, and `build/tmp/jar`.
This covers generator conversion and stale export output without deleting `.gradle` caches or the whole build tree.

A short in-memory preparation timestamp prevents the template event and the immediately following build event from
processing the same workspace twice.

Forge hooks are generated as compile-time source for each API generation. This is required because Forge production
remaps Minecraft member names (methods and fields) while the development environment uses named mappings. Reflection
on names such as `PackSource.BUILT_IN`, `getPackType`, or `Minecraft.getInstance` therefore worked in the MCreator
run client but failed in an exported jar. Generated direct calls are reobfuscated by ForgeGradle together with the
rest of the mod, so they keep working in production.

* Forge 1.17.1, 1.18.2, and 1.19.2 use `new FolderRepositorySource(File, PackSource.BUILT_IN)`.
* Forge 1.19.4, Forge 1.20.1, and NeoForge 1.20.1 use
  `new FolderRepositorySource(Path, event.getPackType(), PackSource.BUILT_IN)`.
* NeoForge 1.20.4 and later use the runtime reflection path, because NeoForge keeps stable runtime names.
* Forge 1.16.5 has no `AddPackFindersEvent`. Its generated hook runs at `FMLClientSetupEvent`, adds an `IPackFinder`
  lambda to the client `ResourcePackRepository`, and creates `ResourcePackInfo` entries backed by extracted
  `FolderPack` directories. The required `FolderPack`, `ResourcePackInfo`, and `IPackNameDecorator` references are
  compile-time references and are reobfuscated with the mod.

The generated runtime resolves nested mod-jar resources through `ModList`/`ModFile.findResource` first, then through
the class code source, and finally through the class loader. This addresses production jars where class-loader
resource lookup does not expose nested paths.

No mod element, editor, menu, toolbar button, preference page, or workspace panel is registered.

## Verification performed

* `javac --release 21 -Xlint:all` against the vendored MCreator 2026.1 API: 0 errors.
* `gradlew build`: `BUILD SUCCESSFUL`.
* Plugin package inspection: `plugin.json` at the archive root, expanded version, compiled classes, no compile-time
  libraries.
* Build-handler harness for all supported generator names: folders created, only zip files selected, normalization
  and collision handling executed, source archives copied unchanged, build block injected.
* Cleanup harness: stale `build/classes/**/optionalpacks`, `build/resources/main/optional_packs`, and `build/libs`
  artifacts were removed; the version marker was regenerated.
* Generated runtime compiled with `--release 8` to confirm the runtime does not depend on newer Java language
  features.
* Direct Forge hooks compiled against the real merged jars of 1.17.1, 1.18.2, 1.19.2, 1.19.4, 1.20.1, and
  NeoForge 1.20.1.
* Stubbed 1.16.5 audit: the direct `FolderPack`/`ResourcePackInfo` hook compiles and uses `required=false`.
* Reflection hook compiled for NeoForge 1.20.4 through 1.21.8.
* Exported jar inspection: the normalized archive is present under `optional_packs/resourcepacks/`, and the generated
  classes are present. The production failure was traced to Forge name remapping in reflection, not to a missing jar
  resource.
* A standalone Gradle project verified that the managed block excludes raw `resourcepacks/` and `datapacks/` while
  including generated `optional_packs/` zips.
* Configuration cache test of the managed block: configuration cache entry stored successfully.

Workspace level in-game verification on each target installation remains a manual test because it requires a real
MCreator workspace, the matching Minecraft dependencies, and a graphical client.

## Known Limitations

### Forge 1.16.5 data packs

Forge 1.16.5 does not provide `AddPackFindersEvent`. Resource packs are registered through the client
`IPackFinder` path. The world creation data pack list cannot be extended from a regular Forge mod without a coremod
or mixin. The archive is still normalized and bundled into the jar, and the known limitation is limited to automatic
data pack registration on Forge 1.16.5.

If a Forge 1.16.5 data pack must be selectable, the user can copy it into the world's `datapacks/` directory manually.
No archive content is changed by this plugin.

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
timeout, MCreator may start Gradle before the copy completes. Keep the pack set reasonably small or increase the
available disk throughput.

### Java target

The build uses a JDK 25 toolchain but emits Java 21 bytecode because the bundled MCreator 2026.1 runtime is Java 21.
Compiling the plugin with `--release 25` would produce classes that the bundled runtime cannot load.

### Generator allowlist

Only the generator names listed above are processed. A datapack-only or resourcepack-only MCreator workspace, an
unlisted generator version, or a Fabric workspace is not modified. The plugin removes its managed block and generated
tree when it observes an unsupported generator name.

### Dedicated servers

Forge and NeoForge register the data pack repository source on both logical sides through the loader event. Resource
pack registration on a dedicated server is harmless but unused. The data pack is still optional and disabled by
default.
