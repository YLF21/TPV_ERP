package com.tpverp.backend.observability;

import com.tpverp.backend.verifactu.FiscalOperationalMetrics;
import com.tpverp.backend.verifactu.FiscalSubmissionStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness signal for the fiscal operational projection. A backlog or AEAT
 * rejection is business state, not an infrastructure outage and therefore
 * remains UP so that local sales are not blocked by this indicator.
 */
@Component("tpvFiscal")
public class TpvFiscalHealthIndicator implements HealthIndicator {

    private final FiscalOperationalMetrics metrics;

    public TpvFiscalHealthIndicator(FiscalOperationalMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        var snapshot = metrics.snapshot();
        var stale = metrics.isSnapshotStale();
        var failed = metrics.hasRefreshFailure();
        var usable = snapshot != null && !failed && !stale;
        var health = usable ? Health.up() : Health.down();

        health.withDetail("snapshotAvailable", snapshot != null)
                .withDetail("snapshotUsable", usable)
                .withDetail("snapshotStale", stale)
                .withDetail("refreshFailed", failed)
                .withDetail("freshnessThresholdSeconds",
                        metrics.freshnessThreshold().toSeconds())
                .withDetail("collectionFailures", metrics.collectionFailureCount());
        if (metrics.lastSuccessfulRefreshAt() != null) {
            health.withDetail("lastSuccessfulRefreshAt", metrics.lastSuccessfulRefreshAt());
        }
        if (metrics.lastRefreshFailureAt() != null) {
            health.withDetail("lastRefreshFailureAt", metrics.lastRefreshFailureAt());
        }
        if (metrics.lastRefreshFailureDescription() != null) {
            health.withDetail("lastRefreshFailure", metrics.lastRefreshFailureDescription());
        }
        if (snapshot != null) {
            health.withDetail("pendingCount", snapshot.pendingCount())
                    .withDetail("backlogByStatus", snapshot.backlogByStatus())
                    .withDetail("rejectedCount",
                            snapshot.backlogCount(FiscalSubmissionStatus.RECHAZADO))
                    .withDetail("expiredLeases", snapshot.expiredLeases());
            if (snapshot.oldestPendingAt() != null) {
                health.withDetail("oldestPendingAt", snapshot.oldestPendingAt());
            }
            if (snapshot.lastAeatSuccessAt() != null) {
                health.withDetail("lastAeatSuccessAt", snapshot.lastAeatSuccessAt());
            }
        }
        return health.build();
    }
}
