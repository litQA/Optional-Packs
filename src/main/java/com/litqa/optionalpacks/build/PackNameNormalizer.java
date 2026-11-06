package com.litqa.optionalpacks.build;

import java.util.Locale;

/**
 * Converts an arbitrary user supplied zip file name into the restricted ASCII pack name accepted by the plugin.
 */
public final class PackNameNormalizer {

    private static final String ZIP_EXTENSION = ".zip";

    private PackNameNormalizer() {
    }

    /**
     * Normalizes a source file name.
     *
     * @param fileName original file name, including the {@code .zip} extension
     * @return a non-empty name containing only lowercase ASCII letters, digits, underscores and dots
     */
    public static String normalize(String fileName) {
        String baseName = fileName;
        if (baseName.toLowerCase(Locale.ROOT).endsWith(ZIP_EXTENSION)) {
            baseName = baseName.substring(0, baseName.length() - ZIP_EXTENSION.length());
        }

        StringBuilder result = new StringBuilder(baseName.length());
        boolean previousUnderscore = false;
        for (int index = 0; index < baseName.length(); index++) {
            char character = Character.toLowerCase(baseName.charAt(index));
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '.';
            char mapped = allowed ? character : '_';
            if (mapped == '_') {
                if (previousUnderscore) {
                    continue;
                }
                previousUnderscore = true;
            } else {
                previousUnderscore = false;
            }
            result.append(mapped);
        }

        int start = 0;
        int end = result.length();
        while (start < end && (result.charAt(start) == '_' || result.charAt(start) == '.')) {
            start++;
        }
        while (end > start && (result.charAt(end - 1) == '_' || result.charAt(end - 1) == '.')) {
            end--;
        }

        String normalized = result.substring(start, end);
        return normalized.isEmpty() ? "pack" : normalized;
    }

    /**
     * Returns the first unique name in the given sequence, appending {@code _2}, {@code _3}, and so on when needed.
     *
     * @param baseName normalized base name
     * @param usedNames names already assigned in the current category
     * @return a name that is not present in {@code usedNames}
     */
    public static String uniqueName(String baseName, Iterable<String> usedNames) {
        String candidate = baseName;
        int suffix = 2;
        while (contains(usedNames, candidate)) {
            candidate = baseName + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static boolean contains(Iterable<String> values, String candidate) {
        for (String value : values) {
            if (value.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
