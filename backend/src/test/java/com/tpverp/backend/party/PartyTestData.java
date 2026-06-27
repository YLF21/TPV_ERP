package com.tpverp.backend.party;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

final class PartyTestData {

    private PartyTestData() {
    }

    static Company company() {
        return new Company("B00000000", "Company", address());
    }

    static Store store(Company company) {
        return new Store(company, "Store", address(), UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    static UUID id(Company company) {
        try {
            Field field = Company.class.getDeclaredField("id");
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
