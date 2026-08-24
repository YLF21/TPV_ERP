package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record FiscalRequiredSubmissionExportRequest(
        @NotNull FiscalExportKind kind,
        @NotNull OffsetDateTime periodStart,
        @NotNull OffsetDateTime periodEnd) {

    @AssertTrue(message = "El periodo del requerimiento debe terminar despues de su inicio")
    public boolean hasValidPeriod() {
        return periodStart != null && periodEnd != null && !periodEnd.isBefore(periodStart);
    }
}
