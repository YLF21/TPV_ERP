package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
        String timezone,
        String issuerTaxId,
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
        timezone = validTimezone(timezone, generatedAt);
        issuerTaxId = validTaxId(issuerTaxId);
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

    private static String validTimezone(String value, OffsetDateTime generatedAt) {
        var timezone = requiredText(value, "timezone");
        try {
            var expectedOffset = ZoneId.of(timezone).getRules()
                    .getOffset(generatedAt.toInstant());
            if (!generatedAt.getOffset().equals(expectedOffset)) {
                throw new IllegalArgumentException(
                        "generatedAt y timezone tienen offsets distintos");
            }
            return timezone;
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("timezone no es valida", exception);
        }
    }

    private static String validTaxId(String value) {
        var taxId = requiredText(value, "issuerTaxId").toUpperCase();
        if (!taxId.matches("[0-9A-Z]{9}")) {
            throw new IllegalArgumentException("issuerTaxId no es valido");
        }
        return taxId;
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
