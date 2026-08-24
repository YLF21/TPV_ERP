package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.time.OffsetDateTime;

public record FiscalExportRequest(
        @NotNull FiscalExportKind kind,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd) {

    public FiscalExportRequest(FiscalExportKind kind) {
        this(kind, null, null);
    }

    @AssertTrue(message = "El periodo de exportacion debe incluir inicio y fin, en ese orden")
    public boolean hasValidPeriod() {
        return (periodStart == null && periodEnd == null)
                || (periodStart != null && periodEnd != null
                        && !periodEnd.isBefore(periodStart));
    }
}
