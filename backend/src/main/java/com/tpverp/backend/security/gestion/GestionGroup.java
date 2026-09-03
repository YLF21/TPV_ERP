package com.tpverp.backend.security.gestion;

import java.util.Locale;

public enum GestionGroup {
    FISCAL,
    SEGURIDAD,
    CONFIGURACION;

    public static GestionGroup parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El grupo de APP GESTION es obligatorio");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Grupo de APP GESTION no reconocido", exception);
        }
    }
}
