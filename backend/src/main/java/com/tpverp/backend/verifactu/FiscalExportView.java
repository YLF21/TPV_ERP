package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FiscalExportView(
        UUID exportId,
        FiscalExportKind kind,
        Instant exportedAt,
        long recordCount,
        UUID eventId,
        List<String> xml) {
}
