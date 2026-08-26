package com.tpverp.saas.license;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Canonicaliza los datos que deben poder instalarse sin correcciones locales. */
public final class LicenseProvisioningData {

    private static final Pattern MULTIPLE_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,63}");
    private static final Pattern STORE_CODE = Pattern.compile("\\d{3}");
    private static final Pattern SPANISH_POSTAL_CODE = Pattern.compile("\\d{5}");
    private static final Pattern ISO_COUNTRY_CODE = Pattern.compile("[A-Z]{2}");
    private static final String[] ADDRESS_FIELDS = {
            "linea1", "ciudad", "codigoPostal", "provincia", "pais"
    };

    private LicenseProvisioningData() {
    }

    public static String requiredName(String value, String field, int maxLength) {
        String normalized = requiredText(value, field);
        normalized = MULTIPLE_WHITESPACE.matcher(normalized).replaceAll(" ");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " supera " + maxLength + " caracteres");
        }
        return normalized;
    }

    public static String requiredCode(String value, String field) {
        String normalized = MULTIPLE_WHITESPACE.matcher(requiredText(value, field))
                .replaceAll("-")
                .toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " solo admite letras, numeros, punto, guion y guion bajo");
        }
        return normalized;
    }

    public static String storeCode(String value) {
        String normalized = requiredText(value, "storeCode");
        if (!STORE_CODE.matcher(normalized).matches() || "000".equals(normalized)) {
            throw new IllegalArgumentException("storeCode debe estar entre 001 y 999");
        }
        return normalized;
    }

    public static String timeZoneId(String value) {
        String normalized = requiredText(value, "timeZoneId");
        try {
            return ZoneId.of(normalized).getId();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("timeZoneId no es una zona horaria valida", exception);
        }
    }

    public static String optionalText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " supera " + maxLength + " caracteres");
        }
        return normalized;
    }

    public static Map<String, String> fiscalAddress(
            Map<String, String> address, String field) {
        if (address == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        var normalized = new LinkedHashMap<String, String>();
        for (String key : ADDRESS_FIELDS) {
            normalized.put(key, requiredText(address.get(key), field + "." + key));
        }
        String countryCode = normalized.get("pais").toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRY_CODE.matcher(countryCode).matches()) {
            throw new IllegalArgumentException(
                    field + ".pais debe ser un codigo ISO alfa-2");
        }
        normalized.put("pais", countryCode);
        if ("ES".equals(countryCode)
                && !SPANISH_POSTAL_CODE.matcher(normalized.get("codigoPostal")).matches()) {
            throw new IllegalArgumentException(
                    field + ".codigoPostal debe contener 5 digitos para Espana");
        }
        address.forEach((key, value) -> {
            if (key != null && !normalized.containsKey(key) && value != null && !value.isBlank()) {
                normalized.put(key, value.trim());
            }
        });
        return Map.copyOf(normalized);
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
