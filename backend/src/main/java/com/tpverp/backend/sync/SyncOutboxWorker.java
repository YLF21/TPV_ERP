package com.tpverp.backend.sync;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class SyncOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncOutboxWorker.class);
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_MAX_ATTEMPTS = 10;
    private static final Duration DEFAULT_CLAIM_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(5);
    private static final Duration DEFAULT_MAXIMUM_BACKOFF = Duration.ofMinutes(15);

    private final SyncOutboxService outbox;
    private final SyncEventSender sender;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration claimTimeout;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;

    @Autowired
    public SyncOutboxWorker(
            SyncOutboxService outbox,
            SyncEventSender sender,
            Clock clock,
            Environment environment) {
        this(
                outbox,
                sender,
                property(environment, "tpv.sync.outbox.batch-size", DEFAULT_BATCH_SIZE),
                property(environment, "tpv.sync.outbox.max-attempts", DEFAULT_MAX_ATTEMPTS),
                Duration.ofMillis(property(
                        environment,
                        "tpv.sync.outbox.claim-timeout-ms",
                        DEFAULT_CLAIM_TIMEOUT.toMillis())),
                Duration.ofMillis(property(
                        environment,
                        "tpv.sync.outbox.initial-backoff-ms",
                        DEFAULT_INITIAL_BACKOFF.toMillis())),
                Duration.ofMillis(property(
                        environment,
                        "tpv.sync.outbox.maximum-backoff-ms",
                        DEFAULT_MAXIMUM_BACKOFF.toMillis())));
    }

    /** Compatibilidad para construccion directa fuera del contenedor Spring. */
    public SyncOutboxWorker(SyncOutboxService outbox, SyncEventSender sender, Clock clock) {
        this(
                outbox,
                sender,
                DEFAULT_BATCH_SIZE,
                DEFAULT_MAX_ATTEMPTS,
                DEFAULT_CLAIM_TIMEOUT,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAXIMUM_BACKOFF);
    }

    private SyncOutboxWorker(
            SyncOutboxService outbox,
            SyncEventSender sender,
            int batchSize,
            int maxAttempts,
            Duration claimTimeout,
            Duration initialBackoff,
            Duration maximumBackoff) {
        this.outbox = outbox;
        this.sender = sender;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.claimTimeout = claimTimeout;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
    }

    public int runOnce() {
        List<SyncOutboxEvent> claimed;
        try {
            claimed = outbox.claimBatch(batchSize, claimTimeout);
        } catch (RuntimeException exception) {
            log.warn("No se pudo reclamar el lote del outbox de sincronizacion", exception);
            return 0;
        }

        int sent = 0;
        for (var event : claimed) {
            sent += deliver(event);
        }
        return sent;
    }

    /** Procesa solo el evento solicitado por un wake post-commit. */
    public int runEvent(UUID eventId) {
        if (eventId == null) {
            return 0;
        }
        Optional<SyncOutboxEvent> claimed;
        try {
            claimed = outbox.claimEvent(eventId, claimTimeout);
        } catch (RuntimeException exception) {
            log.warn("No se pudo reclamar el evento {} del outbox", eventId, exception);
            return 0;
        }
        return claimed.map(this::deliver).orElse(0);
    }

    private int deliver(SyncOutboxEvent event) {
        try {
            sender.send(event);
            return outbox.markSent(event.getEventId(), event.getClaimToken()) ? 1 : 0;
        } catch (RuntimeException exception) {
            safelyMarkFailed(event, exception);
            return 0;
        }
    }

    private void safelyMarkFailed(SyncOutboxEvent event, RuntimeException failure) {
        try {
            outbox.markFailed(
                    event.getEventId(),
                    event.getClaimToken(),
                    errorMessage(failure),
                    maxAttempts,
                    initialBackoff,
                    maximumBackoff);
        } catch (RuntimeException persistenceFailure) {
            log.warn(
                    "No se pudo reprogramar el evento {} del outbox tras un fallo remoto",
                    event.getEventId(),
                    persistenceFailure);
        }
    }

    private static String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static int property(Environment environment, String key, int defaultValue) {
        int value = environment.getProperty(key, Integer.class, defaultValue);
        if (value < 1) {
            throw new IllegalArgumentException(key + " debe ser positivo");
        }
        return value;
    }

    private static long property(Environment environment, String key, long defaultValue) {
        long value = environment.getProperty(key, Long.class, defaultValue);
        if (value < 1) {
            throw new IllegalArgumentException(key + " debe ser positivo");
        }
        return value;
    }
}
