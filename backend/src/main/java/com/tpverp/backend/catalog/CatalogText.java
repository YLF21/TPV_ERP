package com.tpverp.backend.catalog;

import java.util.Locale;
import java.text.Normalizer;

final class CatalogText {

    private CatalogText() {
    }

    static String normalized(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
                .toUpperCase(Locale.ROOT);
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String searchTerm(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("q es obligatorio");
        }
        String normalized = Normalizer.normalize(value.trim().toUpperCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f\\u1ab0-\\u1aff\\u1dc0-\\u1dff\\u20d0-\\u20ff\\ufe20-\\ufe2f]+", "");
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 2 || length > 100) {
            throw new IllegalArgumentException("q debe contener entre 2 y 100 caracteres");
        }
        return normalized;
    }

    static String escapeLikeLiteral(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
