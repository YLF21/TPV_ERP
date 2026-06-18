package com.tpverp.backend.shared.i18n;

import java.util.Map;
import java.util.Optional;

public final class RequiredField {

    private static final Map<String, FieldKey> FIELDS = Map.of(
            "productRequest.name", FieldKey.PRODUCT_NAME,
            "productRequest.code", FieldKey.PRODUCT_CODE,
            "productRequest.barcode", FieldKey.PRODUCT_BARCODE);

    private RequiredField() {
    }

    public static Optional<FieldKey> from(String objectName, String field) {
        return Optional.ofNullable(FIELDS.get(objectName + "." + field));
    }
    // Traduce rutas de validacion Bean Validation a claves de campo mantenibles.
}
