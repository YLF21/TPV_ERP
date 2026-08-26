package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Safe, read-only metadata for a fiscal export. The generated XML is never
 * included in this projection; it is available only through the controlled
 * export operation.
 */
public record FiscalExportHistoryView(
        UUID exportId,
        UUID companyId,
        UUID installationId,
        FiscalExportKind kind,
        Instant exportedAt,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        long recordCount,
        UUID eventId,
        String contentHash) {

    public static FiscalExportHistoryView from(FiscalExport export) {
        return new FiscalExportHistoryView(
                export.getId(), export.getCompanyId(), export.getInstallationId(),
                export.getKind(), export.getExportedAt(), export.getPeriodStart(),
                export.getPeriodEnd(), export.getRecordCount(), export.getEventId(),
                export.getContentHash());
    }
}
