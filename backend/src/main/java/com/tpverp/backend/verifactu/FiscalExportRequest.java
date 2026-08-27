package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

public record FiscalExportRequest(
        @NotNull FiscalExportKind kind,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        List<UUID> recordIds,
        LocalDate dateFrom,
        LocalDate dateTo,
        String documentNumber,
        FiscalRecordOperation operation,
        FiscalDocumentType documentType,
        FiscalMode fiscalMode) {

    public FiscalExportRequest(FiscalExportKind kind, OffsetDateTime periodStart,
            OffsetDateTime periodEnd) {
        this(kind, periodStart, periodEnd, List.of(), null, null, null, null, null, null);
    }

    public FiscalExportRequest(FiscalExportKind kind) {
        this(kind, null, null, List.of(), null, null, null, null, null, null);
    }

    @AssertTrue(message = "El periodo de exportacion debe incluir inicio y fin, en ese orden")
    public boolean hasValidPeriod() {
        return (periodStart == null && periodEnd == null)
                || (periodStart != null && periodEnd != null
                        && !periodEnd.isBefore(periodStart));
    }

    public List<UUID> safeRecordIds() {
        return recordIds == null ? List.of() : new ArrayList<>(recordIds);
    }

    @AssertTrue(message = "La seleccion fiscal supera el limite; use export-jobs")
    public boolean hasBoundedSelection() {
        var ids = safeRecordIds();
        return ids.size() <= 1000 && ids.stream().distinct().count() == ids.size();
    }
}
