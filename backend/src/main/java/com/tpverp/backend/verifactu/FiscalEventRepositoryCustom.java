package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;

interface FiscalEventRepositoryCustom {
    FiscalEventSummaryAggregate summarizeEvents(
            UUID companyId, UUID installationId, java.time.Instant now);

    long maxSequenceForRead(UUID companyId, UUID installationId);

    List<FiscalEventView> findCursorViewsForRead(
            UUID companyId, UUID installationId, long snapshotSequence,
            FiscalEventReadCursor cursor, int limit);
}
