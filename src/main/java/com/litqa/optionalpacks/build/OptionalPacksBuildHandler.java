package com.litqa.optionalpacks.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Prepares a workspace for a build: creates the two drop folders, normalizes zip names, writes generated resources
 * and generated loader hooks, and injects the generated source/resource paths into {@code build.gradle}.
 */
public final class OptionalPacksBuildHandler {

    private static final Logger LOG = Logger.getLogger("Optional Packs");

    private static final Set<String> SUPPORTED_GENERATORS = Set.of(
            "forge-1.16.5",
            "forge-1.17.1",
            "forge-1.18.2",
            "forge-1.19.2",
            "forge-1.19.4",
            "forge-1.20.1",
            "neoforge-1.20.1",
            "neoforge-1.20.4",
            "neoforge-1.20.6",
            "neoforge-1.21.1",
            "neoforge-1.21.4",
            "neoforge-1.21.8");

    private static final String RESOURCE_FOLDER = "resourcepacks";
    private static final String DATA_FOLDER = "datapacks";

    private OptionalPacksBuildHandler() {
    }

    /**
     * Prepares a workspace for a build.
     *
     * @param context workspace and generator information
     * @throws IOException when the generated tree or build script cannot be written
     */
    public static void handle(BuildContext context) throws IOException {
        // The export action calls generateBase() and Gradle without firing WorkspaceBuildStartedEvent. The template
        // event calls this method before Gradle; stale plugin state and plugin-owned build outputs are removed first.
        deleteRecursively(context.workspace().resolve(".optional_packs"));
        cleanupBuildOutputs(context.workspace());

        Path resources = context.resources();
        Path resourceSource = resources.resolve(RESOURCE_FOLDER);
        Path dataSource = resources.resolve(DATA_FOLDER);
        Files.createDirectories(resourceSource);
        Files.createDirectories(dataSource);

        Path generatedResources = context.generatedResourcesRoot();
        Path generatedResourcePacks = generatedResources.resolve("optional_packs").resolve(RESOURCE_FOLDER);
        Path generatedDataPacks = generatedResources.resolve("optional_packs").resolve(DATA_FOLDER);
        Files.createDirectories(generatedResourcePacks);
        Files.createDirectories(generatedDataPacks);

        List<NormalizedPack> resourcePacks = copyPacks(resourceSource, generatedResourcePacks);
        List<NormalizedPack> dataPacks = copyPacks(dataSource, generatedDataPacks);

        if (resourcePacks.isEmpty() && dataPacks.isEmpty()) {
            // Keep the source drop folders out of the mod jar, but do not emit any plugin classes or resources.
            deleteRecursively(context.workspace().resolve(".optional_packs"));
            BuildScriptInjector.inject(context.workspace().resolve("build.gradle"));
            LOG.info("Optional Packs found no zip files for " + context.generatorName()
                    + "; no optional pack code was generated");
            return;
        }

        writeManifest(generatedResources, resourcePacks, dataPacks);
        writeVersionMarker(generatedResources, context);
        GeneratedSourceFactory.write(context, resourcePacks, dataPacks);
        BuildScriptInjector.inject(context.workspace().resolve("build.gradle"));

        LOG.info("Optional Packs prepared " + resourcePacks.size() + " resource pack(s) and "
                + dataPacks.size() + " data pack(s) for " + context.generatorName());
    }

    /**
     * Removes generated files, plugin-owned build outputs, and the managed build script block for a workspace that is
     * not supported by this plugin.
     *
     * @param workspace workspace root
     * @throws IOException when the build script cannot be updated
     */
    public static void cleanup(Path workspace) throws IOException {
        deleteRecursively(workspace.resolve(".optional_packs"));
        cleanupBuildOutputs(workspace);
        BuildScriptInjector.remove(workspace.resolve("build.gradle"));
    }

    /**
     * Returns whether the generator receives build-time pack processing.
     *
     * @param generatorName MCreator generator directory name
     * @return {@code true} for a supported generator
     */
    public static boolean supports(String generatorName) {
        return generatorName != null && SUPPORTED_GENERATORS.contains(generatorName);
    }

    /**
     * Maps a MCreator generator name to the generated runtime hook family.
     *
     * @param generatorName MCreator generator directory name
     * @return loader family
     */
    public static BuildContext.Target targetFor(String generatorName) {
        if ("forge-1.16.5".equals(generatorName)) {
            return BuildContext.Target.FORGE_1165;
        }
        if ("forge-1.17.1".equals(generatorName) || "forge-1.18.2".equals(generatorName)
                || "forge-1.19.2".equals(generatorName)) {
            return BuildContext.Target.FORGE_LEGACY;
        }
        // The NeoForge 1.20.1 generator is the Forge 1.20.1 generator with NeoForge branding and uses the same
        // net.minecraftforge API surface.
        if ("forge-1.19.4".equals(generatorName) || "forge-1.20.1".equals(generatorName)
                || "neoforge-1.20.1".equals(generatorName)) {
            return BuildContext.Target.FORGE_MODERN;
        }
        if (generatorName != null && generatorName.startsWith("neoforge-")) {
            return BuildContext.Target.NEOFORGE;
        }
        throw new IllegalArgumentException("Unsupported generator: " + generatorName);
    }

    private static List<NormalizedPack> copyPacks(Path sourceDirectory, Path destinationDirectory) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceDirectory)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    candidates.add(path);
                }
            }
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        List<NormalizedPack> result = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (Path source : candidates) {
            String baseName = PackNameNormalizer.normalize(source.getFileName().toString());
            String name = PackNameNormalizer.uniqueName(baseName, usedNames);
            Path destination = destinationDirectory.resolve(name + ".zip");
            try {
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                usedNames.add(name);
                result.add(new NormalizedPack(name, source));
            } catch (IOException exception) {
                LOG.log(Level.WARNING, "Could not copy optional pack " + source, exception);
            }
        }
        return result;
    }

    private static void writeManifest(Path generatedResources, List<NormalizedPack> resourcePacks,
                                      List<NormalizedPack> dataPacks) throws IOException {
        StringBuilder manifest = new StringBuilder();
        for (NormalizedPack pack : resourcePacks) {
            manifest.append("resourcepack=").append(pack.name()).append('\n');
        }
        for (NormalizedPack pack : dataPacks) {
            manifest.append("datapack=").append(pack.name()).append('\n');
        }
        Path manifestPath = generatedResources.resolve("optional_packs").resolve("packs.txt");
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, manifest.toString(), StandardCharsets.UTF_8);
    }

    private static void writeVersionMarker(Path generatedResources, BuildContext context) throws IOException {
        Path marker = generatedResources.resolve("optional_packs").resolve("version.txt");
        String content = "plugin=" + context.pluginVersion() + "\n"
                + "generator=" + context.generatorName() + "\n"
                + "modid=" + context.modId() + "\n";
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, content, StandardCharsets.UTF_8);
    }

    private static void cleanupBuildOutputs(Path workspace) {
        Path classesRoot = workspace.resolve("build").resolve("classes").resolve("java").resolve("main");
        if (Files.isDirectory(classesRoot)) {
            List<Path> staleDirectories = new ArrayList<>();
            try (var stream = Files.walk(classesRoot)) {
                stream.filter(Files::isDirectory)
                        .filter(path -> "optionalpacks".equals(path.getFileName().toString()))
                        .forEach(staleDirectories::add);
            } catch (IOException exception) {
                LOG.log(Level.WARNING, "Could not scan optional pack class outputs in " + classesRoot, exception);
            }
            staleDirectories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path staleDirectory : staleDirectories) {
                deleteRecursively(staleDirectory);
            }
        }

        deleteRecursively(workspace.resolve("build").resolve("resources").resolve("main").resolve("optional_packs"));
        // Remove derived directories produced by older plugin versions that still supported Fabric.
        deleteRecursively(workspace.resolve("build").resolve("resources").resolve("main").resolve("resourcepacks"));
        deleteRecursively(workspace.resolve("build").resolve("resources").resolve("main").resolve("datapacks"));
        // Removing the jar output guarantees that the export action cannot copy an older jar if Gradle re-runs.
        deleteRecursively(workspace.resolve("build").resolve("libs"));
        deleteRecursively(workspace.resolve("build").resolve("tmp").resolve("jar"));
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // A later build attempt will try again.
                }
            });
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Could not clean generated directory " + path, exception);
        }
    }
}
