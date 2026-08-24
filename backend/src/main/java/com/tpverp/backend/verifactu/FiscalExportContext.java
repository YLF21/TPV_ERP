package com.tpverp.backend.verifactu;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Frozen period boundaries embedded in a type 08 or 09 event export. */
public record FiscalExportContext(
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        BillingBoundary firstBilling,
        BillingBoundary lastBilling,
        EventBoundary firstEvent,
        EventBoundary lastEvent) {

    public FiscalExportContext {
        if (periodStart != null && periodEnd != null && periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("El periodo de exportacion no puede invertirse");
        }
        if ((firstBilling == null) != (lastBilling == null)) {
            throw new IllegalArgumentException("Los limites de facturacion deben venir completos");
        }
        if ((firstEvent == null) != (lastEvent == null)) {
            throw new IllegalArgumentException("Los limites de eventos deben venir completos");
        }
    }

    public static FiscalExportContext empty() {
        return new FiscalExportContext(null, null, null, null, null, null);
    }

    public record BillingBoundary(
            String issuerTaxId,
            String number,
            LocalDate issueDate,
            String hash) {
        public BillingBoundary {
            issuerTaxId = required(issuerTaxId, "NIF emisor");
            number = required(number, "numero factura");
            issueDate = Objects.requireNonNull(issueDate, "fecha factura");
            hash = required(hash, "huella factura");
        }
    }

    public record EventBoundary(
            String type,
            OffsetDateTime generatedAt,
            String hash) {
        public EventBoundary {
            type = required(type, "tipo evento");
            generatedAt = Objects.requireNonNull(generatedAt, "fecha evento");
            hash = required(hash, "huella evento");
        }
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }
}
