package com.litqa.optionalpacks.build;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable data required to prepare one workspace for a build.
 *
 * @param workspace workspace root
 * @param resources {@code src/main/resources} directory
 * @param generatorName MCreator generator directory name, for example {@code forge-1.20.1}
 * @param modId workspace mod id
 * @param packageName Java package used by the generated mod
 * @param target loader family that receives the generated runtime hook
 */
public record BuildContext(Path workspace, Path resources, String generatorName, String modId,
                           String packageName, Target target) {

    public BuildContext {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(generatorName, "generatorName");
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(target, "target");
    }

    /**
     * Loader family used to select the generated hook source.
     */
    public enum Target {
        /** Forge 1.17.1 through 1.20.1 and NeoForge 1.20.1, using AddPackFindersEvent. */
        FORGE,
        /** Forge 1.16.5, which predates AddPackFindersEvent. */
        FORGE_1165,
        /** NeoForge 1.20.4 through 1.21.8. */
        NEOFORGE
    }

    public Path generatedRoot() {
        return workspace.resolve(".optional_packs").resolve("generated");
    }

    public Path generatedJavaRoot() {
        return generatedRoot().resolve("java");
    }

    public Path generatedResourcesRoot() {
        return generatedRoot().resolve("resources");
    }
}
