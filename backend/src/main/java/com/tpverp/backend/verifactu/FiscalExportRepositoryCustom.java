package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;

interface FiscalExportRepositoryCustom {
    List<FiscalExportHistoryView> findLegacyHistoryPage(
            UUID companyId, UUID installationId, int limit);

    List<FiscalExportHistoryView> findHistoryCursorPage(
            UUID companyId, UUID installationId, FiscalHistoryReadCursor cursor, int limit);
}
