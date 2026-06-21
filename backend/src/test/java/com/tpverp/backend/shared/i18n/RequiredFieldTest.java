package com.tpverp.backend.shared.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RequiredFieldTest {

    @Test
    void mapsRequiredFieldsUsedByBackendRequests() {
        assertField("loginRequest", "userName", "LOGIN_USERNAME");
        assertField("configureBackupRequest", "directory", "BACKUP_DIRECTORY");
        assertField("customerRequest", "documentNumber", "DOCUMENT_NUMBER");
        assertField("supplierRequest", "legalName", "SUPPLIER_LEGAL_NAME");
        assertField("adjustmentRequest", "warehouseId", "WAREHOUSE");
        assertField("adminEditRequest", "lineas", "DOCUMENT_LINES");
        assertField("documentRequest", "lineas", "DOCUMENT_LINES");
        assertField("lineRequest", "precioUnitario", "UNIT_PRICE");
        assertField("item", "metodoPagoId", "PAYMENT_ITEM_METHOD");
        assertField("consumeVoucherRequest", "reason", "REASON");
        assertField("fiscalCorrectionRequest", "reason", "REASON");
    }

    private static void assertField(String objectName, String field, String expectedKey) {
        assertEquals(expectedKey, RequiredField.from(objectName, field).map(Enum::name).orElse(null));
    }
}
