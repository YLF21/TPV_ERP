package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Parsed, fully correlated AEAT response for one immutable request batch. */
public record VerifactuBatchResponse(
        FiscalSubmissionStatus globalStatus,
        Integer waitSeconds,
        Map<UUID, Line> lines,
        String errorCode,
        String error,
        String payload,
        boolean transportFailure) {

    public VerifactuBatchResponse {
        lines = lines == null ? Map.of() : Map.copyOf(lines);
    }

    public record Line(
            UUID recordId,
            FiscalSubmissionStatus status,
            String errorCode,
            String error,
            FiscalSubmissionStatus rawStatus,
            String duplicateStatus) {
        public Line(UUID recordId, FiscalSubmissionStatus status,
                String errorCode, String error) {
            this(recordId, status, errorCode, error, status, null);
        }
    }

    public boolean validFor(List<FiscalRecord> records) {
        if (transportFailure || globalStatus == null || waitSeconds == null || records == null
                || lines.size() != records.size()) {
            return false;
        }
        boolean hasNonCorrect = false;
        boolean hasNonIncorrect = false;
        for (FiscalRecord record : records) {
            var line = lines.get(record.getId());
            if (line == null || line.status() == null) {
                return false;
            }
            var rawStatus = line.rawStatus() == null ? line.status() : line.rawStatus();
            if (globalStatus == FiscalSubmissionStatus.ACEPTADO
                    && rawStatus != FiscalSubmissionStatus.ACEPTADO) {
                return false;
            }
            hasNonCorrect |= rawStatus != FiscalSubmissionStatus.ACEPTADO;
            hasNonIncorrect |= rawStatus != FiscalSubmissionStatus.RECHAZADO;
            if (globalStatus == FiscalSubmissionStatus.RECHAZADO
                    && rawStatus != FiscalSubmissionStatus.RECHAZADO) {
                return false;
            }
        }
        return globalStatus != FiscalSubmissionStatus.ACEPTADO_CON_ERRORES
                || (hasNonCorrect && hasNonIncorrect);
    }
}
