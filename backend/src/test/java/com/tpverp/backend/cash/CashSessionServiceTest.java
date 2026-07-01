package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.domain.Permission;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TerminalType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class CashSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T09:30:00Z");

    @Test
    void secondMismatchClosesSessionAndStoresDiscrepancy() {
        var session = openSession();

        session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T10:00:00Z"),
                new BigDecimal("93.333"), new BigDecimal("100.00"), new BigDecimal("1.00"));
        var second = session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T10:05:00Z"),
                new BigDecimal("94.00"), new BigDecimal("100.00"), new BigDecimal("1.00"));

        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.CERRADA);
        assertThat(session.getClosedAt()).isEqualTo(Instant.parse("2026-06-25T10:05:00Z"));
        assertThat(session.getDiscrepancy()).isEqualByComparingTo("-6.00");
        assertThat(second.closedSession()).isTrue();
        assertThat(second.getAttemptNumber()).isEqualTo(2);
        assertThat(second.getDiscrepancy()).isEqualByComparingTo("-6.00");
        assertThat(session.getAttempts()).extracting(CashReconciliationAttempt::getDiscrepancy)
                .containsExactly(new BigDecimal("-6.67"), new BigDecimal("-6.00"));
    }

    @Test
    void firstAttemptInsideToleranceClosesImmediately() {
        var session = openSession();

        var attempt = session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T11:00:00Z"),
                new BigDecimal("99.51"), new BigDecimal("100.00"), new BigDecimal("0.50"));

        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.CERRADA);
        assertThat(session.getDiscrepancy()).isEqualByComparingTo("-0.49");
        assertThat(attempt.closedSession()).isTrue();
        assertThat(session.getAttempts()).hasSize(1);
    }

    @Test
    void firstAttemptOutsideToleranceLeavesSessionOpen() {
        var session = openSession();

        var attempt = session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T12:00:00Z"),
                new BigDecimal("98.99"), new BigDecimal("100.00"), new BigDecimal("1.00"));

        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.ABIERTA);
        assertThat(session.getClosedAt()).isNull();
        assertThat(session.getDiscrepancy()).isNull();
        assertThat(attempt.closedSession()).isFalse();
        assertThat(session.getAttempts()).hasSize(1);
    }

    @Test
    void denominationsAreInExpectedEuroOrder() {
        assertThat(CashDenomination.valuesInEuroOrder()).containsExactlyElementsOf(List.of(
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                new BigDecimal("20.00"),
                new BigDecimal("10.00"),
                new BigDecimal("5.00"),
                new BigDecimal("2.00"),
                new BigDecimal("1.00"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                new BigDecimal("0.10"),
                new BigDecimal("0.05"),
                new BigDecimal("0.02"),
                new BigDecimal("0.01")));
    }

    @Test
    void registerAttemptRejectsNegativeDeclaredFund() {
        var session = openSession();

        assertThatThrownBy(() -> session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T13:00:00Z"),
                new BigDecimal("-0.01"), new BigDecimal("100.00"), new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declaredFund");
        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.ABIERTA);
        assertThat(session.getAttempts()).isEmpty();
    }

    @Test
    void closeRejectsNegativeRetainedFund() {
        var session = openSession();

        assertThatThrownBy(() -> session.close(
                UUID.randomUUID(), Instant.parse("2026-06-25T13:30:00Z"),
                new BigDecimal("100.00"), new BigDecimal("-0.01"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retainedFund");
        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.ABIERTA);
    }

    @Test
    void movementDenominationRejectsZeroOrNegativeDenomination() {
        var movement = CashMovement.betweenSessions(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"),
                Instant.parse("2026-06-25T14:00:00Z"), UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> movement.addDenomination(BigDecimal.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denominacion");
        assertThatThrownBy(() -> movement.addDenomination(new BigDecimal("-0.01"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denominacion");
    }

    @Test
    void sessionMovementDerivesStoreAndTerminalFromSession() {
        var session = openSession();

        var movement = CashMovement.sessionMovement(
                UUID.randomUUID(), UUID.randomUUID(), session, CashMovementType.ENTRADA,
                new BigDecimal("10.00"), Instant.parse("2026-06-25T14:30:00Z"),
                UUID.randomUUID(), null, null, null, null);

        assertThat(movement.getStoreId()).isEqualTo(session.getStoreId());
        assertThat(movement.getTerminalId()).isEqualTo(session.getTerminalId());
        assertThat(movement.getSessionId()).isEqualTo(session.getId());
    }

    @Test
    void sessionMovementRejectsBetweenSessionTypes() {
        var session = openSession();

        assertThatThrownBy(() -> CashMovement.sessionMovement(
                UUID.randomUUID(), UUID.randomUUID(), session, CashMovementType.ENTRADA_ENTRE_SESIONES,
                new BigDecimal("10.00"), Instant.parse("2026-06-25T14:45:00Z"),
                UUID.randomUUID(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre sesiones");
        assertThatThrownBy(() -> CashMovement.sessionMovement(
                UUID.randomUUID(), UUID.randomUUID(), session.getId(), CashMovementType.RETIRADA_ENTRE_SESIONES,
                new BigDecimal("10.00"), Instant.parse("2026-06-25T14:46:00Z"),
                UUID.randomUUID(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre sesiones");
    }

    @Test
    void firstOpeningRequiresPreviousBetweenSessionEntry() {
        var fixture = serviceFixture();
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.empty());
        when(fixture.sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                fixture.terminal.getId(), CashSessionStatus.CERRADA))
                .thenReturn(Optional.empty());
        when(fixture.movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(fixture.terminal.getId()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.open(fixture.terminal.getId(), salesAuthentication(fixture.user)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entrada entre sesiones");
    }

    @Test
    void firstOpeningRequiresBetweenSessionEntryNotWithdrawal() {
        var fixture = serviceFixture();
        var betweenSessionWithdrawal = CashMovement.betweenSessionWithdrawal(
                fixture.store.getId(), fixture.terminal.getId(), new BigDecimal("25.00"),
                NOW.minusSeconds(60), fixture.user.getId(), null, "retirada entre sesiones");
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.empty());
        when(fixture.sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                fixture.terminal.getId(), CashSessionStatus.CERRADA))
                .thenReturn(Optional.empty());
        when(fixture.movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(fixture.terminal.getId()))
                .thenReturn(List.of(betweenSessionWithdrawal));

        assertThatThrownBy(() -> fixture.service.open(fixture.terminal.getId(), salesAuthentication(fixture.user)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entrada entre sesiones");
    }

    @Test
    void opensWithPreviousRetainedFundPlusBetweenSessionMovements() {
        var fixture = serviceFixture();
        var previous = closedSession(fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), "100.00");
        var betweenSessionEntry = CashMovement.betweenSessions(
                fixture.store.getId(), fixture.terminal.getId(), new BigDecimal("25.00"),
                NOW.minusSeconds(60), fixture.user.getId(), null, "refuerzo");
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.empty());
        when(fixture.sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                fixture.terminal.getId(), CashSessionStatus.CERRADA))
                .thenReturn(Optional.of(previous));
        when(fixture.movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(fixture.terminal.getId()))
                .thenReturn(List.of(betweenSessionEntry));

        var opened = fixture.service.open(fixture.terminal.getId(), salesAuthentication(fixture.user));

        assertThat(opened.status()).isEqualTo(CashSessionStatus.ABIERTA);
        assertThat(opened.openingFund()).isEqualByComparingTo("125.00");
    }

    @Test
    void opensWithPreviousRetainedFundMinusBetweenSessionWithdrawal() {
        var fixture = serviceFixture();
        var previous = closedSession(fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), "100.00");
        var betweenSessionWithdrawal = CashMovement.betweenSessionWithdrawal(
                fixture.store.getId(), fixture.terminal.getId(), new BigDecimal("25.00"),
                NOW.minusSeconds(60), fixture.user.getId(), null, "retirada entre sesiones");
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.empty());
        when(fixture.sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                fixture.terminal.getId(), CashSessionStatus.CERRADA))
                .thenReturn(Optional.of(previous));
        when(fixture.movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(fixture.terminal.getId()))
                .thenReturn(List.of(betweenSessionWithdrawal));

        var opened = fixture.service.open(fixture.terminal.getId(), salesAuthentication(fixture.user));

        assertThat(opened.status()).isEqualTo(CashSessionStatus.ABIERTA);
        assertThat(opened.openingFund()).isEqualByComparingTo("75.00");
    }

    @Test
    void betweenSessionsCanRecordWithdrawal() {
        var fixture = serviceFixture();
        var previous = closedSession(fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), "50.00");
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.empty());
        when(fixture.sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                fixture.terminal.getId(), CashSessionStatus.CERRADA))
                .thenReturn(Optional.of(previous));
        when(fixture.movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(fixture.terminal.getId()))
                .thenReturn(List.of());

        var movement = fixture.service.betweenSessions(
                fixture.terminal.getId(),
                new CashWithdrawalRequest(new BigDecimal("25.00"), "retirada entre sesiones", List.of(), true),
                accountingAuthentication(fixture.user));

        assertThat(movement.type()).isEqualTo(CashMovementType.RETIRADA_ENTRE_SESIONES);
        assertThat(movement.amount()).isEqualByComparingTo("25.00");
    }

    @Test
    void betweenSessionWithdrawalCannotExceedPendingOpeningFund() {
        var fixture = serviceFixture();
        var previous = closedSession(fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), "50.00");
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.empty());
        when(fixture.sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                fixture.terminal.getId(), CashSessionStatus.CERRADA))
                .thenReturn(Optional.of(previous));
        when(fixture.movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(fixture.terminal.getId()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.betweenSessions(
                fixture.terminal.getId(),
                new CashWithdrawalRequest(new BigDecimal("50.01"), "retirada entre sesiones", List.of(), true),
                accountingAuthentication(fixture.user)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.cash.withdrawal_exceeds_pending_opening_fund");
        verify(fixture.movements, never()).save(any(CashMovement.class));
    }

    @Test
    void betweenSessionRequiresConfigOrAccountingPermission() {
        var fixture = serviceFixture();

        assertThatThrownBy(() -> fixture.service.betweenSessions(
                fixture.terminal.getId(),
                new CashWithdrawalRequest(new BigDecimal("10.00"), "entrada entre sesiones", List.of(), false),
                salesAuthentication(fixture.user)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("configuracion de caja");
    }

    @Test
    void betweenSessionRejectsOpenSession() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("40.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> fixture.service.betweenSessions(
                fixture.terminal.getId(),
                new CashWithdrawalRequest(new BigDecimal("10.00"), "entrada entre sesiones", List.of(), false),
                accountingAuthentication(fixture.user)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caja abierta");
    }

    @Test
    void requiredDenominationBreakdownRejectsTotalOnly() {
        var fixture = serviceFixture();
        var config = new CashStoreConfig(fixture.store.getId());
        ReflectionTestUtils.setField(config, "requireWithdrawalBreakdown", true);
        when(fixture.configs.findById(fixture.store.getId())).thenReturn(Optional.of(config));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.betweenSessions(
                fixture.terminal.getId(),
                new CashWithdrawalRequest(new BigDecimal("10.00"), "retirada entre sesiones", List.of(), true),
                accountingAuthentication(fixture.user)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desglose de denominaciones");
    }

    @Test
    void sessionWithdrawalCannotExceedExpectedCash() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("40.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.withdrawal(
                fixture.terminal.getId(),
                new CashWithdrawalRequest(new BigDecimal("40.01"), "retirada", List.of()),
                salesAuthentication(fixture.user)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("efectivo disponible");
    }

    @Test
    void normalWithdrawalIgnoresBetweenSessionDirectionFlagAndStillWithdraws() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("40.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        var movement = fixture.service.withdrawal(
                fixture.terminal.getId(),
                new CashWithdrawalRequest(new BigDecimal("10.00"), "retirada", List.of(), true),
                salesAuthentication(fixture.user));

        assertThat(movement.type()).isEqualTo(CashMovementType.RETIRADA);
        assertThat(movement.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void entryDuringSessionRequiresAdminOrAccountingAuthorizer() {
        var fixture = serviceFixture();
        var sellerAuthorizer = user(fixture.store, "SELLER", new Role(fixture.store, "SELLER"));
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("40.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.users.findByTiendaIdAndNombre(fixture.store.getId(), "SELLER"))
                .thenReturn(Optional.of(sellerAuthorizer));
        when(fixture.passwordEncoder.matches("secret", sellerAuthorizer.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.entry(
                fixture.terminal.getId(),
                new CashEntryRequest(new BigDecimal("10.00"), "entrada manual", "SELLER", "secret", List.of()),
                salesAuthentication(fixture.user)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("autorizador");
    }

    @Test
    void sellerStatusDoesNotExposeExpectedTotals() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("40.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of(
                CashMovement.sessionMovement(
                        fixture.store.getId(), fixture.terminal.getId(), session, CashMovementType.COBRO_EFECTIVO,
                        new BigDecimal("15.00"), NOW, fixture.user.getId(), null, null, null, null)));

        var status = fixture.service.status(fixture.terminal.getId(), salesAuthentication(fixture.user));

        assertThat(status.openingFund()).isEqualByComparingTo("40.00");
        assertThat(status.expectedCash()).isNull();
        assertThat(status.availableCash()).isNull();
    }

    @Test
    void accountingStatusCanSeeExpectedTotals() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("40.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of(
                CashMovement.sessionMovement(
                        fixture.store.getId(), fixture.terminal.getId(), session, CashMovementType.COBRO_EFECTIVO,
                        new BigDecimal("15.00"), NOW, fixture.user.getId(), null, null, null, null)));

        var status = fixture.service.status(fixture.terminal.getId(), accountingAuthentication(fixture.user));

        assertThat(status.openingFund()).isEqualByComparingTo("40.00");
        assertThat(status.expectedCash()).isEqualByComparingTo("55.00");
        assertThat(status.availableCash()).isEqualByComparingTo("55.00");
    }

    @Test
    void firstMismatchReturnsOpenSessionWithDiscrepancyOnly() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        var view = fixture.service.close(
                fixture.terminal.getId(),
                new CashCloseRequest(new BigDecimal("90.00"), List.of(), BigDecimal.ZERO, "sobrante", List.of()),
                salesAuthentication(fixture.user));

        assertThat(view.status()).isEqualTo(CashSessionStatus.ABIERTA);
        assertThat(view.expectedCash()).isNull();
        assertThat(view.availableCash()).isNull();
        assertThat(view.retainedFund()).isNull();
        assertThat(view.discrepancy()).isEqualByComparingTo("-10.00");
        assertThat(view.reconciliationAttempt()).isEqualTo(1);
        assertThat(view.closedByAttempt()).isFalse();
        assertThat(session.getAttempts()).hasSize(1);
    }

    @Test
    void secondMismatchClosesAndStoresDiscrepancyWithoutExplanation() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        session.registerAttempt(fixture.user.getId(), NOW.plusSeconds(60), new BigDecimal("90.00"),
                new BigDecimal("100.00"), BigDecimal.ZERO);
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        var view = fixture.service.close(
                fixture.terminal.getId(),
                new CashCloseRequest(new BigDecimal("95.00"), List.of(), BigDecimal.ZERO, null, List.of()),
                salesAuthentication(fixture.user));

        assertThat(view.status()).isEqualTo(CashSessionStatus.CERRADA);
        assertThat(view.expectedCash()).isNull();
        assertThat(view.retainedFund()).isNull();
        assertThat(view.discrepancy()).isEqualByComparingTo("-5.00");
        assertThat(view.reconciliationAttempt()).isEqualTo(2);
        assertThat(view.closedByAttempt()).isTrue();
        assertThat(session.getDiscrepancy()).isEqualByComparingTo("-5.00");
        assertThat(session.getAttempts()).extracting(CashReconciliationAttempt::getAttemptNumber)
                .containsExactly(1, 2);
    }

    @Test
    void sellerCloseViewDoesNotExposeExpectedCash() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        var view = fixture.service.close(
                fixture.terminal.getId(),
                new CashCloseRequest(new BigDecimal("100.00"), List.of(), BigDecimal.ZERO, null, List.of()),
                salesAuthentication(fixture.user));

        assertThat(view.status()).isEqualTo(CashSessionStatus.CERRADA);
        assertThat(view.expectedCash()).isNull();
        assertThat(view.availableCash()).isNull();
        assertThat(view.retainedFund()).isNull();
        assertThat(view.discrepancy()).isEqualByComparingTo("0.00");
    }

    @Test
    void accountingCloseViewIncludesExpectedCash() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        var view = fixture.service.close(
                fixture.terminal.getId(),
                new CashCloseRequest(new BigDecimal("100.00"), List.of(), BigDecimal.ZERO, null, List.of()),
                salesAndAccountingAuthentication(fixture.user));

        assertThat(view.status()).isEqualTo(CashSessionStatus.CERRADA);
        assertThat(view.expectedCash()).isEqualByComparingTo("100.00");
        assertThat(view.retainedFund()).isEqualByComparingTo("100.00");
        assertThat(view.discrepancy()).isEqualByComparingTo("0.00");
    }

    @Test
    void closedCashSessionEnqueuesSyncEvent() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW,
                new BigDecimal("100.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        fixture.service.close(
                fixture.terminal.getId(),
                new CashCloseRequest(new BigDecimal("100.00"), List.of(), BigDecimal.ZERO, null, List.of()),
                salesAuthentication(fixture.user));

        var command = org.mockito.ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(fixture.syncOutbox).enqueue(command.capture());
        assertThat(command.getValue().companyId()).isEqualTo(fixture.store.getEmpresa().getId());
        assertThat(command.getValue().storeId()).isEqualTo(fixture.store.getId());
        assertThat(command.getValue().terminalId()).isEqualTo(fixture.terminal.getId());
        assertThat(command.getValue().entityType()).isEqualTo("CIERRE_CAJA");
        assertThat(command.getValue().entityId()).isEqualTo(session.getId());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.CERRAR);
        assertThat(command.getValue().payload())
                .containsEntry("estado", "CERRADA")
                .containsEntry("efectivoTeorico", "100.00")
                .containsEntry("fondoDejado", "100.00")
                .containsEntry("descuadre", "0.00");
    }

    @Test
    void firstMismatchDoesNotEnqueueSyncEventWhileSessionRemainsOpen() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW,
                new BigDecimal("100.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        fixture.service.close(
                fixture.terminal.getId(),
                new CashCloseRequest(new BigDecimal("90.00"), List.of(), BigDecimal.ZERO, null, List.of()),
                salesAuthentication(fixture.user));

        verify(fixture.syncOutbox, never()).enqueue(any());
    }

    @Test
    void finalWithdrawalAffectsExpectedCashBeforeCounting() {
        var fixture = serviceFixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        var view = fixture.service.close(
                fixture.terminal.getId(),
                new CashCloseRequest(new BigDecimal("80.00"), List.of(), new BigDecimal("20.00"), "retirada cierre",
                        List.of(new CashDenominationCommand(new BigDecimal("20.00"), 1))),
                salesAndAccountingAuthentication(fixture.user));

        assertThat(view.status()).isEqualTo(CashSessionStatus.CERRADA);
        assertThat(view.expectedCash()).isEqualByComparingTo("80.00");
        assertThat(view.discrepancy()).isEqualByComparingTo("0.00");
        verify(fixture.movements).save(any(CashMovement.class));
    }

    @Test
    void closeRequiresBreakdownWhenConfigured() {
        var fixture = serviceFixture();
        var config = new CashStoreConfig(fixture.store.getId());
        ReflectionTestUtils.setField(config, "requireClosingBreakdown", true);
        when(fixture.configs.findById(fixture.store.getId())).thenReturn(Optional.of(config));
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.close(
                fixture.terminal.getId(),
                new CashCloseRequest(new BigDecimal("100.00"), List.of(), BigDecimal.ZERO, null, List.of()),
                salesAuthentication(fixture.user)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desglose de denominaciones");
    }

    @Test
    void closingOnNextDateMarksSessionAsLateClosing() {
        var fixture = serviceFixture(Instant.parse("2026-06-26T00:30:00Z"));
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(),
                Instant.parse("2026-06-25T23:30:00Z"), new BigDecimal("100.00"));
        when(fixture.sessions.findByTerminalIdAndStatus(fixture.terminal.getId(), CashSessionStatus.ABIERTA))
                .thenReturn(Optional.of(session));
        when(fixture.movements.findAllBySesionCajaId(session.getId())).thenReturn(List.of());

        fixture.service.close(
                fixture.terminal.getId(),
                new CashCloseRequest(new BigDecimal("100.00"), List.of(), BigDecimal.ZERO, null, List.of()),
                salesAuthentication(fixture.user));

        assertThat(session.isLateClosing()).isTrue();
    }

    private CashSession openSession() {
        return CashSession.open(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-06-25T09:00:00Z"),
                new BigDecimal("50.004"));
    }

    private static ServiceFixture serviceFixture() {
        return serviceFixture(NOW);
    }

    private static ServiceFixture serviceFixture(Instant now) {
        var store = store("001");
        var user = user(store, "SELLER", salesRole(store));
        var terminal = new Terminal(store, "TPV 1", TerminalType.TERMINAL_VENTA, "hash");
        var sessions = mock(CashSessionRepository.class);
        var movements = mock(CashMovementRepository.class);
        var configs = mock(CashStoreConfigRepository.class);
        var terminals = mock(TerminalRepository.class);
        var organization = mock(CurrentOrganization.class);
        var users = mock(UserAccountRepository.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        var syncOutbox = mock(SyncOutboxService.class);
        when(terminals.findByIdAndTiendaId(terminal.getId(), store.getId())).thenReturn(Optional.of(terminal));
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(any())).thenReturn(user);
        when(configs.findById(store.getId())).thenReturn(Optional.of(new CashStoreConfig(store.getId())));
        when(sessions.save(any(CashSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(movements.save(any(CashMovement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var permissions = new CashPermissionService(users, passwordEncoder, organization);
        var calculator = new CashAmountCalculator(sessions, movements);
        var service = new CashSessionService(
                sessions, movements, configs, terminals, organization, permissions, calculator,
                syncOutbox, Clock.fixed(now, ZoneOffset.UTC));
        return new ServiceFixture(
                service, sessions, movements, configs, terminals, organization, users, passwordEncoder,
                syncOutbox, store, user, terminal);
    }

    private static CashSession closedSession(UUID storeId, UUID terminalId, UUID userId, String retainedFund) {
        var session = CashSession.open(storeId, terminalId, userId, NOW.minusSeconds(3600), new BigDecimal("10.00"));
        session.close(
                userId, NOW.minusSeconds(1800), new BigDecimal("120.00"),
                new BigDecimal(retainedFund), BigDecimal.ZERO);
        return session;
    }

    private static UsernamePasswordAuthenticationToken salesAuthentication(UserAccount user) {
        return new UsernamePasswordAuthenticationToken(
                user, "token", List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.GESTION_VENTAS)));
    }

    private static UsernamePasswordAuthenticationToken accountingAuthentication(UserAccount user) {
        return new UsernamePasswordAuthenticationToken(
                user, "token", List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.GESTION_CUENTAS)));
    }

    private static UsernamePasswordAuthenticationToken salesAndAccountingAuthentication(UserAccount user) {
        return new UsernamePasswordAuthenticationToken(
                user, "token", List.of(
                        new SimpleGrantedAuthority(CorePermissionBootstrap.GESTION_VENTAS),
                        new SimpleGrantedAuthority(CorePermissionBootstrap.GESTION_CUENTAS)));
    }

    private static Role salesRole(Store store) {
        var role = new Role(store, "SELLER");
        role.conceder(new Permission(CorePermissionBootstrap.GESTION_VENTAS, "sales", "DOCUMENTS"));
        return role;
    }

    private static UserAccount user(Store store, String name, Role role) {
        return new UserAccount(store, name, "hash-" + name, role);
    }

    private static Store store(String code) {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                code, "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private record ServiceFixture(
            CashSessionService service,
            CashSessionRepository sessions,
            CashMovementRepository movements,
            CashStoreConfigRepository configs,
            TerminalRepository terminals,
            CurrentOrganization organization,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            SyncOutboxService syncOutbox,
            Store store,
            UserAccount user,
            Terminal terminal) {
    }
}
