package com.tpverp.backend.organization;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SpanishTaxId {

    private static final Pattern STRUCTURE = Pattern.compile("[A-Z0-9]{9}");
    private static final Pattern DNI = Pattern.compile("\\d{8}[A-Z]");
    private static final Pattern NIE = Pattern.compile("[XYZ]\\d{7}[A-Z]");
    private static final Pattern SPECIAL_NIF = Pattern.compile("[KLM]\\d{7}[A-Z]");
    private static final Pattern CIF = Pattern.compile("[ABCDEFGHJNPQRSUVW]\\d{7}[0-9A-J]");
    private static final String PERSONAL_CONTROL = "TRWAGMYFPDXBNJZSQVHLCKE";
    private static final String CIF_CONTROL = "JABCDEFGHI";

    private SpanishTaxId() {
    }

    // Normaliza separadores habituales sin validar todavia el digito de control.
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

    // Normaliza y valida el digito de control de un NIF, NIE o CIF espanol.
    public static String validate(String value) {
        var normalized = normalize(value);
        if (DNI.matcher(normalized).matches()) {
            validatePersonal(normalized, normalized.substring(0, 8));
        } else if (NIE.matcher(normalized).matches()) {
            validatePersonal(
                    normalized,
                    "XYZ".indexOf(normalized.charAt(0)) + normalized.substring(1, 8));
        } else if (SPECIAL_NIF.matcher(normalized).matches()) {
            validatePersonal(normalized, "0" + normalized.substring(1, 8));
        } else if (CIF.matcher(normalized).matches()) {
            validateCif(normalized);
        } else {
            throw new IllegalArgumentException(
                    "El NIF no corresponde a un NIF, NIE o CIF espanol");
        }
        return normalized;
    }

    private static void validatePersonal(String value, String digits) {
        var expected = PERSONAL_CONTROL.charAt(Integer.parseInt(digits) % 23);
        if (value.charAt(8) != expected) {
            throw invalidControl();
        }
    }

    private static void validateCif(String value) {
        var digits = value.substring(1, 8);
        var sum = 0;
        for (var index = 0; index < digits.length(); index++) {
            var digit = digits.charAt(index) - '0';
            if (index % 2 == 0) {
                var doubled = digit * 2;
                sum += doubled / 10 + doubled % 10;
            } else {
                sum += digit;
            }
        }
        var control = (10 - sum % 10) % 10;
        var actual = value.charAt(8);
        var prefix = value.charAt(0);
        var valid = switch (prefix) {
            case 'A', 'B', 'E', 'H' -> actual == Character.forDigit(control, 10);
            case 'P', 'Q', 'S' -> actual == CIF_CONTROL.charAt(control);
            default -> actual == Character.forDigit(control, 10)
                    || actual == CIF_CONTROL.charAt(control);
        };
        if (!valid) {
            throw invalidControl();
        }
    }

    private static IllegalArgumentException invalidControl() {
        return new IllegalArgumentException("El NIF tiene un digito de control invalido");
    }
}
