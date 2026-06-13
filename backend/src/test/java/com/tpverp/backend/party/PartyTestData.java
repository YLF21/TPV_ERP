package com.tpverp.backend.party;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

final class PartyTestData {

    private PartyTestData() {
    }

    static Empresa company() {
        return new Empresa("B00000000", "Empresa", address());
    }

    static Tienda store(Empresa company) {
        return new Tienda(company, "Tienda", address(), UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    static UUID id(Empresa company) {
        try {
            Field field = Empresa.class.getDeclaredField("id");
            field.setAccessible(true);
            return (UUID) field.get(company);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
    }
}
