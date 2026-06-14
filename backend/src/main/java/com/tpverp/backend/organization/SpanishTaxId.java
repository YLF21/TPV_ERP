package com.tpverp.backend.organization;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SpanishTaxId {

    private static final Pattern STRUCTURE = Pattern.compile("[A-Z0-9]{9}");

    private SpanishTaxId() {
    }

    public static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("El NIF es obligatorio");
        }
        String normalized = value.replace(" ", "")
                .replace("-", "")
                .toUpperCase(Locale.ROOT);
        if (!STRUCTURE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("El NIF no tiene una estructura valida");
        }
        return normalized;
    }
    // Normaliza separadores habituales sin validar todavía el dígito de control.
}
