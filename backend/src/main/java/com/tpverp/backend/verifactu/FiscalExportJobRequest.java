package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** Immutable, bounded filter contract captured in the durable export job. */
public record FiscalExportJobRequest(
        @NotNull FiscalExportKind kind,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        List<UUID> recordIds,
        LocalDate dateFrom,
        LocalDate dateTo,
        String documentNumber,
        String documentNumberPrefix,
        FiscalRecordOperation operation,
        FiscalDocumentType documentType,
        FiscalMode fiscalMode,
        FiscalExportJobScope scope) {

    public FiscalExportJobRequest {
        recordIds = recordIds == null ? List.of()
                : java.util.Collections.unmodifiableList(new ArrayList<>(recordIds));
    }

    /** Compatibility constructor for existing non-HTTP worker tests/callers. */
    public FiscalExportJobRequest(FiscalExportKind kind, OffsetDateTime periodStart,
            OffsetDateTime periodEnd, List<UUID> recordIds, LocalDate dateFrom,
            LocalDate dateTo, String documentNumber, String documentNumberPrefix,
            FiscalRecordOperation operation, FiscalDocumentType documentType,
            FiscalMode fiscalMode) {
        this(kind, periodStart, periodEnd, recordIds, dateFrom, dateTo, documentNumber,
                documentNumberPrefix, operation, documentType, fiscalMode,
                FiscalExportJobScope.CURRENT);
    }

    public FiscalExportJobRequest(FiscalExportKind kind) {
        this(kind, null, null, List.of(), null, null, null, null, null, null, null,
                FiscalExportJobScope.CURRENT);
    }

    public FiscalExportJobRequest(FiscalExportKind kind, OffsetDateTime periodStart,
            OffsetDateTime periodEnd) {
        this(kind, periodStart, periodEnd, List.of(), null, null, null, null, null, null, null,
                FiscalExportJobScope.PERIOD);
    }

    @AssertTrue(message = "El periodo de exportacion debe incluir inicio y fin, en ese orden")
    public boolean hasValidPeriod() {
        return (periodStart == null && periodEnd == null)
                || (periodStart != null && periodEnd != null
                        && !periodEnd.isBefore(periodStart));
    }

    @AssertTrue(message = "Las fechas de expedicion no son validas")
    public boolean hasValidIssueDates() {
        return dateFrom == null || dateTo == null || !dateTo.isBefore(dateFrom);
    }

    @AssertTrue(message = "Solo puede indicarse un numero exacto o un prefijo")
    public boolean hasSingleNumberFilter() {
        return documentNumber == null || documentNumberPrefix == null;
    }

    public List<UUID> safeRecordIds() {
        return recordIds == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(recordIds));
    }

    @AssertTrue(message = "El scope fiscal y sus filtros no son compatibles")
    public boolean hasValidScope() {
        if (scope == null || kind == null) return false;
        var ids = safeRecordIds();
        var hasPeriod = periodStart != null || periodEnd != null;
        var completePeriod = periodStart != null && periodEnd != null;
        var hasOther = dateFrom != null || dateTo != null || documentNumber != null
                || documentNumberPrefix != null || operation != null || documentType != null
                || fiscalMode != null;
        return switch (scope) {
            case CURRENT -> kind == FiscalExportKind.BILLING && ids.size() == 1
                    && !hasPeriod && !hasOther;
            case SELECTED -> kind == FiscalExportKind.BILLING && ids.size() >= 1 && ids.size() <= 1000
                    && !hasPeriod && !hasOther;
            case FILTERED -> kind == FiscalExportKind.BILLING && ids.isEmpty() && hasOther;
            case PERIOD -> completePeriod && ids.isEmpty() && !hasOther;
        } && (!hasPeriod || completePeriod);
    }
}
