package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.List;

public record FiscalIntegrityCheckView(
        Instant checkedAt,
        FiscalMode mode,
        boolean ok,
        List<String> anomalies,
        long billingRecordsChecked,
        long eventRecordsChecked) {
}
