package com.tpverp.backend.verifactu;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Global, low-cardinality fiscal operational metrics.
 *
 * <p>No company, store, record, NIF or installation is ever a meter tag. The
 * values are refreshed from a bounded aggregate query and retain their last
 * successful sample if a later collection fails.</p>
 */
@Component
public class FiscalOperationalMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(FiscalOperationalMetrics.class);
    private static final FiscalSubmissionStatus[] BACKLOG_STATUSES = {
            FiscalSubmissionStatus.PENDIENTE,
            FiscalSubmissionStatus.ENVIANDO,
            FiscalSubmissionStatus.ENVIADO
    };

    private final FiscalOperationalStatusRepository repository;
    private final Clock clock;
    private final Duration freshnessThreshold;
    private final Counter collectionFailures;
    private final EnumMap<FiscalSubmissionStatus, AtomicLong> backlog =
            new EnumMap<>(FiscalSubmissionStatus.class);
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();
    private final AtomicLong expiredLeases = new AtomicLong();
    private final AtomicLong lastAeatSuccessEpochSeconds = new AtomicLong();
    private final AtomicLong lastSuccessfulRefreshEpochSeconds = new AtomicLong();
    private final AtomicReference<FiscalOperationalStatusSnapshot> latestSnapshot =
            new AtomicReference<>();
    private final AtomicReference<Instant> lastSuccessfulRefresh = new AtomicReference<>();
    private final AtomicReference<Instant> lastRefreshFailureAt = new AtomicReference<>();
    private volatile RuntimeException lastRefreshFailure;

    @Autowired
    public FiscalOperationalMetrics(
            FiscalOperationalStatusRepository repository,
            MeterRegistry meters,
            Clock clock,
            @Value("${tpv.verifactu.observability.stale-after-ms:180000}")
            long freshnessThresholdMs) {
        this(repository, meters, clock, Duration.ofMillis(freshnessThresholdMs));
    }

    /** Compatibility constructor for focused unit tests and small callers. */
    public FiscalOperationalMetrics(
            FiscalOperationalStatusRepository repository,
            MeterRegistry meters,
            Clock clock) {
        this(repository, meters, clock, Duration.ofMinutes(3));
    }

    public FiscalOperationalMetrics(
            FiscalOperationalStatusRepository repository,
            MeterRegistry meters,
            Clock clock,
            Duration freshnessThreshold) {
        this.repository = repository;
        this.clock = clock;
        if (freshnessThreshold.isNegative()) {
            throw new IllegalArgumentException("El umbral de frescura no puede ser negativo");
        }
        this.freshnessThreshold = freshnessThreshold;
        this.collectionFailures = Counter.builder("tpv.verifactu.observability.collection.failures")
                .description("Fallos al recoger la observabilidad operativa fiscal")
                .register(meters);
        for (var status : BACKLOG_STATUSES) {
            var value = new AtomicLong();
            backlog.put(status, value);
            Gauge.builder("tpv.verifactu.backlog", value, AtomicLong::get)
                    .description("Registros VeriFactu pendientes de transporte por estado")
                    .tag("status", status.name())
                    .register(meters);
        }
        Gauge.builder("tpv.verifactu.oldest.pending.age.seconds",
                        oldestPendingAgeSeconds, AtomicLong::get)
                .description("Edad en segundos del registro fiscal pendiente mas antiguo")
                .register(meters);
        Gauge.builder("tpv.verifactu.leases.expired", expiredLeases, AtomicLong::get)
                .description("Leases fiscales ENVIANDO expirados en la ultima muestra")
                .register(meters);
        Gauge.builder("tpv.verifactu.last.aeat.success.epoch.seconds",
                        lastAeatSuccessEpochSeconds, AtomicLong::get)
                .description("Unix timestamp del ultimo intento AEAT aceptado")
                .register(meters);
        Gauge.builder("tpv.verifactu.observability.last.success.epoch.seconds",
                        lastSuccessfulRefreshEpochSeconds, AtomicLong::get)
                .description("Unix timestamp de la ultima recogida fiscal correcta")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${tpv.verifactu.observability.initial-delay-ms:5000}",
            fixedDelayString = "${tpv.verifactu.observability.interval-ms:60000}")
    void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException exception) {
            LOGGER.warn("No se pudo actualizar la observabilidad fiscal", exception);
        }
    }

    public synchronized FiscalOperationalStatusSnapshot refresh() {
        try {
            var snapshot = repository.findGlobal();
            for (var status : BACKLOG_STATUSES) {
                backlog.get(status).set(snapshot.backlogCount(status));
            }
            var now = clock.instant();
            oldestPendingAgeSeconds.set(snapshot.oldestPendingAt() == null
                    ? 0L : Math.max(0L, Duration.between(snapshot.oldestPendingAt(), now).getSeconds()));
            expiredLeases.set(snapshot.expiredLeases());
            lastAeatSuccessEpochSeconds.set(snapshot.lastAeatSuccessAt() == null
                    ? 0L : snapshot.lastAeatSuccessAt().getEpochSecond());
            lastSuccessfulRefreshEpochSeconds.set(now.getEpochSecond());
            latestSnapshot.set(snapshot);
            lastSuccessfulRefresh.set(now);
            lastRefreshFailureAt.set(null);
            lastRefreshFailure = null;
            return snapshot;
        } catch (RuntimeException exception) {
            collectionFailures.increment();
            lastRefreshFailureAt.set(clock.instant());
            lastRefreshFailure = exception;
            throw exception;
        }
    }

    public FiscalOperationalStatusSnapshot snapshot() {
        return latestSnapshot.get();
    }

    public double collectionFailureCount() {
        return collectionFailures.count();
    }

    public Instant lastSuccessfulRefreshAt() {
        return lastSuccessfulRefresh.get();
    }

    public boolean hasRefreshFailure() {
        return lastRefreshFailure != null;
    }

    public Instant lastRefreshFailureAt() {
        return lastRefreshFailureAt.get();
    }

    public String lastRefreshFailureDescription() {
        var failure = lastRefreshFailure;
        if (failure == null) {
            return null;
        }
        var message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public boolean isSnapshotStale() {
        var refreshedAt = lastSuccessfulRefreshAt();
        return refreshedAt == null
                || !clock.instant().isBefore(refreshedAt.plus(freshnessThreshold));
    }

    public Duration freshnessThreshold() {
        return freshnessThreshold;
    }
}
