package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;

interface FiscalRequiredSubmissionRepositoryCustom {
    List<FiscalRequiredSubmissionHistoryView> findLegacyHistoryPage(
            UUID companyId, UUID installationId, int limit);

    List<FiscalRequiredSubmissionHistoryView> findHistoryCursorPage(
            UUID companyId, UUID installationId, FiscalHistoryReadCursor cursor, int limit);
}
