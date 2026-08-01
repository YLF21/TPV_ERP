package com.tpverp.backend.security.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SaleOperationAuthorizationAttemptServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T10:00:00Z");

    private SaleOperationAuthorizationAttemptRepository attempts;
    private AuditService audit;
    private SaleOperationAuthorizationAttemptService service;
    private SaleOperationAuthorizationAttemptService.Context context;

    @BeforeEach
    void setUp() {
        attempts = mock(SaleOperationAuthorizationAttemptRepository.class);
        audit = mock(AuditService.class);
        service = new SaleOperationAuthorizationAttemptService(
                attempts,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        context = new SaleOperationAuthorizationAttemptService.Context(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CAJERO",
                UUID.randomUUID(),
                SaleOperationCode.CANCEL_TICKET);
    }

    @Test
    void thirdConsecutiveFailureStartsFiveSecondCooldownAndAuditsNoPassword() {
        var attempt = mock(SaleOperationAuthorizationAttempt.class);
        var reservation = reservation();
        when(attempts.findByScopeForUpdate(
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode())).thenReturn(Optional.of(attempt));
        when(attempt.getLastFailureAt()).thenReturn(NOW.minusSeconds(10));
        when(attempt.getConsecutiveFailures()).thenReturn(2);
        when(attempt.ownsReservation(reservation.token())).thenReturn(true);
        when(attempt.hasActiveReservationAt(NOW)).thenReturn(true);
        when(attempt.registerFailure(
                reservation.token(),
                NOW,
                SaleOperationAuthorizationAttemptService.FAILURE_WINDOW,
                Duration.ofSeconds(5)))
                .thenReturn(new SaleOperationAuthorizationAttempt.Failure(
                        3, NOW.plusSeconds(5)));

        var failure = service.recordFailure(
                context, reservation, " supervisor ");

        assertThat(failure.consecutiveFailures()).isEqualTo(3);
        assertThat(failure.retryAfterSeconds()).isEqualTo(5);
        assertThat(failure.throttled()).isTrue();
        @SuppressWarnings("unchecked")
        var details = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq(SaleOperationAuthorizationAttemptService.AUDIT_FAILED),
                eq(AuditResult.FALLO),
                details.capture());
        assertThat(details.getValue())
                .containsEntry("operatorId", context.operatorId().toString())
                .containsEntry("terminalId", context.terminalId().toString())
                .containsEntry("operationCode", "CANCEL_TICKET")
                .containsEntry("requestedAuthorizerUsername", "SUPERVISOR")
                .containsEntry("consecutiveFailures", 3)
                .doesNotContainKeys("password", "authorizerPassword");
    }

    @Test
    void quietWindowResetsTheProgressiveCounter() {
        var attempt = mock(SaleOperationAuthorizationAttempt.class);
        var reservation = reservation();
        when(attempts.findByScopeForUpdate(
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode())).thenReturn(Optional.of(attempt));
        when(attempt.getLastFailureAt()).thenReturn(
                NOW.minus(SaleOperationAuthorizationAttemptService.FAILURE_WINDOW));
        when(attempt.getConsecutiveFailures()).thenReturn(20);
        when(attempt.ownsReservation(reservation.token())).thenReturn(true);
        when(attempt.hasActiveReservationAt(NOW)).thenReturn(true);
        when(attempt.registerFailure(
                reservation.token(),
                NOW,
                SaleOperationAuthorizationAttemptService.FAILURE_WINDOW,
                Duration.ZERO))
                .thenReturn(new SaleOperationAuthorizationAttempt.Failure(1, null));

        var failure = service.recordFailure(context, reservation, null);

        assertThat(failure.consecutiveFailures()).isOne();
        assertThat(failure.throttled()).isFalse();
    }

    @Test
    void blockedAttemptIsRejectedBeforeReservingPasswordVerification() {
        var attempt = mock(SaleOperationAuthorizationAttempt.class);
        when(attempts.findByScopeForUpdate(
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode())).thenReturn(Optional.of(attempt));
        when(attempt.isBlockedAt(NOW)).thenReturn(true);
        when(attempt.getConsecutiveFailures()).thenReturn(4);
        when(attempt.getBlockedUntil()).thenReturn(NOW.plusSeconds(15));

        assertThatThrownBy(() -> service.reserve(context, "SUPERVISOR"))
                .isInstanceOf(SaleOperationAuthorizationThrottledException.class)
                .satisfies(exception -> assertThat(
                        ((SaleOperationAuthorizationThrottledException) exception)
                                .retryAfterSeconds()).isEqualTo(15));
        verify(attempts, never()).saveAndFlush(any());
        verify(audit).record(
                eq(SaleOperationAuthorizationAttemptService.AUDIT_THROTTLED),
                eq(AuditResult.FALLO),
                any());
    }

    @Test
    void successDeletesOnlyTheOperatorsScopedState() {
        var attempt = mock(SaleOperationAuthorizationAttempt.class);
        var reservation = reservation();
        when(attempts.findByScopeForUpdate(
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode())).thenReturn(Optional.of(attempt));
        when(attempt.ownsReservation(reservation.token())).thenReturn(true);
        when(attempt.hasActiveReservationAt(NOW)).thenReturn(true);

        service.recordSuccess(context, reservation);

        verify(attempts).delete(attempt);
        verify(attempts).flush();
        verify(audit, never()).record(any(), any(), any());
    }

    @Test
    void expiredReservationCannotCompleteEvenIfItsTokenStillMatches() {
        var attempt = mock(SaleOperationAuthorizationAttempt.class);
        var reservation = new SaleOperationAuthorizationAttemptService.Reservation(
                UUID.randomUUID(), NOW);
        when(attempts.findByScopeForUpdate(
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode())).thenReturn(Optional.of(attempt));
        when(attempt.ownsReservation(reservation.token())).thenReturn(true);
        when(attempt.hasActiveReservationAt(NOW)).thenReturn(false);
        when(attempt.getReservationUntil()).thenReturn(NOW);

        assertThatThrownBy(() -> service.recordSuccess(context, reservation))
                .isInstanceOf(SaleOperationAuthorizationThrottledException.class);

        verify(attempts, never()).delete(any());
        verify(audit).record(
                eq(SaleOperationAuthorizationAttemptService.AUDIT_THROTTLED),
                eq(AuditResult.FALLO),
                any());
    }

    @Test
    void activeReservationRejectsConcurrentPasswordVerification() {
        var attempt = mock(SaleOperationAuthorizationAttempt.class);
        when(attempts.findByScopeForUpdate(
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode())).thenReturn(Optional.of(attempt));
        when(attempt.hasActiveReservationAt(NOW)).thenReturn(true);
        when(attempt.getReservationUntil()).thenReturn(NOW.plusSeconds(30));

        assertThatThrownBy(() -> service.reserve(context, null))
                .isInstanceOf(SaleOperationAuthorizationThrottledException.class)
                .satisfies(exception -> assertThat(
                        ((SaleOperationAuthorizationThrottledException) exception)
                                .retryAfterSeconds()).isEqualTo(30));

        verify(attempt, never()).reserve(any(), any(), any());
        verify(attempts, never()).saveAndFlush(any());
    }

    @Test
    void reservesExactlyOneThirtySecondCredentialLease() {
        var attempt = mock(SaleOperationAuthorizationAttempt.class);
        when(attempts.findByScopeForUpdate(
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode())).thenReturn(Optional.of(attempt));

        var reservation = service.reserve(context, null);

        assertThat(reservation.expiresAt()).isEqualTo(NOW.plusSeconds(30));
        verify(attempt).reserve(
                reservation.token(), NOW,
                SaleOperationAuthorizationAttemptService.RESERVATION_TTL);
        verify(attempts).saveAndFlush(attempt);
    }

    private static SaleOperationAuthorizationAttemptService.Reservation reservation() {
        return new SaleOperationAuthorizationAttemptService.Reservation(
                UUID.randomUUID(), NOW.plusSeconds(30));
    }
}
