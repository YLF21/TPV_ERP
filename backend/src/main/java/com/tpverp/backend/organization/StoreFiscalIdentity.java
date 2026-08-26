package com.tpverp.backend.organization;

import java.time.DateTimeException;
import java.time.ZoneId;

public final class StoreFiscalIdentity {

    private StoreFiscalIdentity() {
    }

    public static String code(String value) {
        if (value == null || !value.matches("\\d{3}") || "000".equals(value)) {
            throw new IllegalArgumentException("El codigo fiscal debe estar entre 001 y 999");
        }
        return value;
    }

    public static String timezone(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("La zona horaria fiscal no es valida");
        }
        try {
            return ZoneId.of(value.trim()).getId();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("La zona horaria fiscal no es valida", exception);
        }
    }
}
