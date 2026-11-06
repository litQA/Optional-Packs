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
| Workspace folder access | `Workspace#getFolderManager()#getWorkspaceFolder()` |
| Workspace package access | `WorkspaceSettings#getModElementsPackage()` |
| Generator name access | `Generator#getGeneratorName()` |

The supported target matrix is six Forge generators (`forge-1.16.5` through `forge-1.20.1`) and six NeoForge
generators (`neoforge-1.20.1`, `neoforge-1.20.4`, `neoforge-1.20.6`, `neoforge-1.21.1`, `neoforge-1.21.4`,
`neoforge-1.21.8`). Fabric support was removed from this 2026.1 branch.

The NeoForge 1.20.1 generator uses the Forge 1.20.1 code shape and the `net.minecraftforge` package. It therefore
uses the same generated hook as Forge 1.20.1 instead of the `net.neoforged` hook used by NeoForge 1.20.4 and later.

## Implementation summary

The build listener performs all work synchronously so the files exist before Gradle starts:

1. Creates the source drop folders.
2. Scans only direct `.zip` files.
3. Normalizes names and copies archives to `.optional_packs/generated/resources/optional_packs/`.
4. Writes the loader hook and shared reflection runtime to `.optional_packs/generated/java/`.
5. Adds a managed block to the generated `build.gradle`. The block excludes the source drop folders from
   `processResources` and adds the generated Java and resource roots.

Forge 1.17.1 through 1.20.1 and NeoForge 1.20.1 use `AddPackFindersEvent`. The runtime locates the correct
`FolderRepositorySource` constructor at runtime:

* `(File, PackSource)` on Forge 1.17.1, 1.18.2, and 1.19.2.
* `(Path, PackType, PackSource)` on Forge 1.19.4 and 1.20.1.
* `(Path, PackType, PackSource, DirectoryValidator)` on NeoForge 1.20.4 and later.

The `(File, PackSource)` form was the cause of the first-round failure on 1.16.5 through 1.19.2. The previous
reflection only supplied a `Path`, so no constructor matched and no repository source was added. The current
reflection supplies either a `Path` or its `File` equivalent.

Forge 1.16.5 has no `AddPackFindersEvent`. Its generated hook runs at `FMLClientSetupEvent`, adds an `IPackFinder` to
the client `ResourcePackRepository`, and creates `ResourcePackInfo` entries backed by `FilePack` objects. The old
`ResourcePackInfo.create` arguments are filled by type, and reflective methods are made accessible for generated
lambda classes. A game-directory copy remains as a fallback only when the finder path is unavailable.

No mod element, editor, menu, toolbar button, preference page, or workspace panel is registered.

## Verification performed

* `javac --release 21 -Xlint:all` against the vendored MCreator 2026.1 API: 0 errors.
* `gradlew build`: `BUILD SUCCESSFUL`.
* Plugin package inspection: `plugin.json` at the archive root, expanded version, compiled classes, no compile-time
  libraries.
* Build-handler harness for all supported generator names: folders created, only zip files selected, normalization
  and collision handling executed, source archives copied unchanged, build block injected.
* Generated runtime compiled with `--release 8` to confirm the runtime does not depend on newer Java language
  features.
* Generated hooks compiled for every loader family against loader API stubs.
* Real 1.17.1, 1.18.2, and 1.19.2 merged jars: `FolderRepositorySource` is constructed successfully through the
  `(File, PackSource)` constructor.
* Real 1.20.1 merged jar: `FolderRepositorySource` is constructed successfully through the `(Path, PackType,
  PackSource)` constructor.
* Stubbed 1.21.8 audit: the four-argument `FolderRepositorySource` constructor with `DirectoryValidator` is
  constructed successfully.
* Stubbed 1.16.5 audit: the old `IPackFinder` path creates `ResourcePackInfo` entries and accepts every normalized
  archive, with `required=false`.
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

### Build event dependency

The folders, copies, and generated sources are prepared only when MCreator runs a workspace build. Running
`gradlew build` directly in the generated workspace reuses the last prepared tree and does not rescan the source
drop folders. This is intentional because the plugin receives the MCreator workspace context from
`WorkspaceBuildStartedEvent`.

### Strict pack structure

The plugin deliberately does not validate, repair, or rewrite pack contents. Forge and NeoForge use the loader's
normal metadata reader; archives with missing or malformed `pack.mcmeta` are skipped by the loader. A malformed zip
archive is logged and skipped without failing the build.

### Managed build script block

The block appended to `build.gradle` is regenerated on each MCreator build. Manual edits inside the managed markers
are lost. MCreator regenerates `build.gradle` from its generator template before the build event, so the plugin
appends the current block after that regeneration.

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
