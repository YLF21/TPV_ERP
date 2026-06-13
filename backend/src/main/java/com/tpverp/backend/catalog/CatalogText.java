package com.tpverp.backend.catalog;

import java.util.Locale;

final class CatalogText {

    private CatalogText() {
    }

    static String normalized(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
