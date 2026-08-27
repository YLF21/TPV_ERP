package com.tpverp.backend.sync;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncOutboxService {

    private final SyncOutboxEventRepository repository;
    private final Clock clock;

    public SyncOutboxService(SyncOutboxEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public SyncOutboxEvent enqueue(SyncOutboundEventCommand command) {
        var event = new SyncOutboxEvent(
                command.companyId(),
                command.storeId(),
                command.terminalId(),
                command.storeSequence(),
                command.entityType(),
                command.entityId(),
                command.operation(),
                command.payload(),
                clock.instant());
        repository.save(event);
        return event;
    }

    @Transactional(readOnly = true)
    public List<SyncOutboxEvent> pending() {
        return repository.findByStatusOrderByCreatedAtAsc(SyncOutboxStatus.PENDIENTE);
    }

    @Transactional
    public SyncOutboxEvent save(SyncOutboxEvent event) {
        return repository.save(event);
    }

    @Transactional(readOnly = true)
    public Optional<SyncOutboxEvent> latest(
            UUID companyId, UUID storeId, String entityType, UUID entityId) {
        return repository.findTopByCompanyIdAndStoreIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
                companyId, storeId, entityType, entityId);
    }

    @Transactional
    public List<SyncOutboxEvent> claimBatch(int batchSize, Duration claimTimeout) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize debe estar entre 1 y 1000");
        }
        if (claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()) {
            throw new IllegalArgumentException("claimTimeout debe ser positivo");
        }

        InstantWindow window = InstantWindow.from(clock, claimTimeout);
        List<SyncOutboxEvent> events = repository.findClaimableForUpdate(
                window.now(), window.staleBefore(), batchSize);
        events.forEach(event -> event.claim(UUID.randomUUID(), window.now()));
        return List.copyOf(events);
    }

    @Transactional
    public boolean markSent(UUID eventId, UUID claimToken) {
        var event = repository.findLockedByEventId(eventId).orElse(null);
        return event != null && event.markSent(claimToken, clock.instant());
    }

    @Transactional
    public boolean markFailed(
            UUID eventId,
            UUID claimToken,
            String error,
            int maxAttempts,
            Duration initialBackoff,
            Duration maximumBackoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts debe ser positivo");
        }
        validateBackoff(initialBackoff, maximumBackoff);

        var event = repository.findLockedByEventId(eventId).orElse(null);
        if (event == null || event.getStatus() != SyncOutboxStatus.ENVIANDO) {
            return false;
        }

        var now = clock.instant();
        if (event.getAttempts() >= maxAttempts) {
            return event.markDeadLetter(claimToken, error, now);
        }
        var delay = exponentialBackoff(event.getAttempts(), initialBackoff, maximumBackoff);
        return event.markRetry(claimToken, error, now.plus(delay), now);
    }

    private static void validateBackoff(Duration initialBackoff, Duration maximumBackoff) {
        if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff debe ser positivo");
        }
        if (maximumBackoff == null
                || maximumBackoff.isZero()
                || maximumBackoff.isNegative()
                || maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff debe ser igual o mayor que initialBackoff");
        }
    }

    private static Duration exponentialBackoff(
            int attempts, Duration initialBackoff, Duration maximumBackoff) {
        long delayMillis = initialBackoff.toMillis();
        long maximumMillis = maximumBackoff.toMillis();
        for (int attempt = 1; attempt < attempts && delayMillis < maximumMillis; attempt++) {
            delayMillis = delayMillis > maximumMillis / 2
                    ? maximumMillis
                    : Math.min(maximumMillis, delayMillis * 2);
        }
        return Duration.ofMillis(delayMillis);
    }

    private record InstantWindow(java.time.Instant now, java.time.Instant staleBefore) {
        private static InstantWindow from(Clock clock, Duration claimTimeout) {
            var now = clock.instant();
            return new InstantWindow(now, now.minus(claimTimeout));
        }
    }
}
