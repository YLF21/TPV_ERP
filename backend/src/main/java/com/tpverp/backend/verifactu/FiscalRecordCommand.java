package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
        String number,
        LocalDate issueDate,
        OffsetDateTime generatedAt,
        BigDecimal totalTax,
        BigDecimal totalAmount,
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
        number = requiredText(number, "number");
        issueDate = required(issueDate, "issueDate");
        generatedAt = required(generatedAt, "generatedAt");
        snapshot = ImmutableJson.copy(required(snapshot, "snapshot"));
        formatVersion = requiredText(formatVersion, "formatVersion");
        algorithmVersion = requiredText(algorithmVersion, "algorithmVersion");
        applicationVersion = requiredText(applicationVersion, "applicationVersion");
        validateAmounts(operation, totalTax, totalAmount);
    }

    private static void validateAmounts(
            FiscalRecordOperation operation,
            BigDecimal totalTax,
            BigDecimal totalAmount) {
        var alta = operation == FiscalRecordOperation.ALTA;
        if (alta && (totalTax == null || totalAmount == null)) {
            throw new IllegalArgumentException("Los importes son obligatorios para un alta");
        }
        if (!alta && (totalTax != null || totalAmount != null)) {
            throw new IllegalArgumentException("Una anulacion no puede contener importes");
        }
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
