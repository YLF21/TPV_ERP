package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservationStatus;
import com.tpverp.backend.party.loyalty.central.MemberBalanceManualReconciliationRequiredException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MemberBalanceRecoveryIncidentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T14:00:00Z");

    @Test
    void closedReservationBecomesNonRetryableManualReconciliation() {
        var repository = mock(SalePaymentSessionRepository.class);
        var organization = mock(CurrentOrganization.class);
        var audit = mock(AuditService.class);
        var store = mock(Store.class);
        UUID storeId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        var session = pendingSession(storeId, reservationId);
        when(repository.findLocked(session.getId())).thenReturn(Optional.of(session));
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        var service = new MemberBalanceRecoveryIncidentService(
                repository, organization, audit, Clock.fixed(NOW, ZoneOffset.UTC));

        service.recordFailure(
                session.getId(),
                new MemberBalanceManualReconciliationRequiredException(
                        reservationId, LocalMemberBalanceReservationStatus.RELEASED));

        assertThat(session.isMemberBalanceRecoveryManualReview()).isTrue();
        assertThat(session.getMemberBalanceRecoveryDisposition())
                .isEqualTo(MemberBalanceRecoveryDisposition.MANUAL_RECONCILIATION_REQUIRED);
        assertThat(session.getMemberBalanceRecoveryNextAttemptAt()).isNull();
        assertThat(MemberBalanceRecoveryIncidentView.from(session).retryAllowed()).isFalse();
        assertThatThrownBy(() -> service.scheduleManualRetry(
                session.getId(), session.getVersion(), "Reintento inseguro"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("conciliacion contable manual");
    }

    @Test
    void transientFailureUsesBackoffAndRemainsRetryable() {
        var repository = mock(SalePaymentSessionRepository.class);
        var session = pendingSession(UUID.randomUUID(), UUID.randomUUID());
        when(repository.findLocked(session.getId())).thenReturn(Optional.of(session));
        var service = new MemberBalanceRecoveryIncidentService(
                repository,
                mock(CurrentOrganization.class),
                mock(AuditService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.recordFailure(session.getId(), new IllegalStateException("saas_unavailable"));

        assertThat(session.isMemberBalanceRecoveryManualReview()).isFalse();
        assertThat(session.getMemberBalanceRecoveryDisposition())
                .isEqualTo(MemberBalanceRecoveryDisposition.AUTOMATIC_RETRY);
        assertThat(session.getMemberBalanceRecoveryNextAttemptAt())
                .isEqualTo(NOW.plusSeconds(5));
        assertThat(MemberBalanceRecoveryIncidentView.from(session).retryAllowed()).isTrue();
    }

    private static SalePaymentSession pendingSession(UUID storeId, UUID reservationId) {
        var session = SalePaymentSession.reserve(
                UUID.randomUUID(), storeId, UUID.randomUUID(), UUID.randomUUID(),
                "hash", "{}", BigDecimal.ZERO);
        session.memberBalancePrepared(reservationId, new BigDecimal("1.00"));
        session.finalizeWith(UUID.randomUUID(), "T-1");
        return session;
    }
}
