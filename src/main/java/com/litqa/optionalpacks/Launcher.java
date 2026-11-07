package com.litqa.optionalpacks;

import com.litqa.optionalpacks.build.BuildContext;
import com.litqa.optionalpacks.build.OptionalPacksBuildHandler;
import net.mcreator.generator.Generator;
import net.mcreator.plugin.JavaPlugin;
import net.mcreator.plugin.Plugin;
import net.mcreator.plugin.events.ModifyTemplateResultEvent;
import net.mcreator.plugin.events.workspace.WorkspaceBuildStartedEvent;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.misc.WorkspaceInfo;
import net.mcreator.workspace.settings.WorkspaceSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Plugin entry point.
 *
 * <p>The plugin has no user interface. It prepares the two resource drop folders, copies and normalizes the detected
 * zip archives, writes a loader specific hook into the generated source tree, and updates the generated build
 * script.</p>
 *
 * <p>MCreator's export action calls {@code Generator.generateBase()} and then Gradle directly, but it does not fire
 * {@link WorkspaceBuildStartedEvent}. The mod base template event is therefore the hook that covers both the normal
 * build action and the export action.</p>
 */
@SuppressWarnings("this-escape")
public final class Launcher extends JavaPlugin {

    private static final Logger LOG = LogManager.getLogger("Optional Packs");
    private static final String MOD_BASE_TEMPLATE = "modbase/mod.java.ftl";
    private static final long TEMPLATE_PREPARE_DEDUP_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Map<Path, Long> templatePreparedAt = new ConcurrentHashMap<>();

    public Launcher(Plugin plugin) {
        super(plugin);
        addListener(WorkspaceBuildStartedEvent.class, this::onWorkspaceBuildStarted);
        addListener(ModifyTemplateResultEvent.class, this::onTemplateResult);
        LOG.info("Optional Packs " + pluginVersion() + " plugin was loaded");
    }

    private void onWorkspaceBuildStarted(WorkspaceBuildStartedEvent event) {
        try {
            Workspace workspace = event.getMCreator().getWorkspace();
            if (workspace == null) {
                return;
            }
            Path workspaceFolder = workspace.getFolderManager().getWorkspaceFolder().toPath();
            Long preparedAt = templatePreparedAt.remove(workspaceFolder);
            if (preparedAt != null && System.nanoTime() - preparedAt < TEMPLATE_PREPARE_DEDUP_NANOS) {
                LOG.debug("Optional Packs was already prepared during code generation for {}", workspaceFolder);
                return;
            }
            prepareWorkspace(workspace, "build event");
        } catch (Throwable throwable) {
            // A resource pack problem must never abort the workspace build.
            LOG.error("Optional Packs could not prepare the workspace", throwable);
        }
    }

    private void onTemplateResult(ModifyTemplateResultEvent event) {
        try {
            if (!MOD_BASE_TEMPLATE.equals(event.getTemplateName())) {
                return;
            }
            Object workspaceInfo = event.getDataModel().get("w");
            if (!(workspaceInfo instanceof WorkspaceInfo info)) {
                return;
            }
            Workspace workspace = info.getWorkspace();
            if (prepareWorkspace(workspace, "template event")) {
                templatePreparedAt.put(workspace.getFolderManager().getWorkspaceFolder().toPath(), System.nanoTime());
            }
        } catch (Throwable throwable) {
            // Template generation must continue even when pack preparation fails.
            LOG.error("Optional Packs could not prepare the workspace from the template event", throwable);
        }
    }

    private boolean prepareWorkspace(Workspace workspace, String trigger) throws IOException {
        if (workspace == null) {
            return false;
        }
        Generator generator = workspace.getGenerator();
        if (generator == null) {
            return false;
        }
        String generatorName = generator.getGeneratorName();
        Path workspaceFolder = workspace.getFolderManager().getWorkspaceFolder().toPath();
        if (!OptionalPacksBuildHandler.supports(generatorName)) {
            OptionalPacksBuildHandler.cleanup(workspaceFolder);
            return false;
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
                pluginVersion(),
                OptionalPacksBuildHandler.targetFor(generatorName));
        OptionalPacksBuildHandler.handle(context);
        LOG.info("Optional Packs prepared " + generatorName + " via " + trigger);
        return true;
    }

    private String pluginVersion() {
        try {
            String version = getPlugin().getInfo().getVersion();
            return version == null || version.isBlank() ? "unknown" : version;
        } catch (Throwable throwable) {
            return "unknown";
        }
    }
}
