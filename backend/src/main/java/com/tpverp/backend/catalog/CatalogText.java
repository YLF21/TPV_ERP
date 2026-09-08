package com.tpverp.backend.catalog;

import java.util.Locale;
import java.text.Normalizer;

public final class CatalogText {

    private CatalogText() {
    }

    public static String normalized(String value, String field) {
        String canonical = canonicalIdentity(value);
        if (canonical == null || canonical.isEmpty()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return canonical;
    }

    /** Canonical form used by persisted catalog identities and lookups. */
    public static String canonicalIdentity(String value) {
        if (value == null) return null;
        String stripped = stripUnicodeWhitespace(value);
        if (stripped.isEmpty()) return "";
        return Normalizer.normalize(
                Normalizer.normalize(stripped, Normalizer.Form.NFC).toUpperCase(Locale.ROOT),
                Normalizer.Form.NFC);
    }

    public static boolean isBlank(String value) {
        return value == null || stripUnicodeWhitespace(value).isEmpty();
    }

    private static String stripUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    static String optional(String value) {
        return isBlank(value) ? null : stripUnicodeWhitespace(value);
    }

    static String searchTerm(String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("q es obligatorio");
        }
        String normalized = Normalizer.normalize(canonicalIdentity(value), Normalizer.Form.NFD)
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
