package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservation;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservationStatus;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCheckoutProtocolService;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralException;
import com.tpverp.backend.party.MemberLoyaltyService;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.sync.SyncOutboxEvent;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.sync.SyncOutboxStatus;
import com.tpverp.backend.terminal.CardTerminalConfigurationReader;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentTerminalOperationService;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
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
import org.springframework.test.util.ReflectionTestUtils;

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
        var requestedSale = org.mockito.ArgumentCaptor.forClass(PosCashController.SaleRequest.class);
        verify(fixture.sales(), times(2)).prepareSale(
                requestedSale.capture(), eq(fixture.authentication()));
        assertThat(requestedSale.getAllValues().get(0).memberBalanceAmount()).isNull();
        assertThat(requestedSale.getAllValues().get(1).memberBalanceAmount()).isNull();
        verify(fixture.repository).save(session);
        verify(fixture.protocol, never()).abortPrepared(any());
    }

    @Test
    void reservationConflictCreatesSessionWithoutMemberBalanceWithStableCode() {
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
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.CONFLICT,
                        "La reserva pertenece a otra venta"));

        var session = fixture.service.reserve(
                sessionId, fixture.sale, reservationId, fixture.authentication);

        assertThat(session.getTotal()).isEqualByComparingTo("100.00");
        assertThat(session.getMemberBalanceReservationId()).isNull();
        assertThat(session.getMemberBalanceAppliedAmount()).isEqualByComparingTo("0.00");
        assertThat(session.getMemberBalanceFailureCode())
                .isEqualTo("member_balance_unavailable");
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

        var order = inOrder(fixture.sales(), fixture.protocol);
        order.verify(fixture.sales()).prepareSale(
                argThat(request -> request.memberBalanceAmount() == null),
                eq(fixture.authentication()));
        order.verify(fixture.protocol).prepare(
                eq(reservationId), eq(fixture.storeId), eq(fixture.terminalId),
                eq(sessionId.toString()), eq(sessionId), eq(new BigDecimal("10.00")),
                eq(new BigDecimal("0.00")));
        order.verify(fixture.protocol).authorizePreparedLocalConsumption(
                eq(reservationId), eq(fixture.sale.customerId()), eq(new BigDecimal("10.00")),
                eq(new BigDecimal("0.00")));
        order.verify(fixture.sales()).prepareSale(
                argThat(request -> new BigDecimal("10.00").compareTo(request.memberBalanceAmount()) == 0),
                eq(fixture.authentication()));
    }

    @Test
    void authorizeFailureAbortsPreparedReservationAndDoesNotReintroduceF10() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        when(fixture.protocol.prepare(
                reservationId, fixture.storeId, fixture.terminalId, sessionId.toString(), sessionId,
                new BigDecimal("10.00"), new BigDecimal("0.00")))
                .thenReturn(mock(LocalMemberBalanceReservation.class));
        doThrow(new IllegalStateException("central_authorize_failed"))
                .when(fixture.protocol).authorizePreparedLocalConsumption(
                        reservationId, fixture.sale.customerId(), new BigDecimal("10.00"),
                        new BigDecimal("0.00"));

        var session = fixture.service.reserve(
                sessionId, fixture.sale, reservationId, fixture.authentication);

        assertThat(session.getMemberBalanceReservationId()).isNull();
        assertThat(session.getMemberBalanceAppliedAmount()).isEqualByComparingTo("0.00");
        assertThat(session.getMemberBalanceFailureCode()).isEqualTo("member_balance_unavailable");
        verify(fixture.protocol).abortPrepared(reservationId);
        var requestedSale = org.mockito.ArgumentCaptor.forClass(PosCashController.SaleRequest.class);
        verify(fixture.sales(), times(2)).prepareSale(
                requestedSale.capture(), eq(fixture.authentication()));
        assertThat(requestedSale.getAllValues())
                .allMatch(value -> value.memberBalanceAmount() == null);
    }

    @Test
    void normalSaleDoesNotUseF10WhileRetentionClaimsRemainEffective() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        when(fixture.protocol.hasEffectiveRetention(reservationId)).thenReturn(true);

        var session = fixture.service.reserve(
                sessionId, fixture.sale, reservationId, fixture.authentication);

        verify(fixture.protocol, never()).prepare(
                any(), any(), any(), any(), any(), any(), any());
        assertThat(session.getMemberBalanceAppliedAmount()).isEqualByComparingTo("0.00");
        assertThat(session.getMemberBalanceFailureCode()).isEqualTo("member_balance_unavailable");
    }

    @Test
    void mixedExchangeWithPositiveTotalCannotPrepareF10AlongsideReturn() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        when(fixture.protocol.hasEffectiveRetention(reservationId)).thenReturn(true);
        var origin = new PosCashController.ReturnOriginRequest(
                TicketReturnService.ReturnSourceType.TICKET, "T-1", UUID.randomUUID(),
                UUID.randomUUID(), null);
        var returnLine = new PosCashController.LineRequest(
                UUID.randomUUID(), new BigDecimal("-1"), BigDecimal.ZERO, null,
                List.of(), null, origin);
        var mixedSale = new PosCashController.SaleRequest(
                fixture.sale.customerId(), List.of(fixture.sale.lines().getFirst(), returnLine),
                fixture.sale.discountAuthorizationToken(), fixture.sale.promotionalCouponCode(),
                fixture.sale.checkoutDiscountAmount(), fixture.sale.internalComment(),
                fixture.sale.operationAuthorizations(), fixture.sale.previousTicketImport(),
                fixture.sale.quoteFingerprint(), fixture.sale.memberBalanceAmount(),
                fixture.sale.documentDiscountPercent(), fixture.sale.wholesaleMode());
        assertThatThrownBy(() -> fixture.service.reserve(
                sessionId, mixedSale, reservationId, fixture.authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("member_balance_not_allowed_with_return");

        verify(fixture.protocol, never()).prepare(
                any(), any(), any(), any(), any(), any(), any());
        verify(fixture.protocol, never()).requireRetentionConfiguredForReturn(any());
        verify(fixture.repository, never()).save(any(SalePaymentSession.class));
    }

    @Test
    void mixedReturnWithUnconfiguredRetentionCannotUseF10() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        var origin = new PosCashController.ReturnOriginRequest(
                TicketReturnService.ReturnSourceType.TICKET, "T-1", UUID.randomUUID(),
                UUID.randomUUID(), null);
        var returnLine = new PosCashController.LineRequest(
                UUID.randomUUID(), new BigDecimal("-1"), BigDecimal.ZERO, null,
                List.of(), null, origin);
        var mixedSale = new PosCashController.SaleRequest(
                fixture.sale.customerId(), List.of(fixture.sale.lines().getFirst(), returnLine),
                fixture.sale.discountAuthorizationToken(), fixture.sale.promotionalCouponCode(),
                fixture.sale.checkoutDiscountAmount(), fixture.sale.internalComment(),
                fixture.sale.operationAuthorizations(), fixture.sale.previousTicketImport(),
                fixture.sale.quoteFingerprint(), fixture.sale.memberBalanceAmount(),
                fixture.sale.documentDiscountPercent(), fixture.sale.wholesaleMode());
        assertThatThrownBy(() -> fixture.service.reserve(
                sessionId, mixedSale, reservationId, fixture.authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("member_balance_not_allowed_with_return");

        verify(fixture.protocol, never()).prepare(
                any(), any(), any(), any(), any(), any(), any());
        verify(fixture.repository, never()).save(any(SalePaymentSession.class));
    }

    @Test
    void mixedReturnWithoutReservationCannotUseF10() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var origin = new PosCashController.ReturnOriginRequest(
                TicketReturnService.ReturnSourceType.TICKET, "T-1", UUID.randomUUID(),
                UUID.randomUUID(), null);
        var returnLine = new PosCashController.LineRequest(
                UUID.randomUUID(), new BigDecimal("-1"), BigDecimal.ZERO, null,
                List.of(), null, origin);
        var mixedSale = new PosCashController.SaleRequest(
                UUID.randomUUID(), List.of(fixture.sale.lines().getFirst(), returnLine),
                fixture.sale.discountAuthorizationToken(), fixture.sale.promotionalCouponCode(),
                fixture.sale.checkoutDiscountAmount(), fixture.sale.internalComment(),
                fixture.sale.operationAuthorizations(), fixture.sale.previousTicketImport(),
                fixture.sale.quoteFingerprint(), fixture.sale.memberBalanceAmount(),
                fixture.sale.documentDiscountPercent(), fixture.sale.wholesaleMode());

        assertThatThrownBy(() -> fixture.service.reserve(
                sessionId, mixedSale, null, fixture.authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("member_balance_not_allowed_with_return");

        verify(fixture.sales(), never()).prepareSale(any(), any());
        verify(fixture.repository(), never()).save(any(SalePaymentSession.class));
    }

    @Test
    void pureReturnWithZeroF10RequiresRetentionBeforePersistingTheTicket() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        var session = SalePaymentSession.reserve(
                sessionId, fixture.storeId(), fixture.terminalId(), fixture.userId(),
                "pure-return-hash", "snapshot", new BigDecimal("-10.00"));
        session.addAllocation(
                        UUID.randomUUID(), "refund-cash", SalePaymentAllocationKind.CASH,
                        new BigDecimal("10.00"), null, null)
                .approve(null, null, null);
        session.memberWalletLinked(reservationId);
        when(fixture.repository.findLocked(sessionId)).thenReturn(Optional.of(session));

        var returnLine = mock(DocumentLineCommand.class);
        when(returnLine.originalDocumentLineId()).thenReturn(UUID.randomUUID());
        when(returnLine.cantidad()).thenReturn(BigDecimal.ONE.negate());
        var snapshot = mock(ApprovedCardTicketSnapshot.class);
        when(snapshot.lines()).thenReturn(List.of(returnLine));
        when(fixture.snapshots().deserialize("snapshot")).thenReturn(snapshot);
        doThrow(new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.CONFLICT,
                "member_balance_retention_requires_return"))
                .when(fixture.protocol()).requireRetentionConfiguredForReturn(reservationId);

        assertThatThrownBy(() -> fixture.service().finalizeSession(sessionId, fixture.authentication()))
                .isInstanceOf(MemberBalanceCentralException.class)
                .hasMessage("member_balance_retention_requires_return");

        verify(fixture.protocol()).requireRetentionConfiguredForReturn(reservationId);
        verify(fixture.documents(), never()).createApprovedReturn(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refundMemberCreditCreatesCreditWithoutReadingAPreviousWallet() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var session = SalePaymentSession.reserve(
                sessionId, fixture.storeId(), fixture.terminalId(), fixture.userId(),
                "refund-credit-hash", "snapshot", new BigDecimal("-10.00"));
        when(fixture.repository().findLocked(sessionId)).thenReturn(Optional.of(session));
        var sourceTicketId = UUID.randomUUID();
        var sourceLine = mock(DocumentLineCommand.class);
        when(sourceLine.originalDocumentLineId()).thenReturn(UUID.randomUUID());
        when(sourceLine.cantidad()).thenReturn(BigDecimal.ONE.negate());
        when(sourceLine.returnSourceTicketId()).thenReturn(sourceTicketId);
        var snapshot = mock(ApprovedCardTicketSnapshot.class);
        when(snapshot.customerId()).thenReturn(customerId);
        when(snapshot.lines()).thenReturn(List.of(sourceLine));
        when(fixture.snapshots().deserialize("snapshot")).thenReturn(snapshot);
        when(fixture.memberLoyalty().isActiveMember(customerId)).thenReturn(true);
        var source = mock(CommercialDocument.class);
        when(source.getClienteId()).thenReturn(customerId);
        when(fixture.documents().find(sourceTicketId)).thenReturn(source);

        var saved = fixture.service().add(
                sessionId,
                UUID.randomUUID(),
                "refund-member-credit",
                SalePaymentAllocationKind.MEMBER_CREDIT,
                new BigDecimal("10.00"),
                null, null, null, null, null, null, null, null, null,
                fixture.authentication());

        assertThat(saved.getDirection()).isEqualTo(SalePaymentSessionDirection.REFUND);
        assertThat(saved.getAllocations()).singleElement()
                .satisfies(allocation -> {
                    assertThat(allocation.getKind())
                            .isEqualTo(SalePaymentAllocationKind.MEMBER_CREDIT);
                    assertThat(allocation.getAmount()).isEqualByComparingTo("10.00");
                    assertThat(allocation.getStatus())
                            .isEqualTo(PaymentTerminalOperationStatus.APPROVED);
                });
        verify(fixture.memberLoyalty()).isActiveMember(customerId);
        verify(fixture.memberLoyalty(), never()).wallet(customerId);
    }

    @Test
    void refundMemberCreditRejectsCustomerWithoutAnActiveMemberBeforePersisting() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var session = SalePaymentSession.reserve(
                sessionId, fixture.storeId(), fixture.terminalId(), fixture.userId(),
                "refund-credit-inactive-hash", "snapshot", new BigDecimal("-10.00"));
        when(fixture.repository().findLocked(sessionId)).thenReturn(Optional.of(session));
        var sourceTicketId = UUID.randomUUID();
        var sourceLine = mock(DocumentLineCommand.class);
        when(sourceLine.originalDocumentLineId()).thenReturn(UUID.randomUUID());
        when(sourceLine.cantidad()).thenReturn(BigDecimal.ONE.negate());
        when(sourceLine.returnSourceTicketId()).thenReturn(sourceTicketId);
        var snapshot = mock(ApprovedCardTicketSnapshot.class);
        when(snapshot.customerId()).thenReturn(customerId);
        when(snapshot.lines()).thenReturn(List.of(sourceLine));
        when(fixture.snapshots().deserialize("snapshot")).thenReturn(snapshot);
        when(fixture.memberLoyalty().isActiveMember(customerId)).thenReturn(false);
        var source = mock(CommercialDocument.class);
        when(source.getClienteId()).thenReturn(customerId);
        when(fixture.documents().find(sourceTicketId)).thenReturn(source);

        assertThatThrownBy(() -> fixture.service().add(
                sessionId,
                UUID.randomUUID(),
                "refund-member-credit-inactive",
                SalePaymentAllocationKind.MEMBER_CREDIT,
                new BigDecimal("10.00"),
                null, null, null, null, null, null, null, null, null,
                fixture.authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("member_credit_active_member_required");

        verify(fixture.repository(), never()).save(any(SalePaymentSession.class));
        verify(fixture.memberLoyalty()).isActiveMember(customerId);
        verify(fixture.memberLoyalty(), never()).wallet(any());
    }

    @Test
    void refundMemberCreditCannotBeRedirectedToAnotherCustomer() {
        var fixture = reservationFixture();
        var sessionId = UUID.randomUUID();
        var snapshotCustomerId = UUID.randomUUID();
        var sourceCustomerId = UUID.randomUUID();
        var sourceTicketId = UUID.randomUUID();
        var session = SalePaymentSession.reserve(
                sessionId, fixture.storeId(), fixture.terminalId(), fixture.userId(),
                "refund-credit-mismatch-hash", "snapshot", new BigDecimal("-10.00"));
        when(fixture.repository().findLocked(sessionId)).thenReturn(Optional.of(session));
        var sourceLine = mock(DocumentLineCommand.class);
        when(sourceLine.originalDocumentLineId()).thenReturn(UUID.randomUUID());
        when(sourceLine.cantidad()).thenReturn(BigDecimal.ONE.negate());
        when(sourceLine.returnSourceTicketId()).thenReturn(sourceTicketId);
        var snapshot = mock(ApprovedCardTicketSnapshot.class);
        when(snapshot.customerId()).thenReturn(snapshotCustomerId);
        when(snapshot.lines()).thenReturn(List.of(sourceLine));
        when(fixture.snapshots().deserialize("snapshot")).thenReturn(snapshot);
        var source = mock(CommercialDocument.class);
        when(source.getClienteId()).thenReturn(sourceCustomerId);
        when(fixture.documents().find(sourceTicketId)).thenReturn(source);

        assertThatThrownBy(() -> fixture.service().add(
                sessionId, UUID.randomUUID(), "refund-member-credit-mismatch",
                SalePaymentAllocationKind.MEMBER_CREDIT, new BigDecimal("10.00"),
                null, null, null, null, null, null, null, null, null,
                fixture.authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("member_credit_customer_mismatch");

        verify(fixture.memberLoyalty(), never()).isActiveMember(any());
        verify(fixture.repository(), never()).save(any(SalePaymentSession.class));
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
    void committedTicketWithRetentionOnlyUsesRecoveryOutboxWithoutF10Finalization() {
        var fixture = recoveryFixture();
        var session = SalePaymentSession.reserve(
                UUID.randomUUID(), fixture.storeId, fixture.terminalId, fixture.userId,
                "hash-active-zero", "{}", BigDecimal.ZERO);
        session.memberBalancePrepared(fixture.reservationId, BigDecimal.ZERO.setScale(2));
        session.finalizeWith(UUID.randomUUID(), "T-active-zero");
        when(fixture.repository.findState(session.getId())).thenReturn(Optional.of(session));
        when(fixture.repository.findLocked(session.getId())).thenReturn(Optional.of(session));
        when(fixture.protocol.hasEffectiveRetention(fixture.reservationId))
                .thenReturn(true);
        when(fixture.protocol.requiresCentralWalletFinalization(fixture.reservationId))
                .thenReturn(false);

        fixture.service.recoverMemberBalanceFinalization(session.getId());

        verify(fixture.protocol, never()).markTicketCommitted(any(), any());
        verify(fixture.protocol, never()).finalizePrepared(any());
        assertThat(session.getMemberBalanceSynchronizedAt()).isNull();
    }

    @Test
    void queueOfPureReturnRetentionDoesNotMarkSessionSynchronizedBeforeAck() {
        var fixture = recoveryFixture(BigDecimal.ZERO.setScale(2));
        when(fixture.protocol.hasEffectiveRetention(fixture.reservationId)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(
                fixture.service, "queueMemberBalanceFinalization", fixture.session);

        verify(fixture.protocol, never()).markTicketCommitted(any(), any());
        verify(fixture.protocol, never()).abortPrepared(any());
        assertThat(fixture.session.getMemberBalanceSynchronizedAt()).isNull();
    }

    @Test
    void sentReturnRecoveryClosesActiveReservationAfterAck() {
        var fixture = recoveryFixture(BigDecimal.ZERO.setScale(2));
        var closed = mock(LocalMemberBalanceReservation.class);
        when(closed.isClosed()).thenReturn(true);
        when(fixture.protocol.abortPrepared(fixture.reservationId)).thenReturn(closed);
        fixture.session.markMemberBalanceSynchronized(Instant.parse("2026-08-18T12:01:00Z"));
        configureReturnRecoveryEvent(fixture, SyncOutboxStatus.ENVIADO, true);

        fixture.service.recoverMemberBalanceFinalization(fixture.session.getId());

        verify(fixture.protocol).abortPrepared(fixture.reservationId);
        assertThat(fixture.session.getMemberBalanceSynchronizedAt()).isNotNull();
    }

    @Test
    void unsentReturnRecoveryNeverClosesActiveReservation() {
        for (var status : List.of(
                SyncOutboxStatus.PENDIENTE,
                SyncOutboxStatus.ERROR,
                SyncOutboxStatus.DEAD_LETTER)) {
            var fixture = recoveryFixture(BigDecimal.ZERO.setScale(2));
            fixture.session.markMemberBalanceSynchronized(Instant.parse("2026-08-18T12:01:00Z"));
            configureReturnRecoveryEvent(fixture, status, true);

            fixture.service.recoverMemberBalanceFinalization(fixture.session.getId());

            verify(fixture.protocol, never()).abortPrepared(any());
        }
    }

    @Test
    void sentReturnRecoveryWithDifferentOwnerNeverClosesActiveReservation() {
        var fixture = recoveryFixture(BigDecimal.ZERO.setScale(2));
        fixture.session.markMemberBalanceSynchronized(Instant.parse("2026-08-18T12:01:00Z"));
        configureReturnRecoveryEvent(fixture, SyncOutboxStatus.ENVIADO, false);

        fixture.service.recoverMemberBalanceFinalization(fixture.session.getId());

        verify(fixture.protocol, never()).abortPrepared(any());
    }

    @Test
    void sentReturnRecoveryFromDifferentTerminalNeverClosesActiveReservation() {
        var fixture = recoveryFixture(BigDecimal.ZERO.setScale(2));
        fixture.session.markMemberBalanceSynchronized(Instant.parse("2026-08-18T12:01:00Z"));
        configureReturnRecoveryEvent(
                fixture, SyncOutboxStatus.ENVIADO, true, UUID.randomUUID());

        fixture.service.recoverMemberBalanceFinalization(fixture.session.getId());

        verify(fixture.protocol, never()).abortPrepared(any());
    }

    @Test
    void sentReturnRecoveryIsIdempotentWhenRetried() {
        var fixture = recoveryFixture(BigDecimal.ZERO.setScale(2));
        var closed = mock(LocalMemberBalanceReservation.class);
        when(closed.isClosed()).thenReturn(true);
        when(fixture.protocol.abortPrepared(fixture.reservationId)).thenReturn(closed);
        configureReturnRecoveryEvent(fixture, SyncOutboxStatus.ENVIADO, true);

        fixture.service.recoverMemberBalanceFinalization(fixture.session.getId());
        fixture.service.recoverMemberBalanceFinalization(fixture.session.getId());

        verify(fixture.protocol, times(2)).abortPrepared(fixture.reservationId);
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
    void cancelAttemptsMemberBalanceAbortAfterPersistingCancellation() {
        var fixture = reservationFixture();
        var session = SalePaymentSession.reserve(
                UUID.randomUUID(), fixture.storeId(), fixture.terminalId(), fixture.userId(),
                "cancel-hash", "{}", BigDecimal.ZERO);
        var reservationId = UUID.randomUUID();
        session.memberBalancePrepared(reservationId, new BigDecimal("10.00"));
        when(fixture.repository().findLocked(session.getId())).thenReturn(Optional.of(session));
        when(fixture.repository().findState(session.getId())).thenReturn(Optional.of(session));
        var reservation = mock(LocalMemberBalanceReservation.class);
        when(reservation.isClosed()).thenReturn(true);
        when(fixture.protocol().abortPrepared(reservationId)).thenReturn(reservation);

        var cancelled = fixture.service().cancel(session.getId(), fixture.authentication());

        assertThat(cancelled.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
        verify(fixture.protocol()).abortPrepared(reservationId);
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
        var memberLoyalty = mock(MemberLoyaltyService.class);
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
        when(methods.findByEmpresaIdAndNombreAndActivoTrue(companyId, "CREDITO_DEVOLUCION"))
                .thenReturn(Optional.of(new PaymentMethod(companyId, "CREDITO_DEVOLUCION", true)));
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
        service.setMemberLoyaltyService(memberLoyalty);
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
                new BigDecimal("10.00"),
                new BigDecimal("5.00"),
                false);
        return new ReservationFixture(
                repository, service, protocol, sales, documents, snapshots, authentication,
                sale, storeId, terminalId, userId, memberLoyalty);
    }

    private static CommercialDocument quote(UUID storeId, BigDecimal total) {
        var quote = mock(CommercialDocument.class);
        when(quote.getTiendaId()).thenReturn(storeId);
        when(quote.getTotal()).thenReturn(total);
        return quote;
    }

    private static RecoveryFixture recoveryFixture() {
        return recoveryFixture(new BigDecimal("10.00"));
    }

    private static RecoveryFixture recoveryFixture(BigDecimal preparedAmount) {
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
        session.memberBalancePrepared(reservationId, preparedAmount);
        session.finalizeWith(UUID.randomUUID(), "T-1");
        when(repository.findState(session.getId())).thenReturn(Optional.of(session));
        when(repository.findLocked(session.getId())).thenReturn(Optional.of(session));
        when(repository.save(any(SalePaymentSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new RecoveryFixture(
                repository, service, protocol, session,
                storeId, terminalId, userId, reservationId);
    }

    private static void configureReturnRecoveryEvent(
            RecoveryFixture fixture,
            com.tpverp.backend.sync.SyncOutboxStatus status,
            boolean matchingOwner) {
        configureReturnRecoveryEvent(fixture, status, matchingOwner, fixture.terminalId);
    }

    private static void configureReturnRecoveryEvent(
            RecoveryFixture fixture,
            com.tpverp.backend.sync.SyncOutboxStatus status,
            boolean matchingOwner,
            UUID eventTerminalId) {
        var company = mock(Company.class);
        var store = mock(Store.class);
        var companyId = UUID.randomUUID();
        var centralReservationId = UUID.randomUUID();
        var memberId = UUID.randomUUID();
        when(company.getId()).thenReturn(companyId);
        when(store.getEmpresa()).thenReturn(company);
        var stores = mock(StoreRepository.class);
        when(stores.findWithCompanyById(fixture.storeId)).thenReturn(Optional.of(store));
        var event = mock(SyncOutboxEvent.class);
        when(event.getStatus()).thenReturn(status);
        when(event.getEntityId()).thenReturn(fixture.session.getId());
        when(event.getStoreId()).thenReturn(fixture.storeId);
        when(event.getTerminalId()).thenReturn(eventTerminalId);
        when(event.getPayload()).thenReturn(Map.of(
                "storeId", fixture.storeId.toString(),
                "memberId", memberId.toString(),
                "reservationId", centralReservationId.toString(),
                "reservationSaleId", fixture.session.getId().toString()));
        var outbox = mock(SyncOutboxService.class);
        when(outbox.latest(companyId, fixture.storeId,
                "MEMBER_RETURN_BALANCE_RECOVERY", fixture.session.getId()))
                .thenReturn(Optional.of(event));
        when(fixture.protocol.retentionSnapshotBelongsTo(
                eq(fixture.reservationId), any(), anyString(), any()))
                .thenReturn(matchingOwner);
        fixture.service.setRetentionRecoveryOutbox(
                outbox, new com.fasterxml.jackson.databind.ObjectMapper());
        fixture.service.setRetentionRecoveryStores(stores);
    }

    private record ReservationFixture(
            SalePaymentSessionRepository repository,
            SalePaymentSessionService service,
            MemberBalanceCheckoutProtocolService protocol,
            PosCashService sales,
            DocumentService documents,
            PosCardDocumentSnapshot snapshots,
            Authentication authentication,
            PosCashController.SaleRequest sale,
            UUID storeId,
            UUID terminalId,
            UUID userId,
            MemberLoyaltyService memberLoyalty) {
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
