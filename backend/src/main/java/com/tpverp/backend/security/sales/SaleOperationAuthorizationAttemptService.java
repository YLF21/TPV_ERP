package com.tpverp.backend.security.sales;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleOperationAuthorizationAttemptService {

    public static final String AUDIT_FAILED =
            "SALE_OPERATION_AUTHORIZATION_FAILED";
    public static final String AUDIT_THROTTLED =
            "SALE_OPERATION_AUTHORIZATION_THROTTLED";

    static final Duration FAILURE_WINDOW = Duration.ofMinutes(30);
    static final Duration RESERVATION_TTL = Duration.ofSeconds(30);
    private static final long[] COOLDOWN_SECONDS = {
        0, 0, 5, 15, 30, 60, 120, 300, 600, 900
    };

    private final SaleOperationAuthorizationAttemptRepository attempts;
    private final AuditService audit;
    private final Clock clock;

    public SaleOperationAuthorizationAttemptService(
            SaleOperationAuthorizationAttemptRepository attempts,
            AuditService audit,
            Clock clock) {
        this.attempts = attempts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(
            Context context,
            String requestedAuthorizerUsername) {
        Objects.requireNonNull(context, "context");
        var now = clock.instant();
        var attempt = lockedAttempt(context, now);
        if (attempt.isBlockedAt(now)) {
            throw throttled(
                    context,
                    requestedAuthorizerUsername,
                    block(attempt, now));
        }
        if (attempt.hasActiveReservationAt(now)) {
            var block = new Block(
                    attempt.getConsecutiveFailures(),
                    attempt.getReservationUntil(),
                    retryAfterSeconds(now, attempt.getReservationUntil()));
            throw throttled(context, requestedAuthorizerUsername, block);
        }
        var token = UUID.randomUUID();
        attempt.reserve(token, now, RESERVATION_TTL);
        attempts.saveAndFlush(attempt);
        return new Reservation(token, now.plus(RESERVATION_TTL));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Failure recordFailure(
            Context context,
            Reservation reservation,
            String requestedAuthorizerUsername) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(reservation, "reservation");
        var now = clock.instant();
        var attempt = attempts.findByScopeForUpdate(
                        context.storeId(),
                        context.operatorId(),
                        context.terminalId(),
                        context.operationCode())
                .orElseThrow(() -> staleReservation(
                        context, requestedAuthorizerUsername, now, null));
        requireReservationOwner(
                attempt, reservation, context, requestedAuthorizerUsername, now);
        var nextCount = nextFailureCount(attempt, now);
        var failure = attempt.registerFailure(
                reservation.token(),
                now,
                FAILURE_WINDOW,
                cooldown(nextCount));
        attempts.saveAndFlush(attempt);
        var result = failure.blockedUntil() == null
                ? new Failure(failure.consecutiveFailures(), null, 0)
                : new Failure(
                        failure.consecutiveFailures(),
                        failure.blockedUntil(),
                        retryAfterSeconds(now, failure.blockedUntil()));
        audit.record(
                AUDIT_FAILED,
                AuditResult.FALLO,
                auditDetails(context, requestedAuthorizerUsername, result));
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Context context, Reservation reservation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(reservation, "reservation");
        var now = clock.instant();
        var attempt = attempts.findByScopeForUpdate(
                        context.storeId(),
                        context.operatorId(),
                        context.terminalId(),
                        context.operationCode())
                .orElseThrow(() -> staleReservation(context, null, now, null));
        requireReservationOwner(attempt, reservation, context, null, now);
        attempts.delete(attempt);
        attempts.flush();
    }

    private SaleOperationAuthorizationAttempt lockedAttempt(
            Context context,
            Instant now) {
        ensureExists(context, now);
        var existing = attempts.findByScopeForUpdate(
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        // A successful concurrent request may delete the row between the
        // upsert and the lock. Recreate it once in the same transaction.
        ensureExists(context, now);
        return attempts.findByScopeForUpdate(
                        context.storeId(),
                        context.operatorId(),
                        context.terminalId(),
                        context.operationCode())
                .orElseThrow(() -> new IllegalStateException(
                        "sale_operation_authorization_attempt_state_unavailable"));
    }

    private void ensureExists(Context context, Instant now) {
        attempts.ensureExists(
                UUID.randomUUID(),
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode().name(),
                now);
    }

    private void requireReservationOwner(
            SaleOperationAuthorizationAttempt attempt,
            Reservation reservation,
            Context context,
            String requestedAuthorizerUsername,
            Instant now) {
        if (!attempt.ownsReservation(reservation.token())
                || !attempt.hasActiveReservationAt(now)
                || !reservation.expiresAt().isAfter(now)) {
            throw staleReservation(
                    context, requestedAuthorizerUsername, now, attempt);
        }
    }

    private SaleOperationAuthorizationThrottledException staleReservation(
            Context context,
            String requestedAuthorizerUsername,
            Instant now,
            SaleOperationAuthorizationAttempt attempt) {
        var until = attempt != null
                        && attempt.getReservationUntil() != null
                        && attempt.getReservationUntil().isAfter(now)
                ? attempt.getReservationUntil()
                : now.plusSeconds(1);
        var block = new Block(
                attempt == null ? 0 : attempt.getConsecutiveFailures(),
                until,
                retryAfterSeconds(now, until));
        return throttled(context, requestedAuthorizerUsername, block);
    }

    private SaleOperationAuthorizationThrottledException throttled(
            Context context,
            String requestedAuthorizerUsername,
            Block block) {
        audit.record(
                AUDIT_THROTTLED,
                AuditResult.FALLO,
                auditDetails(context, requestedAuthorizerUsername, block));
        return new SaleOperationAuthorizationThrottledException(
                block.blockedUntil(), block.retryAfterSeconds());
    }

    private static int nextFailureCount(
            SaleOperationAuthorizationAttempt attempt,
            Instant now) {
        var lastFailureAt = attempt.getLastFailureAt();
        if (lastFailureAt == null
                || !lastFailureAt.plus(FAILURE_WINDOW).isAfter(now)) {
            return 1;
        }
        return attempt.getConsecutiveFailures() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : attempt.getConsecutiveFailures() + 1;
    }

    private static Duration cooldown(int consecutiveFailures) {
        var index = Math.min(
                Math.max(consecutiveFailures, 1),
                COOLDOWN_SECONDS.length) - 1;
        return Duration.ofSeconds(COOLDOWN_SECONDS[index]);
    }

    private static Block block(
            SaleOperationAuthorizationAttempt attempt,
            Instant now) {
        return new Block(
                attempt.getConsecutiveFailures(),
                attempt.getBlockedUntil(),
                retryAfterSeconds(now, attempt.getBlockedUntil()));
    }

    private static long retryAfterSeconds(Instant now, Instant blockedUntil) {
        var remainingMillis = Duration.between(now, blockedUntil).toMillis();
        return Math.max(1, (remainingMillis + 999) / 1_000);
    }

    private static Map<String, Object> auditDetails(
            Context context,
            String requestedAuthorizerUsername,
            AttemptState state) {
        var details = new LinkedHashMap<String, Object>();
        details.put("storeId", context.storeId().toString());
        details.put("operatorId", context.operatorId().toString());
        details.put("operatorUsername", context.operatorUsername());
        details.put("terminalId", context.terminalId().toString());
        details.put("operationCode", context.operationCode().name());
        details.put("delegatedAttempt",
                requestedAuthorizerUsername != null
                        && !requestedAuthorizerUsername.isBlank());
        normalizedAuditUsername(requestedAuthorizerUsername).ifPresent(
                value -> details.put("requestedAuthorizerUsername", value));
        details.put("consecutiveFailures", state.consecutiveFailures());
        details.put("retryAfterSeconds", state.retryAfterSeconds());
        if (state.blockedUntil() != null) {
            details.put("blockedUntil", state.blockedUntil().toString());
        }
        return Map.copyOf(details);
    }

    private static Optional<String> normalizedAuditUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        var normalized = username.trim().toUpperCase(Locale.ROOT);
        return Optional.of(normalized.length() <= 128
                ? normalized
                : normalized.substring(0, 128));
    }

    public record Context(
            UUID storeId,
            UUID operatorId,
            String operatorUsername,
            UUID terminalId,
            SaleOperationCode operationCode) {

        public Context {
            Objects.requireNonNull(storeId, "storeId");
            Objects.requireNonNull(operatorId, "operatorId");
            if (operatorUsername == null || operatorUsername.isBlank()) {
                throw new IllegalArgumentException(
                        "operatorUsername is required");
            }
            operatorUsername = operatorUsername.trim();
            Objects.requireNonNull(terminalId, "terminalId");
            Objects.requireNonNull(operationCode, "operationCode");
        }
    }

    private interface AttemptState {
        int consecutiveFailures();

        Instant blockedUntil();

        long retryAfterSeconds();
    }

    public record Block(
            int consecutiveFailures,
            Instant blockedUntil,
            long retryAfterSeconds) implements AttemptState {
    }

    public record Reservation(UUID token, Instant expiresAt) {

        public Reservation {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record Failure(
            int consecutiveFailures,
            Instant blockedUntil,
            long retryAfterSeconds) implements AttemptState {

        public boolean throttled() {
            return blockedUntil != null;
        }
    }
}
