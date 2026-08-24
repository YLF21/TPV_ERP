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
        String batchXml) {

    public FiscalExportView(
            UUID exportId,
            FiscalExportKind kind,
            Instant exportedAt,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            long recordCount,
            UUID eventId,
            List<String> xml) {
        this(exportId, kind, exportedAt, periodStart, periodEnd, recordCount, eventId, xml, null);
    }
}
