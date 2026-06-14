package com.tpverp.backend.organization;

import java.util.Set;

public final class StoreFiscalIdentity {

    private static final Set<String> TIMEZONES = Set.of("Atlantic/Canary", "Europe/Madrid");

    private StoreFiscalIdentity() {
    }

    public static String code(String value) {
        if (value == null || !value.matches("\\d{3}") || "000".equals(value)) {
            throw new IllegalArgumentException("El codigo fiscal debe estar entre 001 y 999");
        }
        return value;
    }

    public static String timezone(String value) {
        if (!TIMEZONES.contains(value)) {
            throw new IllegalArgumentException("La zona horaria fiscal no es valida");
        }
        return value;
    }
}
