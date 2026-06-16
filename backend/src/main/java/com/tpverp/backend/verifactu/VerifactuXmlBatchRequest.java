package com.tpverp.backend.verifactu;

import java.util.List;

public record VerifactuXmlBatchRequest(
        String issuerName,
        String issuerTaxId,
        List<FiscalRecord> records,
        VerifactuSystemInfo systemInfo) {

    // Agrupa la cabecera y los registros fiscales que se enviaran en una misma peticion.
    public VerifactuXmlBatchRequest {
        issuerName = required(issuerName, "nombre del emisor");
        issuerTaxId = required(issuerTaxId, "NIF del emisor");
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("Debe existir al menos un registro fiscal");
        }
        records = List.copyOf(records);
        systemInfo = required(systemInfo, "systemInfo");
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value;
    }
}
