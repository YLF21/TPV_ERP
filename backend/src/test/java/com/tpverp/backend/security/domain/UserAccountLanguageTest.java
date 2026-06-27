package com.tpverp.backend.security.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.shared.i18n.SupportedLanguage;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserAccountLanguageTest {

    @Test
    void usesSpanishByDefaultAndAllowsChangingLanguage() {
        var store = store();
        var user = new UserAccount(store, "USER", "hash", new Role(store, "VENTAS"));

        assertEquals(SupportedLanguage.ES, user.getIdioma());

        user.cambiarIdioma(SupportedLanguage.EN);
        assertEquals(SupportedLanguage.EN, user.getIdioma());

        user.cambiarIdioma(SupportedLanguage.ZH);
        assertEquals(SupportedLanguage.ZH, user.getIdioma());
    }

    private static Store store() {
        var company = new Company("B00000000", "Company", address());
        return new Store(company, "Store", address(), UUID.randomUUID().toString(),
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
