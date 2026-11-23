package com.cloudforge.backend.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SlugUtils {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("(^-+|-+$)");

    private SlugUtils() {
    }

    public static String toSlug(String input, String fallback) {
        if (input == null || input.isBlank()) {
            return fallback;
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        String collapsed = NON_ALNUM.matcher(normalized).replaceAll("-");
        String trimmed = EDGE_DASHES.matcher(collapsed).replaceAll("");
        if (trimmed.isBlank()) {
            return fallback;
        }
        return trimmed.length() > 96 ? trimmed.substring(0, 96).replaceAll("-+$", "") : trimmed;
    }
}
