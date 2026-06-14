package com.tpverp.backend.verifactu;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record FiscalRecordCommand(
        UUID companyId,
        UUID installationId,
        UUID storeId,
        UUID documentId,
        FiscalRecordOperation operation,
        FiscalDocumentType documentType,
        Map<String, Object> snapshot,
        String formatVersion,
        String algorithmVersion,
        String applicationVersion) {

    // Normaliza y valida el contenido antes de iniciar cualquier escritura fiscal.
    public FiscalRecordCommand {
        companyId = required(companyId, "companyId");
        installationId = required(installationId, "installationId");
        storeId = required(storeId, "storeId");
        documentId = Objects.requireNonNull(documentId, "documentId");
        operation = required(operation, "operation");
        documentType = required(documentType, "documentType");
        snapshot = ImmutableJson.copy(required(snapshot, "snapshot"));
        formatVersion = requiredText(formatVersion, "formatVersion");
        algorithmVersion = requiredText(algorithmVersion, "algorithmVersion");
        applicationVersion = requiredText(applicationVersion, "applicationVersion");
    }

    private static String requiredText(String value, String field) {
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
