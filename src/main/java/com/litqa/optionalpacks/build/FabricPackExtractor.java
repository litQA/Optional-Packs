package com.litqa.optionalpacks.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Creates the directory trees consumed by Fabric's built-in pack API.
 *
 * <p>Fabric resolves built-in packs as directories inside the mod jar. The source archives remain untouched; the
 * extracted trees are written below the generated resource root and are never placed back into the source drop
 * folders.</p>
 */
final class FabricPackExtractor {

    private static final Logger LOG = Logger.getLogger("Optional Packs");
    private static final String FABRIC_ROOT = "optional_packs/fabric";

    private FabricPackExtractor() {
    }

    /**
     * Extracts one category of normalized packs.
     *
     * @param generatedResources generated resource root
     * @param packs normalized packs of one category
     * @param category {@code resourcepacks} or {@code datapacks}
     */
    static void extract(Path generatedResources, List<NormalizedPack> packs, String category) {
        Path root = generatedResources.resolve(FABRIC_ROOT).resolve(category);
        for (NormalizedPack pack : packs) {
            Path target = root.resolve(pack.name());
            try (ZipFile zip = new ZipFile(pack.source().toFile())) {
                if (zip.getEntry("pack.mcmeta") == null) {
                    // Fabric's built-in pack reader requires root pack.mcmeta; skip only the registration tree.
                    LOG.warning("Fabric registration skipped pack without a root pack.mcmeta: " + pack.source());
                    continue;
                }
                deleteRecursively(target);
                Files.createDirectories(target);
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    Path destination = target.resolve(entry.getName()).normalize();
                    if (!destination.startsWith(target)) {
                        LOG.warning("Ignoring zip entry outside the pack directory: " + entry.getName());
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        Path parent = destination.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        try (InputStream input = zip.getInputStream(entry)) {
                            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } catch (Exception exception) {
                LOG.log(Level.WARNING, "Fabric pack extraction failed for " + pack.source(), exception);
                deleteQuietly(target);
            }
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(FabricPackExtractor::deleteQuietly);
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Could not clean derived pack directory " + path, exception);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The directory is regenerated on the next build.
        }
    }
}
