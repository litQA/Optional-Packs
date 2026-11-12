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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin entry point.
 *
 * <p>The plugin has no user interface. It prepares the two resource drop folders, copies and normalizes the detected
 * zip archives, writes a loader specific hook into the generated source tree, and updates the generated build
 * script. Fabric workspaces additionally receive the hook in the generated {@code fabric.mod.json} main
 * entrypoint.</p>
 *
 * <p>MCreator's export action calls {@code Generator.generateBase()} and then Gradle directly, but it does not fire
 * {@link WorkspaceBuildStartedEvent}. The mod base template event is therefore the hook that covers both the normal
 * build action and the export action.</p>
 */
@SuppressWarnings("this-escape")
public final class Launcher extends JavaPlugin {

    private static final Logger LOG = LogManager.getLogger("Optional Packs");
    private static final String MOD_BASE_TEMPLATE = "modbase/mod.java.ftl";
    private static final String FABRIC_MOD_JSON_TEMPLATE = "modbase/fabric.mod.json.ftl";
    private static final String FABRIC_GENERATOR_PREFIX = "fabric-";
    private static final Pattern MAIN_ENTRYPOINT_ARRAY = Pattern.compile("\"main\"\\s*:\\s*\\[");
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
            String templateName = event.getTemplateName();
            if (MOD_BASE_TEMPLATE.equals(templateName)) {
                Object workspaceInfo = event.getDataModel().get("w");
                if (!(workspaceInfo instanceof WorkspaceInfo info)) {
                    return;
                }
                Workspace workspace = info.getWorkspace();
                if (prepareWorkspace(workspace, "template event")) {
                    templatePreparedAt.put(workspace.getFolderManager().getWorkspaceFolder().toPath(),
                            System.nanoTime());
                }
                return;
            }
            if (FABRIC_MOD_JSON_TEMPLATE.equals(templateName)) {
                injectFabricEntrypoint(event);
            }
        } catch (Throwable throwable) {
            // Template generation must continue even when pack preparation or metadata injection fails.
            LOG.error("Optional Packs could not process template " + event.getTemplateName(), throwable);
        }
    }

    /**
     * Adds the generated hook to the Fabric {@code main} entrypoint array.
     *
     * <p>Fabric Loader only starts entrypoint classes listed in {@code fabric.mod.json}. The template event is the
     * supported extension point for modifying the generated metadata without changing the generator plugin.</p>
     *
     * @param event template result event for {@code modbase/fabric.mod.json.ftl}
     */
    private void injectFabricEntrypoint(ModifyTemplateResultEvent event) {
        Object workspaceInfo = event.getDataModel().get("w");
        if (!(workspaceInfo instanceof WorkspaceInfo info)) {
            return;
        }
        Workspace workspace = info.getWorkspace();
        if (workspace == null || workspace.getGenerator() == null) {
            return;
        }
        String generatorName = workspace.getGenerator().getGeneratorName();
        if (generatorName == null || !generatorName.startsWith(FABRIC_GENERATOR_PREFIX)) {
            return;
        }
        Path workspaceFolder = workspace.getFolderManager().getWorkspaceFolder().toPath();
        if (!OptionalPacksBuildHandler.hasAnyZip(workspaceFolder)) {
            // No generated hook class exists, so adding the entrypoint would only create a broken reference.
            return;
        }

        String packageName = workspace.getWorkspaceSettings().getModElementsPackage();
        if (packageName == null || packageName.isBlank()) {
            Object packageValue = event.getDataModel().get("package");
            if (packageValue instanceof String value && !value.isBlank()) {
                packageName = value;
            }
        }
        if (packageName == null || packageName.isBlank()) {
            return;
        }
        packageName = packageName.replaceAll("\\.+$", "");
        if (packageName.isBlank()) {
            return;
        }

        String entrypoint = packageName + ".optionalpacks.OptionalPacksHook";
        String output = event.getTemplateOutput();
        String updated = addMainEntrypoint(output, entrypoint);
        if (updated != null && !updated.equals(output)) {
            event.setTemplateOutput(updated);
        }
    }

    /**
     * Inserts an additional main entrypoint into the main entrypoint array of a fabric.mod.json document.
     *
     * @param json generated mod metadata, may be {@code null}
     * @param entrypoint fully qualified entrypoint class name
     * @return the document with the entrypoint added, or the unchanged document when it cannot be modified
     */
    private static String addMainEntrypoint(String json, String entrypoint) {
        if (json == null || json.isBlank()) {
            return json;
        }

        Matcher matcher = MAIN_ENTRYPOINT_ARRAY.matcher(json);
        if (!matcher.find()) {
            return json;
        }

        int arrayStart = matcher.end() - 1;
        int arrayEnd = findClosingBracket(json, arrayStart);
        if (arrayEnd < 0) {
            return json;
        }

        String entry = "\"" + entrypoint + "\"";
        String arrayContent = json.substring(arrayStart + 1, arrayEnd);
        if (arrayContent.contains(entry)) {
            return json;
        }

        StringBuilder result = new StringBuilder(json);
        if (arrayContent.isBlank()) {
            result.insert(arrayStart + 1, entry);
        } else {
            result.insert(arrayStart + 1, entry + ",");
        }
        return result.toString();
    }

    /**
     * Finds the closing bracket that belongs to the given opening bracket, ignoring brackets inside string literals.
     *
     * @param text document to inspect
     * @param openIndex index of the opening bracket
     * @return index of the matching closing bracket, or {@code -1} when the document is malformed
     */
    private static int findClosingBracket(String text, int openIndex) {
        int depth = 0;
        boolean inString = false;
        for (int index = openIndex; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString) {
                if (character == '\\') {
                    index++;
                } else if (character == '"') {
                    inString = false;
                }
            } else if (character == '"') {
                inString = true;
            } else if (character == '[') {
                depth++;
            } else if (character == ']') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
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
        boolean generated = OptionalPacksBuildHandler.handle(context);
        LOG.info("Optional Packs processed " + generatorName + " via " + trigger
                + (generated ? " with generated pack code" : " without generated pack code"));
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
