package com.tpverp.backend.observability;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("tpvBusinessHealthIndicator")
public class TpvBusinessHealthIndicator implements HealthIndicator {

    private final BusinessBacklogMonitor monitor;

    public TpvBusinessHealthIndicator(BusinessBacklogMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public Health health() {
        try {
            var snapshot = monitor.refresh();
            return Health.up()
                    .withDetail("terminalOperationsRequiringAttention", snapshot.unresolvedTerminalOperations())
                    .withDetail("overdueReceivables", snapshot.overdueReceivables())
                    .withDetail("activePromotions", snapshot.activePromotions())
                    .withDetail("refreshedAt", snapshot.refreshedAt())
                    .build();
        } catch (RuntimeException exception) {
            return Health.down(exception).build();
        }
    }
}
