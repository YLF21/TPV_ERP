package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservation;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservationStatus;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCheckoutProtocolService;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CardTerminalConfigurationReader;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentTerminalOperationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;

class SalePaymentSessionMemberBalanceTest {

    @Test
    void saasFailureCreatesThePaymentSessionWithoutMemberBalance() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        when(fixture.protocol.prepare(
                reservationId,
                fixture.storeId,
                fixture.terminalId,
                sessionId.toString(),
                sessionId,
                new BigDecimal("10.00"),
                new BigDecimal("0.00")))
                .thenThrow(new IllegalStateException("saas_unavailable"));

        var session = fixture.service.reserve(
                sessionId, fixture.sale, reservationId, fixture.authentication);

        assertThat(session.getTotal()).isEqualByComparingTo("100.00");
        assertThat(session.getMemberBalanceReservationId()).isNull();
        assertThat(session.getMemberBalanceRequestedAmount()).isEqualByComparingTo("10.00");
        assertThat(session.getMemberBalanceAppliedAmount()).isEqualByComparingTo("0.00");
        assertThat(session.getMemberBalanceFailureCode())
                .isEqualTo("member_balance_unavailable");
        verify(fixture.repository).save(session);
        verify(fixture.protocol, never()).abortPrepared(any());
    }

    @Test
    void successfulPrepareBindsTheReservationBeforeAcceptingPayments() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        when(fixture.protocol.prepare(
                reservationId,
                fixture.storeId,
                fixture.terminalId,
                sessionId.toString(),
                sessionId,
                new BigDecimal("10.00"),
                new BigDecimal("0.00")))
                .thenReturn(mock(LocalMemberBalanceReservation.class));

        var session = fixture.service.reserve(
                sessionId, fixture.sale, reservationId, fixture.authentication);

        assertThat(session.getTotal()).isEqualByComparingTo("90.00");
        assertThat(session.getMemberBalanceReservationId()).isEqualTo(reservationId);
        assertThat(session.getMemberBalanceRequestedAmount()).isEqualByComparingTo("10.00");
        assertThat(session.getMemberBalanceAppliedAmount()).isEqualByComparingTo("10.00");
        assertThat(session.getMemberBalanceFailureCode()).isNull();
    }

    @Test
    void committedTicketIsFinalizedByRecoveryWithoutTouchingCheckoutResult() {
        var fixture = recoveryFixture();
        var reservation = mock(LocalMemberBalanceReservation.class);
        when(reservation.getStatus()).thenReturn(LocalMemberBalanceReservationStatus.CONSUMED);
        when(fixture.protocol.finalizePrepared(fixture.reservationId)).thenReturn(reservation);

        fixture.service.recoverMemberBalanceFinalization(fixture.session.getId());

        verify(fixture.protocol).markTicketCommitted(
                fixture.reservationId, fixture.session.getTicketId());
        verify(fixture.protocol).finalizePrepared(fixture.reservationId);
        assertThat(fixture.session.getMemberBalanceSynchronizedAt()).isNotNull();
    }

    @Test
    void cancelledCheckoutIsAbortedByRecovery() {
        var fixture = recoveryFixture();
        var session = SalePaymentSession.reserve(
                UUID.randomUUID(), fixture.storeId, fixture.terminalId,
                fixture.userId, "hash-cancelled", "{}", BigDecimal.ZERO);
        session.memberBalancePrepared(fixture.reservationId, new BigDecimal("10.00"));
        session.cancel();
        when(fixture.repository.findState(session.getId())).thenReturn(Optional.of(session));
        when(fixture.repository.findLocked(session.getId())).thenReturn(Optional.of(session));
        var reservation = mock(LocalMemberBalanceReservation.class);
        when(reservation.isClosed()).thenReturn(true);
        when(fixture.protocol.abortPrepared(fixture.reservationId)).thenReturn(reservation);

        fixture.service.recoverMemberBalanceAbort(session.getId());

        verify(fixture.protocol).abortPrepared(fixture.reservationId);
        assertThat(session.getMemberBalanceSynchronizedAt()).isNotNull();
    }

    @Test
    void workerRoutesFinalizationAndCancellationIndependently() {
        var repository = mock(SalePaymentSessionRepository.class);
        var service = mock(SalePaymentSessionService.class);
        var incidents = mock(MemberBalanceRecoveryIncidentService.class);
        var finalized = mock(SalePaymentSession.class);
        var cancelled = mock(SalePaymentSession.class);
        var finalizedId = UUID.randomUUID();
        var cancelledId = UUID.randomUUID();
        when(finalized.getId()).thenReturn(finalizedId);
        when(cancelled.getId()).thenReturn(cancelledId);
        when(repository.findMemberBalanceFinalizationRecoveryCandidates(
                any(Instant.class), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(finalized));
        when(repository.findMemberBalanceAbortRecoveryCandidates(
                any(Instant.class), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(cancelled));

        new MemberBalanceCheckoutRecoveryWorker(repository, service, incidents).recover();

        verify(service).recoverMemberBalanceFinalization(finalizedId);
        verify(service).recoverMemberBalanceAbort(cancelledId);
    }

    @Test
    void workerPersistsRecoveryFailureBeforeContinuing() {
        var repository = mock(SalePaymentSessionRepository.class);
        var service = mock(SalePaymentSessionService.class);
        var incidents = mock(MemberBalanceRecoveryIncidentService.class);
        var finalized = mock(SalePaymentSession.class);
        var finalizedId = UUID.randomUUID();
        when(finalized.getId()).thenReturn(finalizedId);
        when(repository.findMemberBalanceFinalizationRecoveryCandidates(
                any(Instant.class), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(finalized));
        when(repository.findMemberBalanceAbortRecoveryCandidates(
                any(Instant.class), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of());
        var failure = new IllegalStateException("saas_unavailable");
        doThrow(failure).when(service).recoverMemberBalanceFinalization(finalizedId);

        new MemberBalanceCheckoutRecoveryWorker(repository, service, incidents).recover();

        verify(incidents).recordFailure(finalizedId, failure);
    }

    private static ReservationFixture reservationFixture() {
        var repository = mock(SalePaymentSessionRepository.class);
        var sales = mock(PosCashService.class);
        var documents = mock(DocumentService.class);
        var snapshots = mock(PosCardDocumentSnapshot.class);
        var methods = mock(PaymentMethodRepository.class);
        var organization = mock(CurrentOrganization.class);
        var terminal = mock(CurrentTerminal.class);
        var configurations = mock(CardTerminalConfigurationReader.class);
        var terminalOperations = mock(PaymentTerminalOperationService.class);
        var cashPayments = mock(CashPaymentRecorder.class);
        var authentication = mock(Authentication.class);
        var protocol = mock(MemberBalanceCheckoutProtocolService.class);
        var company = mock(Company.class);
        var store = mock(Store.class);
        var user = mock(UserAccount.class);
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(user.getId()).thenReturn(userId);
        when(authentication.getPrincipal()).thenReturn(user);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(terminal.terminalId(authentication)).thenReturn(terminalId);
        when(repository.findState(any(UUID.class))).thenReturn(Optional.empty());
        when(repository.findActive(storeId, terminalId, userId)).thenReturn(Optional.empty());
        when(repository.save(any(SalePaymentSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var paymentMethod = new PaymentMethod(companyId, "EFECTIVO", true);
        when(methods.findAllByEmpresaIdOrderByNombre(companyId))
                .thenReturn(List.of(paymentMethod));
        var command = mock(DocumentCommand.class);
        when(command.lineas()).thenReturn(List.of());
        var prepared = new PosCashService.PreparedSale(command, Set.of());
        when(sales.prepareSale(any(PosCashController.SaleRequest.class), eq(authentication)))
                .thenReturn(prepared);
        var discountedQuote = quote(storeId, new BigDecimal("90.00"));
        var fullQuote = quote(storeId, new BigDecimal("100.00"));
        when(sales.quotePreparedSale(
                any(PosCashService.PreparedSale.class),
                any(PosCashController.SaleRequest.class),
                eq(authentication)))
                .thenAnswer(invocation -> {
                    PosCashController.SaleRequest requested = invocation.getArgument(1);
                    return requested.memberBalanceAmount() == null
                            ? fullQuote : discountedQuote;
                });
        var snapshot = mock(ApprovedCardTicketSnapshot.class);
        when(sales.snapshot(any(), any(), any())).thenReturn(snapshot);
        when(snapshots.serialize(snapshot)).thenReturn("{}");
        var service = new SalePaymentSessionService(
                repository, sales, documents, snapshots, methods, organization,
                terminal, configurations, terminalOperations, cashPayments);
        service.setMemberBalanceCheckoutProtocolService(protocol);
        var sale = new PosCashController.SaleRequest(
                null,
                List.of(new PosCashController.LineRequest(
                        UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO)),
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                new BigDecimal("10.00"));
        return new ReservationFixture(
                repository, service, protocol, authentication, sale, storeId, terminalId);
    }

    private static CommercialDocument quote(UUID storeId, BigDecimal total) {
        var quote = mock(CommercialDocument.class);
        when(quote.getTiendaId()).thenReturn(storeId);
        when(quote.getTotal()).thenReturn(total);
        return quote;
    }

    private static RecoveryFixture recoveryFixture() {
        var repository = mock(SalePaymentSessionRepository.class);
        var protocol = mock(MemberBalanceCheckoutProtocolService.class);
        var service = new SalePaymentSessionService(
                repository,
                mock(PosCashService.class),
                mock(DocumentService.class),
                mock(PosCardDocumentSnapshot.class),
                mock(PaymentMethodRepository.class),
                mock(CurrentOrganization.class),
                mock(CurrentTerminal.class),
                mock(CardTerminalConfigurationReader.class),
                mock(PaymentTerminalOperationService.class),
                mock(CashPaymentRecorder.class));
        service.setMemberBalanceCheckoutProtocolService(protocol);
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        var session = SalePaymentSession.reserve(
                UUID.randomUUID(), storeId, terminalId, userId,
                "hash-finalized", "{}", BigDecimal.ZERO);
        session.memberBalancePrepared(reservationId, new BigDecimal("10.00"));
        session.finalizeWith(UUID.randomUUID(), "T-1");
        when(repository.findState(session.getId())).thenReturn(Optional.of(session));
        when(repository.findLocked(session.getId())).thenReturn(Optional.of(session));
        when(repository.save(any(SalePaymentSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new RecoveryFixture(
                repository, service, protocol, session,
                storeId, terminalId, userId, reservationId);
    }

    private record ReservationFixture(
            SalePaymentSessionRepository repository,
            SalePaymentSessionService service,
            MemberBalanceCheckoutProtocolService protocol,
            Authentication authentication,
            PosCashController.SaleRequest sale,
            UUID storeId,
            UUID terminalId) {
    }

    private record RecoveryFixture(
            SalePaymentSessionRepository repository,
            SalePaymentSessionService service,
            MemberBalanceCheckoutProtocolService protocol,
            SalePaymentSession session,
            UUID storeId,
            UUID terminalId,
            UUID userId,
            UUID reservationId) {
    }
}
