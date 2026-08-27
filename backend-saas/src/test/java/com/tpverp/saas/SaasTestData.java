package com.tpverp.saas;

import java.util.Map;

public final class SaasTestData {

    private static final String CIF_CONTROL = "JABCDEFGHI";

    private SaasTestData() {
    }

    public static Map<String, String> fiscalAddress() {
        return Map.of(
                "linea1", "Calle Pruebas 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }

    /** Conserva el prefijo y los siete digitos de un CIF semilla y calcula su control. */
    public static String validCif(String seed) {
        String normalized = seed.replace("-", "").replace(" ", "").toUpperCase();
        if (normalized.length() != 9 || !normalized.substring(1, 8).matches("\\d{7}")) {
            throw new IllegalArgumentException("Semilla CIF no valida: " + seed);
        }
        int sum = 0;
        String digits = normalized.substring(1, 8);
        for (int index = 0; index < digits.length(); index++) {
            int digit = digits.charAt(index) - '0';
            if (index % 2 == 0) {
                int doubled = digit * 2;
                sum += doubled / 10 + doubled % 10;
            } else {
                sum += digit;
            }
        }
        int control = (10 - sum % 10) % 10;
        char prefix = normalized.charAt(0);
        char suffix = switch (prefix) {
            case 'P', 'Q', 'S' -> CIF_CONTROL.charAt(control);
            default -> Character.forDigit(control, 10);
        };
        return prefix + digits + suffix;
    }
}
