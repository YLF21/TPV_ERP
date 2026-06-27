package com.tpverp.backend.shared.domain;

public final class TextValues {

    private TextValues() {
    }

    // Normalizes blank text to null for optional domain fields.
    public static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // Normalizes required text or fails with the existing legacy message format.
    public static String required(String value, String field) {
        var normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }
}
