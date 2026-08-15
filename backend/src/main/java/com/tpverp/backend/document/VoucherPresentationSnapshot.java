package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.List;

public record VoucherPresentationSnapshot(
        int schemaVersion,
        String observations,
        InvoicePresentationSnapshot.TemplateReference template,
        InvoicePresentationSnapshot.LogoReference logo,
        String terminalName,
        List<TraceEntry> traceability,
        Boolean showStoreName) {

    public VoucherPresentationSnapshot {
        if (schemaVersion < 1 || schemaVersion > 2) {
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

    public VoucherPresentationSnapshot(
            int schemaVersion,
            String observations,
            InvoicePresentationSnapshot.TemplateReference template,
            InvoicePresentationSnapshot.LogoReference logo,
            String terminalName,
            List<TraceEntry> traceability) {
        this(schemaVersion, observations, template, logo, terminalName,
                traceability, Boolean.TRUE);
    }

    public boolean shouldShowStoreName() {
        return showStoreName == null || showStoreName;
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
