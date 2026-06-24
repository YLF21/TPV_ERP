package com.tpverp.backend.verifactu;

public record VerifactuSubmissionProperties(
        VerifactuEndpointMode mode,
        String systemName,
        String systemId) {

    // Normaliza los parametros necesarios para preparar el envio certificado a AEAT.
    public VerifactuSubmissionProperties {
        if (mode == null) {
            throw new IllegalArgumentException("modo VERI*FACTU obligatorio");
        }
        systemName = required(systemName, "nombre de sistema");
        systemId = required(systemId, "id de sistema");
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " obligatorio");
        }
        return normalized;
    }
}
