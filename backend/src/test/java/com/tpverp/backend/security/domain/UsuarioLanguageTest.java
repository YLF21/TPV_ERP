package com.tpverp.backend.security.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.shared.i18n.SupportedLanguage;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsuarioLanguageTest {

    @Test
    void usesSpanishByDefaultAndAllowsChangingLanguage() {
        var store = store();
        var user = new Usuario(store, "USER", "hash", new Rol(store, "VENTAS"));

        assertEquals(SupportedLanguage.ES, user.getIdioma());

        user.cambiarIdioma(SupportedLanguage.EN);
        assertEquals(SupportedLanguage.EN, user.getIdioma());

        user.cambiarIdioma(SupportedLanguage.ZH);
        assertEquals(SupportedLanguage.ZH, user.getIdioma());
    }

    private static Tienda store() {
        var company = new Empresa("B00000000", "Empresa", address());
        return new Tienda(company, "Tienda", address(), UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
