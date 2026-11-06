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

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin entry point.
 *
 * <p>The plugin has no user interface. It creates the two resource drop folders, copies and normalizes the detected
 * zip archives, writes a loader specific hook into the generated source tree, and updates the generated Fabric mod
 * metadata so the hook is started by Fabric Loader.</p>
 */
@SuppressWarnings("this-escape")
public final class Launcher extends JavaPlugin {

    private static final Logger LOG = LogManager.getLogger("Optional Packs");
    private static final String FABRIC_MOD_JSON_TEMPLATE = "modbase/fabric.mod.json.ftl";
    private static final String FABRIC_GENERATOR = "fabric-1.21.8";
    private static final Pattern MAIN_ENTRYPOINT_ARRAY = Pattern.compile("\"main\"\\s*:\\s*\\[");

    public Launcher(Plugin plugin) {
        super(plugin);
        addListener(WorkspaceBuildStartedEvent.class, this::onWorkspaceBuildStarted);
        addListener(ModifyTemplateResultEvent.class, this::onTemplateResult);
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

    private void onTemplateResult(ModifyTemplateResultEvent event) {
        try {
            if (!FABRIC_MOD_JSON_TEMPLATE.equals(event.getTemplateName())) {
                return;
            }
            Map<String, Object> dataModel = event.getDataModel();
            Object workspaceInfo = dataModel.get("w");
            if (!(workspaceInfo instanceof WorkspaceInfo info)) {
                return;
            }
            if (!FABRIC_GENERATOR.equals(info.getWorkspace().getGenerator().getGeneratorName())) {
                return;
            }
            Object packagePath = dataModel.get("package");
            if (!(packagePath instanceof String packageName) || packageName.isBlank()) {
                return;
            }

            String entrypoint = packageName + ".optionalpacks.OptionalPacksHook";
            event.setTemplateOutput(addMainEntrypoint(event.getTemplateOutput(), entrypoint));
        } catch (Throwable throwable) {
            LOG.error("Optional Packs could not add the Fabric entrypoint", throwable);
        }
    }

    /**
     * Inserts an additional main entrypoint into the main entrypoint array of a fabric.mod.json document.
     *
     * @param json       generated mod metadata, may be {@code null}
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
     * @param text      document to inspect
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
}
