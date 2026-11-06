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
| Template event | `net.mcreator.plugin.events.ModifyTemplateResultEvent` |
| Fabric metadata template | `modbase/fabric.mod.json.ftl` |
| Workspace folder access | `Workspace#getFolderManager()#getWorkspaceFolder()` |
| Workspace package access | `WorkspaceSettings#getModElementsPackage()` |
| Generator name access | `Generator#getGeneratorName()` |

The target matrix is exactly the generator directory names listed in the task: six Forge generators, five NeoForge
generators, and `fabric-1.21.8`.

## Implementation summary

The build listener performs all work synchronously so the files exist before Gradle starts:

1. Creates the source drop folders.
2. Scans only direct `.zip` files.
3. Normalizes names and copies archives to `.optional_packs/generated/resources/optional_packs/`.
4. Writes the loader hook and shared reflection runtime to `.optional_packs/generated/java/`.
5. Adds a managed block to the generated `build.gradle`. The block excludes the source drop folders from
   `processResources` and adds the generated Java and resource roots.
6. For Fabric, extracts the archives into the directory layout required by Fabric's built-in pack API.

Forge and NeoForge hooks use `AddPackFindersEvent`. A temporary directory containing the bundled zips is registered
through `FolderRepositorySource` for the pack type reported by the event. NeoForge's event bus annotation differs
between 1.20.4, 1.20.6, and 1.21.x, so the generated hook follows the annotation form used by each generator.

Fabric uses the `main` entrypoint for a generated `ModInitializer`. `fabric.mod.json` is modified through
`ModifyTemplateResultEvent` to add that entrypoint. The runtime calls
`ResourceManagerHelper#registerBuiltinResourcePack` with `ResourcePackActivationType.NORMAL`. `NORMAL` is optional and
disabled by default; no `ALWAYS_ENABLED` or default-enabled mode is used.

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
* A real MC 1.20.1 audit confirmed that `FolderRepositorySource` is constructed through the three-argument
  constructor. A stubbed MC 1.21.8 audit confirmed the four-argument constructor with `DirectoryValidator`.
* A standalone Gradle project verified that the managed block excludes raw `resourcepacks/` and `datapacks/` while
  including generated `optional_packs/` zips and generated Fabric `resourcepacks/r_*/` directories.
* Configuration cache test of the managed block: configuration cache entry stored successfully.

Workspace level in-game verification on each target installation remains a manual test because it requires a real
MCreator workspace, the matching Minecraft dependencies, and a graphical client.

## Known Limitations

### Forge 1.16.5 data packs

Forge 1.16.5 does not provide `AddPackFindersEvent`. Resource packs can be copied into the game directory and the
client resource pack repository can be reloaded, which is implemented. The world creation data pack list cannot be
extended from a regular Forge mod without a coremod or mixin. The archive is still normalized and bundled into the jar,
and the known limitation is limited to automatic data pack registration on Forge 1.16.5.

If a Forge 1.16.5 data pack must be selectable, the user can extract it into the world's `datapacks/` directory
manually. No archive content is changed by this plugin.

### Build event dependency

The folders, copies, and generated sources are prepared only when MCreator runs a workspace build. Running
`gradlew build` directly in the generated workspace reuses the last prepared tree and does not rescan the source
drop folders. This is intentional because the plugin receives the MCreator workspace context from
`WorkspaceBuildStartedEvent`.

### Fabric directory layout

Fabric's built-in pack API consumes pack directories under `resourcepacks/<id path>`. The build therefore writes
derived directories in addition to the normalized zips. The derived directory is generated content; the source zip
is not modified. This increases the mod jar size by the sum of the unpacked archives.

Fabric registration also requires a root `pack.mcmeta`. An archive without that file remains in the jar but is not
registered with Fabric, because the Fabric API cannot derive pack metadata from it. This is a loader constraint, not
a repair or rewrite of the archive.

### Strict pack structure

The plugin deliberately does not validate, repair, or rewrite pack contents. Forge and NeoForge use the loader's
normal metadata reader; archives with missing or malformed `pack.mcmeta` are skipped by the loader. Fabric packs
without a root `pack.mcmeta` are skipped during generation. A malformed zip archive is logged and skipped without
failing the build.

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

Only the generator names listed in the task are processed. A datapack-only or resourcepack-only MCreator workspace,
or an unlisted generator version, is not modified. The plugin removes its managed block and generated tree when it
observes an unsupported generator name.

### Dedicated servers

Forge and NeoForge register the data pack repository source on both logical sides through the loader event. Fabric
uses a common `main` entrypoint. Resource pack registration on a dedicated server is harmless but unused. The data
pack is still optional and disabled by default.
