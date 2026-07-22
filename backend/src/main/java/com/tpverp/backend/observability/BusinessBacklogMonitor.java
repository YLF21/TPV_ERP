package com.tpverp.backend.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BusinessBacklogMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessBacklogMonitor.class);
    private static final String TERMINAL_BACKLOG_SQL = """
            select count(*)
              from payment_terminal_operation
             where status in ('TIMEOUT', 'REVIEW_REQUIRED')
            """;
    private static final String OVERDUE_RECEIVABLES_SQL = """
            select count(*)
              from documento
             where tipo in ('ALBARAN_VENTA', 'FACTURA_VENTA')
               and estado in ('PENDIENTE', 'PARCIAL')
               and fecha_vencimiento < current_date
            """;
    private static final String ACTIVE_PROMOTIONS_SQL = """
            select count(*)
              from promocion
             where estado = 'ACTIVE'
               and fecha_inicio <= current_date
               and (fecha_fin is null or fecha_fin >= current_date)
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter collectionFailures;
    private final AtomicLong unresolvedTerminalOperations = new AtomicLong();
    private final AtomicLong overdueReceivables = new AtomicLong();
    private final AtomicLong activePromotions = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    public BusinessBacklogMonitor(JdbcTemplate jdbc, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.collectionFailures = Counter.builder("tpv.business.monitor.collection.failures")
                .description("Failures while collecting TPV business backlog metrics")
                .register(meters);
        backlogGauge(meters, "terminal_unresolved", unresolvedTerminalOperations);
        backlogGauge(meters, "receivables_overdue", overdueReceivables);
        backlogGauge(meters, "promotions_active", activePromotions);
        Gauge.builder("tpv.business.monitor.last_success", lastSuccessEpochSeconds, AtomicLong::get)
                .description("Unix timestamp of the last successful business metric collection")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${tpv.monitoring.business.initial-delay-ms:5000}",
            fixedDelayString = "${tpv.monitoring.business.interval-ms:30000}")
    void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not refresh TPV business backlog metrics", exception);
        }
    }

    public synchronized Snapshot refresh() {
        try {
            unresolvedTerminalOperations.set(count(TERMINAL_BACKLOG_SQL));
            overdueReceivables.set(count(OVERDUE_RECEIVABLES_SQL));
            activePromotions.set(count(ACTIVE_PROMOTIONS_SQL));
            var refreshedAt = Instant.now(clock);
            lastSuccessEpochSeconds.set(refreshedAt.getEpochSecond());
            return snapshot(refreshedAt);
        } catch (RuntimeException exception) {
            collectionFailures.increment();
            throw exception;
        }
    }

    public Snapshot snapshot() {
        var epochSeconds = lastSuccessEpochSeconds.get();
        return snapshot(epochSeconds == 0L ? null : Instant.ofEpochSecond(epochSeconds));
    }

    private Snapshot snapshot(Instant refreshedAt) {
        return new Snapshot(
                unresolvedTerminalOperations.get(),
                overdueReceivables.get(),
                activePromotions.get(),
                refreshedAt);
    }

    private long count(String sql) {
        var value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private static void backlogGauge(MeterRegistry meters, String kind, AtomicLong value) {
        Gauge.builder("tpv.business.backlog", value, AtomicLong::get)
                .description("Current TPV operational backlog")
                .tag("kind", kind)
                .register(meters);
    }

    public record Snapshot(
            long unresolvedTerminalOperations,
            long overdueReceivables,
            long activePromotions,
            Instant refreshedAt) {
    }
}
