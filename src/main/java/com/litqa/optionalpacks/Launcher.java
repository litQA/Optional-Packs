package com.litqa.optionalpacks;

import com.litqa.optionalpacks.build.BuildContext;
import com.litqa.optionalpacks.build.OptionalPacksBuildHandler;
import net.mcreator.generator.Generator;
import net.mcreator.plugin.JavaPlugin;
import net.mcreator.plugin.Plugin;
import net.mcreator.plugin.events.workspace.WorkspaceBuildStartedEvent;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.settings.WorkspaceSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * Plugin entry point.
 *
 * <p>The plugin has no user interface. It creates the two resource drop folders, copies and normalizes the detected
 * zip archives, writes a loader specific hook into the generated source tree, and updates the generated build
 * script so the generated tree is compiled and packaged.</p>
 */
@SuppressWarnings("this-escape")
public final class Launcher extends JavaPlugin {

    private static final Logger LOG = LogManager.getLogger("Optional Packs");

    public Launcher(Plugin plugin) {
        super(plugin);
        addListener(WorkspaceBuildStartedEvent.class, this::onWorkspaceBuildStarted);
        LOG.info("Optional Packs plugin was loaded");
    }

    private void onWorkspaceBuildStarted(WorkspaceBuildStartedEvent event) {
        try {
            Workspace workspace = event.getMCreator().getWorkspace();
            if (workspace == null) {
                return;
            }
            Generator generator = workspace.getGenerator();
            if (generator == null) {
                return;
            }
            String generatorName = generator.getGeneratorName();
            Path workspaceFolder = workspace.getFolderManager().getWorkspaceFolder().toPath();
            if (!OptionalPacksBuildHandler.supports(generatorName)) {
                OptionalPacksBuildHandler.cleanup(workspaceFolder);
                return;
            }

            WorkspaceSettings settings = workspace.getWorkspaceSettings();
            String modId = settings.getModID();
            String packageName = settings.getModElementsPackage();
            if (packageName == null || packageName.isBlank()) {
                packageName = "net.mcreator." + modId;
            }
            packageName = packageName.replaceAll("\\.+$", "");

            BuildContext context = new BuildContext(
                    workspaceFolder,
                    workspaceFolder.resolve("src").resolve("main").resolve("resources"),
                    generatorName,
                    modId,
                    packageName,
                    OptionalPacksBuildHandler.targetFor(generatorName));
            OptionalPacksBuildHandler.handle(context);
        } catch (Throwable throwable) {
            // A resource pack problem must never abort the workspace build.
            LOG.error("Optional Packs could not prepare the workspace", throwable);
        }
    }
}
