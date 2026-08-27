package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

interface FiscalRecordSummaryRepositoryCustom {
    FiscalEventSummaryAggregate summarizePeriod(
            UUID companyId, UUID installationId, Instant previousSummaryAt, Instant now);
}
