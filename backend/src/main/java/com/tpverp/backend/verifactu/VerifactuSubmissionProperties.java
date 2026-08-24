package com.tpverp.backend.verifactu;

public record VerifactuSubmissionProperties(
        VerifactuEndpointMode mode,
        String systemName,
        String systemId,
        String producerName,
        String producerTaxId,
        String systemVersion) {

    /** Compatibility constructor for focused transport tests. */
    public VerifactuSubmissionProperties(
            VerifactuEndpointMode mode, String systemName, String systemId) {
        this(mode, systemName, systemId, "TPV ERP", "B00000000", "4.1.0");
    }

    // Normaliza los parametros necesarios para preparar el envio certificado a AEAT.
    public VerifactuSubmissionProperties {
        if (mode == null) {
            throw new IllegalArgumentException("modo VERI*FACTU obligatorio");
        }
        systemName = required(systemName, "nombre de sistema");
        systemId = required(systemId, "id de sistema");
        producerName = required(producerName, "nombre del productor");
        producerTaxId = required(producerTaxId, "NIF del productor");
        systemVersion = required(systemVersion, "version del sistema");
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " obligatorio");
        }
        return normalized;
    }
}
