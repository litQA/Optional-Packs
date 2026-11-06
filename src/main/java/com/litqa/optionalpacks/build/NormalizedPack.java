package com.litqa.optionalpacks.build;

import java.nio.file.Path;

/**
 * A normalized pack copied into the generated resource directory.
 *
 * @param name normalized pack name without the {@code .zip} extension
 * @param source original source archive
 */
public record NormalizedPack(String name, Path source) {
}
