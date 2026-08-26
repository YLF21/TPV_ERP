package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FiscalExportView(
        UUID exportId,
        FiscalExportKind kind,
        Instant exportedAt,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        long recordCount,
        UUID eventId,
        List<String> xml,
        String batchXml,
        String contentHash,
        UUID companyId,
        UUID storeId,
        UUID installationId,
        List<FiscalExportRecordView> records) {

    public FiscalExportView {
        xml = xml == null ? List.of() : List.copyOf(xml);
        records = records == null ? List.of() : List.copyOf(records);
    }

    public FiscalExportView(
            UUID exportId,
            FiscalExportKind kind,
            Instant exportedAt,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            long recordCount,
            UUID eventId,
            List<String> xml) {
        this(exportId, kind, exportedAt, periodStart, periodEnd, recordCount, eventId, xml, null, null,
                null, null, null, List.of());
    }

    /** Compatibility constructor for callers that did not expose the persisted content hash. */
    public FiscalExportView(
            UUID exportId,
            FiscalExportKind kind,
            Instant exportedAt,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            long recordCount,
            UUID eventId,
            List<String> xml,
            String batchXml) {
        this(exportId, kind, exportedAt, periodStart, periodEnd, recordCount, eventId, xml, batchXml, null,
                null, null, null, List.of());
    }

    /** Compatibility constructor for persisted exports with a content hash only. */
    public FiscalExportView(
            UUID exportId, FiscalExportKind kind, Instant exportedAt,
            OffsetDateTime periodStart, OffsetDateTime periodEnd, long recordCount,
            UUID eventId, List<String> xml, String batchXml, String contentHash) {
        this(exportId, kind, exportedAt, periodStart, periodEnd, recordCount, eventId, xml, batchXml,
                contentHash, null, null, null, List.of());
    }
}
