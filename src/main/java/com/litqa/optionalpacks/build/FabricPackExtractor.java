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
 * Fabric's built-in pack API consumes pack directories rather than zip files. This class creates the derived
 * directories from the untouched source archives inside the generated resource tree.
 */
final class FabricPackExtractor {

    private static final Logger LOG = Logger.getLogger("Optional Packs");

    private FabricPackExtractor() {
    }

    /**
     * Extracts packs into {@code resourcepacks/<prefix><name>} directories.
     *
     * @param generatedResources generated resource root
     * @param packs normalized packs of one category
     * @param prefix {@code r_} for resource packs or {@code d_} for data packs
     */
    static void extract(Path generatedResources, List<NormalizedPack> packs, String prefix) {
        Path root = generatedResources.resolve("resourcepacks");
        for (NormalizedPack pack : packs) {
            Path target = root.resolve(prefix + pack.name());
            try (ZipFile zip = new ZipFile(pack.source().toFile())) {
                if (zip.getEntry("pack.mcmeta") == null) {
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
