package com.tpverp.backend.verifactu;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Coordinates the tenant-scoped persisted operating-time accumulator. */
@Service
public class FiscalOperatingClockService {
    static final Duration SUMMARY_THRESHOLD = Duration.ofHours(6);
    static final Duration MAX_HEARTBEAT_GAP = Duration.ofMinutes(2);

    private final FiscalOperatingClockRepository clocks;

    public FiscalOperatingClockService(FiscalOperatingClockRepository clocks) {
        this.clocks = clocks;
    }

    /** Locks and observes one heartbeat inside the caller's fiscal transaction. */
    public boolean observeAndCheckDue(UUID companyId, UUID installationId, Instant now) {
        var clock = lock(companyId, installationId, now);
        clock.observe(now, MAX_HEARTBEAT_GAP);
        clocks.save(clock);
        return clock.isDue(SUMMARY_THRESHOLD);
    }

    /** Starts a new operating period without carrying time across a NO-mode start. */
    public void reset(UUID companyId, UUID installationId, Instant now) {
        var clock = lock(companyId, installationId, now);
        clock.reset(now);
        clocks.save(clock);
    }

    private FiscalOperatingClock lock(UUID companyId, UUID installationId, Instant now) {
        clocks.insertIfMissing(UUID.randomUUID(), companyId, installationId, now);
        return clocks.findForUpdate(companyId, installationId)
                .orElseThrow(() -> new IllegalStateException("Reloj operativo fiscal no encontrado"));
    }
}
