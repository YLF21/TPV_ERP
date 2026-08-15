package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.List;

public record VoucherPresentationSnapshot(
        int schemaVersion,
        String observations,
        InvoicePresentationSnapshot.TemplateReference template,
        InvoicePresentationSnapshot.LogoReference logo,
        String terminalName,
        List<TraceEntry> traceability) {

    public VoucherPresentationSnapshot {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("voucher_print_snapshot_version_invalid");
        }
        if (template == null) {
            throw new IllegalArgumentException("voucher_print_template_reference_required");
        }
        traceability = traceability == null ? List.of() : List.copyOf(traceability);
        if (traceability.isEmpty()) {
            throw new IllegalArgumentException("voucher_print_traceability_required");
        }
    }

    public record TraceEntry(
            String documentNumber,
            CommercialDocumentType documentType,
            LocalDate documentDate,
            String operation) {

        public TraceEntry {
            if (documentNumber == null || documentNumber.isBlank()) {
                throw new IllegalArgumentException("voucher_print_trace_document_required");
            }
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("voucher_print_trace_operation_required");
            }
        }
    }
}
