package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.EnumMap;

/**
 * Small, metadata-only view of the local VeriFactu operation queue.
 *
 * <p>It deliberately contains no fiscal XML, snapshot or record payload. The
 * same projection is used by the company/installation status endpoint and by
 * the global operational metrics collector.</p>
 */
public record FiscalOperationalStatusSnapshot(
        Map<FiscalSubmissionStatus, Long> backlogByStatus,
        Instant oldestPendingAt,
        Instant lastAeatSuccessAt,
        long expiredLeases) {

    public FiscalOperationalStatusSnapshot {
        var copy = new EnumMap<FiscalSubmissionStatus, Long>(FiscalSubmissionStatus.class);
        if (backlogByStatus != null) {
            backlogByStatus.forEach((status, count) -> {
                if (status != null && count != null && count >= 0L) {
                    copy.put(status, count);
                }
            });
        }
        backlogByStatus = Map.copyOf(copy);
        if (expiredLeases < 0L) {
            throw new IllegalArgumentException("Los leases expirados no pueden ser negativos");
        }
    }

    public long pendingCount() {
        return backlogByStatus.getOrDefault(FiscalSubmissionStatus.PENDIENTE, 0L)
                + backlogByStatus.getOrDefault(FiscalSubmissionStatus.ENVIANDO, 0L)
                + backlogByStatus.getOrDefault(FiscalSubmissionStatus.ENVIADO, 0L);
    }

    public long backlogCount(FiscalSubmissionStatus status) {
        return backlogByStatus.getOrDefault(Objects.requireNonNull(status), 0L);
    }
}
