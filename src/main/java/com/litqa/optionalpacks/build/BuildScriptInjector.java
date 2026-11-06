package com.litqa.optionalpacks.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adds the generated source/resource directories and source resource exclusions to a workspace build script.
 *
 * <p>The block is idempotent. MCreator regenerates {@code build.gradle} from its generator template before every
 * build, so the block is appended again after each generation instead of being treated as a user editable file.</p>
 */
public final class BuildScriptInjector {

    private static final String START_MARKER = "// >>> optional_packs managed block >>>";
    private static final String END_MARKER = "// <<< optional_packs managed block <<<";

    private static final String BLOCK = START_MARKER + "\n"
            + "// This block is rewritten by the optional_packs MCreator plugin on every build.\n"
            + "def optionalPacksGenerated = project.file('.optional_packs/generated')\n"
            + "sourceSets.main.java.srcDir(new File(optionalPacksGenerated, 'java'))\n"
            + "sourceSets.main.resources {\n"
            + "    // The source drop folders are input only. Generated copies are added to processResources below.\n"
            + "    exclude 'resourcepacks/**'\n"
            + "    exclude 'datapacks/**'\n"
            + "}\n"
            + "processResources {\n"
            + "    from(new File(optionalPacksGenerated, 'resources'))\n"
            + "}\n"
            + END_MARKER + "\n";

    private BuildScriptInjector() {
    }

    /**
     * Injects the managed block into a workspace build script, replacing an older managed block when present.
     *
     * @param buildScript workspace {@code build.gradle}
     * @throws IOException when the file cannot be read or written
     */
    public static void inject(Path buildScript) throws IOException {
        String content = Files.exists(buildScript) ? Files.readString(buildScript, StandardCharsets.UTF_8) : "";
        content = removeBlock(content);
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content += "\n";
        }
        Files.createDirectories(buildScript.getParent());
        Files.writeString(buildScript, content + BLOCK, StandardCharsets.UTF_8);
    }

    /**
     * Removes the managed block from a workspace build script.
     *
     * @param buildScript workspace {@code build.gradle}
     * @throws IOException when the file cannot be read or written
     */
    public static void remove(Path buildScript) throws IOException {
        if (!Files.exists(buildScript)) {
            return;
        }
        String content = Files.readString(buildScript, StandardCharsets.UTF_8);
        String updated = removeBlock(content);
        if (!updated.equals(content)) {
            Files.writeString(buildScript, updated, StandardCharsets.UTF_8);
        }
    }

    private static String removeBlock(String content) {
        int start = content.indexOf(START_MARKER);
        if (start < 0) {
            return content;
        }
        int end = content.indexOf(END_MARKER, start);
        if (end < 0) {
            return content;
        }
        end += END_MARKER.length();
        while (end < content.length() && (content.charAt(end) == '\r' || content.charAt(end) == '\n')) {
            end++;
        }
        return content.substring(0, start) + content.substring(end);
    }
}
