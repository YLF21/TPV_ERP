package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.DocumentRelationRepository;
import com.tpverp.backend.document.DocumentRelationType;
import com.tpverp.backend.document.TicketReturnValuationService;
import com.tpverp.backend.document.SalePaymentSession;
import com.tpverp.backend.document.SalePaymentSessionRepository;
import com.tpverp.backend.document.SalePaymentSessionStatus;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LocalMemberBalanceReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private final UUID storeId = UUID.randomUUID();
    private final UUID terminalId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private LocalMemberBalanceReservationRepository reservations;
    private MemberBalanceReservationCoordinator coordinator;
    private LocalMemberBalanceReservationService service;

    @BeforeEach
    void setUp() {
        reservations = mock(LocalMemberBalanceReservationRepository.class);
        TerminalRepository terminals = mock(TerminalRepository.class);
        coordinator = mock(MemberBalanceReservationCoordinator.class);
        when(terminals.findByIdAndTiendaId(terminalId, storeId)).thenReturn(Optional.of(mock(Terminal.class)));
        when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new LocalMemberBalanceReservationService(
                reservations,
                terminals,
                coordinator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persisteLaReservaCentralConSuLease() {
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-1")).thenReturn(Optional.empty());
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-1"))
                .thenReturn(central("ACTIVE"));

        LocalMemberBalanceReservation reservation = service.reserve(
                storeId, terminalId, memberId, "sale-1");

        assertThat(reservation.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.ACTIVE);
        assertThat(reservation.getReservedTotal()).isEqualByComparingTo("10.00");
        assertThat(reservation.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void reservaCentralNoEncontradaNoSeTrataComoLiberacionIdempotente() {
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-reserve-404")).thenReturn(Optional.empty());
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-reserve-404"))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED, 404,
                        "Reserva de saldo socio no encontrada"));

        assertThatThrownBy(() -> service.reserve(
                storeId, terminalId, memberId, "sale-reserve-404"))
                .isInstanceOf(MemberBalanceCentralException.class)
                .satisfies(error -> assertThat(((MemberBalanceCentralException) error).getStatusCode())
                        .isEqualTo(404));
    }

    @Test
    void conservaAliasLegacyComoLoyaltyPeroExponeTotalTipadoMixto() {
        var central = new MemberBalanceCentralGateway.ReservationResponse(
                UUID.randomUUID(), memberId, "ACTIVE", new BigDecimal("13.21"), new BigDecimal("4.42"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("12.99"), new BigDecimal("4.42"), List.of(), NOW,
                NOW.plusSeconds(120), 30, 120);

        var reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-mixed-account", central, NOW);

        assertThat(reservation.getAccountBalance()).isEqualByComparingTo("17.41");
        assertThat(reservation.getAccountLoyaltyBalance()).isEqualByComparingTo("12.99");
        assertThat(reservation.getAccountReturnCreditBalance()).isEqualByComparingTo("4.42");
        assertThat((BigDecimal) ReflectionTestUtils.getField(reservation, "accountBalance"))
                .isEqualByComparingTo("12.99");
    }

    @Test
    void liberacionSinConexionQuedaPendienteSinBloquearElCierreLocal() {
        LocalMemberBalanceReservation reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-1", central("ACTIVE"), NOW);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(coordinator.release(
                reservation.getCentralReservationId(), storeId, terminalId, "sale-1"))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.UNAVAILABLE,
                        "sin conexion"));

        LocalMemberBalanceReservation released = service.release(
                reservation.getId(), storeId, terminalId, "sale-1");

        assertThat(released.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.RELEASE_PENDING);
    }

    @Test
    void liberacionCentralNoEncontradaSeConsideraCerradaDeFormaIdempotente() {
        LocalMemberBalanceReservation reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-404", central("ACTIVE"), NOW);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(coordinator.release(
                reservation.getCentralReservationId(), storeId, terminalId, "sale-404"))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED, 404,
                        "Reserva de saldo socio no encontrada"));

        LocalMemberBalanceReservation released = service.release(
                reservation.getId(), storeId, terminalId, "sale-404");

        assertThat(released.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.RELEASED);
        assertThat(released.isClosed()).isTrue();
        verify(reservations).save(reservation);
    }

    @Test
    void releasePreparedKeepsLocalStateAndDoesNotCallCentral() {
        LocalMemberBalanceReservation reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-prepared", central("PREPARED"), NOW);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        LocalMemberBalanceReservation result = service.release(
                reservation.getId(), storeId, terminalId, "sale-prepared");

        assertThat(result.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.PREPARED);
        verify(coordinator, never()).release(any(), any(), any(), any());
        verify(reservations, never()).save(any());
    }

    @Test
    void releaseTicketCommittedKeepsProtocolStateAndDoesNotCallCentral() {
        LocalMemberBalanceReservation reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-ticket", central("PREPARED"), NOW);
        UUID ticketId = UUID.randomUUID();
        reservation.markTicketCommitted(ticketId, NOW);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        LocalMemberBalanceReservation result = service.release(
                reservation.getId(), storeId, terminalId, "sale-ticket");

        assertThat(result.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.TICKET_COMMITTED);
        assertThat(result.getTicketId()).isEqualTo(ticketId);
        verify(coordinator, never()).release(any(), any(), any(), any());
        verify(reservations, never()).save(any());
    }

    @Test
    void applyConservaLaFotoJsonDeLotesParaCapacidadOffline() {
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        MemberBalanceCentralGateway.ReservationResponse central =
                new MemberBalanceCentralGateway.ReservationResponse(
                        UUID.randomUUID(), memberId, "ACTIVE", new BigDecimal("10.00"),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), null, BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), new BigDecimal("10.00"),
                        BigDecimal.ZERO.setScale(2), java.util.List.of(
                                new MemberBalanceCentralGateway.ReservedLot(
                                        com.tpverp.backend.party.MemberBalanceLotType.LOYALTY,
                                        lotId, new BigDecimal("0.09"), NOW, null,
                                        movementId, sourceDocumentId)),
                        List.of(new MemberBalanceCentralGateway.RetentionClaim(
                                lotId, movementId, sourceDocumentId,
                                new BigDecimal("0.09"), new BigDecimal("0.09"),
                                new BigDecimal("0.09"))), NOW,
                        NOW.plusSeconds(120), 30, 120, 1L, "f", new BigDecimal("0.09"),
                        new BigDecimal("0.09"), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), new BigDecimal("9.91"),
                        BigDecimal.ZERO.setScale(2));

        LocalMemberBalanceReservation reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-1", central, NOW);

        assertThat(reservation.getRetentionReservedLots()).hasSize(1);
        assertThat(reservation.getRetentionReservedLots().getFirst().heldAmount())
                .isEqualByComparingTo(".09");
        assertThat(reservation.retentionSnapshotKnownAmount(lotId, new BigDecimal(".20")))
                .isEqualByComparingTo(".09");
        var view = LocalMemberBalanceReservationController.ReservationView.from(reservation);
        assertThat(view.reservedLots()).singleElement()
                .extracting(LocalMemberBalanceReservationController.ReservedLotView::heldAmount)
                .isEqualTo(new BigDecimal(".09"));
    }

    @Test
    void sourceWithoutSettlementClearsCentralSnapshotWithCanonicalZero() {
        var reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-no-loyalty", central("ACTIVE"), NOW);
        UUID sourceId = UUID.randomUUID();
        var source = mock(CommercialDocument.class);
        when(source.getId()).thenReturn(sourceId);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        var documents = mock(CommercialDocumentRepository.class);
        when(documents.findByIdAndTiendaId(sourceId, storeId)).thenReturn(Optional.of(source));
        var valuation = mock(TicketReturnValuationService.class);
        when(valuation.value(any(), any())).thenReturn(new TicketReturnValuationService.Valuation(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        var planner = mock(MemberReturnBalanceRetentionPlanner.class);
        when(planner.plan(any(), any(), any())).thenReturn(
                MemberReturnBalanceRetentionPlanner.Plan.none(sourceId));
        service.setRetentionDependencies(documents, valuation, planner);
        when(coordinator.configureRetention(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(central(reservation.getCentralReservationId(), "ACTIVE"));

        var result = service.configureRetention(
                reservation.getId(), storeId, terminalId, "sale-no-loyalty", sourceId,
                List.of(new PaymentTerminalRefundLineSelection(UUID.randomUUID(), new BigDecimal("1.000"))));

        assertThat(result).isSameAs(reservation);
        assertThat(result.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.ACTIVE);
        verify(coordinator).configureRetention(
                eq(reservation.getCentralReservationId()), eq(storeId), eq(terminalId),
                eq("sale-no-loyalty"), any(), eq(sourceId), eq(new BigDecimal("0.00")), eq(List.of()));
    }

    @Test
    void emptySelectionAlwaysSendsCanonicalZeroClearForRetrySafety() {
        var reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-empty", central("ACTIVE"), NOW);
        UUID sourceId = UUID.randomUUID();
        var source = mock(CommercialDocument.class);
        when(source.getId()).thenReturn(sourceId);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        var documents = mock(CommercialDocumentRepository.class);
        when(documents.findByIdAndTiendaId(sourceId, storeId)).thenReturn(Optional.of(source));
        service.setRetentionDependencies(documents, mock(TicketReturnValuationService.class),
                mock(MemberReturnBalanceRetentionPlanner.class));
        when(coordinator.configureRetention(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(central(reservation.getCentralReservationId(), "ACTIVE"));

        service.configureRetention(
                reservation.getId(), storeId, terminalId, "sale-empty", sourceId, List.of());

        verify(coordinator).configureRetention(
                eq(reservation.getCentralReservationId()), eq(storeId), eq(terminalId),
                eq("sale-empty"), any(), eq(sourceId), eq(new BigDecimal("0.00")), eq(List.of()));
    }

    @Test
    void zeroPlanAfterPreviousRetentionClearsCentralClaims() {
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        UUID centralId = UUID.randomUUID();
        when(reservation.matches(storeId, terminalId, "sale-cleared")).thenReturn(true);
        when(reservation.isActive()).thenReturn(true);
        when(reservation.getRetentionRevision()).thenReturn(1L);
        when(reservation.getCentralReservationId()).thenReturn(centralId);
        when(reservations.findForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        var sourceId = UUID.randomUUID();
        var source = mock(CommercialDocument.class);
        when(source.getId()).thenReturn(sourceId);
        var documents = mock(CommercialDocumentRepository.class);
        when(documents.findByIdAndTiendaId(sourceId, storeId)).thenReturn(Optional.of(source));
        var valuation = mock(TicketReturnValuationService.class);
        when(valuation.value(any(), any())).thenReturn(new TicketReturnValuationService.Valuation(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        var planner = mock(MemberReturnBalanceRetentionPlanner.class);
        when(planner.plan(any(), any(), any())).thenReturn(
                MemberReturnBalanceRetentionPlanner.Plan.none(sourceId));
        when(coordinator.configureRetention(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(central("ACTIVE"));
        service.setRetentionDependencies(documents, valuation, planner);

        service.configureRetention(reservationId, storeId, terminalId, "sale-cleared", sourceId, List.of());

        verify(coordinator).configureRetention(eq(centralId), eq(storeId), eq(terminalId),
                eq("sale-cleared"), any(), eq(sourceId), eq(new BigDecimal("0.00")), eq(List.of()));
        verify(reservation).apply(any(), any());
    }

    @Test
    void sourceMemberAWithReservationMemberBIsRejectedWithoutClearingClaims() {
        var reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-member-b", central("ACTIVE"), NOW);
        UUID sourceId = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        var source = mock(CommercialDocument.class);
        when(source.getId()).thenReturn(sourceId);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        var documents = mock(CommercialDocumentRepository.class);
        when(documents.findByIdAndTiendaId(sourceId, storeId)).thenReturn(Optional.of(source));
        var valuation = mock(TicketReturnValuationService.class);
        when(valuation.value(any(), any())).thenReturn(new TicketReturnValuationService.Valuation(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        var claim = new MemberBalanceCentralGateway.RetentionClaim(
                UUID.randomUUID(), UUID.randomUUID(), sourceId, new BigDecimal("1.00"),
                new BigDecimal("1.00"));
        var plan = new MemberReturnBalanceRetentionPlanner.Plan(
                sourceId, memberB, new BigDecimal("1.00"), List.of(claim),
                MemberReturnBalanceRetentionPlanner.fingerprint(
                        sourceId, new BigDecimal("1.00"), List.of(claim)));
        var planner = mock(MemberReturnBalanceRetentionPlanner.class);
        when(planner.plan(any(), any(), any())).thenReturn(plan);
        when(coordinator.configureRetention(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(central(reservation.getCentralReservationId(), "ACTIVE"));
        service.setRetentionDependencies(documents, valuation, planner);

        assertThatThrownBy(() -> service.configureRetention(
                reservation.getId(), storeId, terminalId, "sale-member-b", sourceId,
                List.of(new PaymentTerminalRefundLineSelection(UUID.randomUUID(), new BigDecimal("1.00")))))
                .isInstanceOf(MemberBalanceCentralException.class)
                .hasMessage("member_balance_retention_member_mismatch");

        verify(coordinator, never()).configureRetention(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aggregateRetentionWithoutSerializedClaimsIsRejectedAsIncompatible() {
        UUID reservationId = UUID.randomUUID();
        var central = new MemberBalanceCentralGateway.ReservationResponse(
                UUID.randomUUID(), memberId, "ACTIVE", new BigDecimal("10.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10.00"), BigDecimal.ZERO, List.of(), List.of(), NOW,
                NOW.plusSeconds(120), 30, 120, 1L, "fingerprint", new BigDecimal("0.04"),
                new BigDecimal("0.04"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("9.96"),
                BigDecimal.ZERO);

        assertThatThrownBy(() -> LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-contract", central, NOW))
                .isInstanceOf(MemberBalanceCentralException.class)
                .hasMessage("member_balance_retention_contract_incompatible");
    }

    @Test
    void settledSalesInvoiceUsesOriginTicketForLoyaltyRetentionClaims() {
        var reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-invoice", central("ACTIVE"), NOW);
        UUID invoiceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        var invoice = mock(CommercialDocument.class);
        var ticket = mock(CommercialDocument.class);
        when(invoice.getId()).thenReturn(invoiceId);
        when(invoice.getTipo()).thenReturn(CommercialDocumentType.FACTURA_VENTA);
        when(invoice.isSettledByOrigin()).thenReturn(true);
        when(ticket.getId()).thenReturn(ticketId);
        when(ticket.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        var documents = mock(CommercialDocumentRepository.class);
        when(documents.findByIdAndTiendaId(invoiceId, storeId)).thenReturn(Optional.of(invoice));
        when(documents.findByIdAndTiendaId(ticketId, storeId)).thenReturn(Optional.of(ticket));
        var relations = mock(DocumentRelationRepository.class);
        when(relations.findOriginId(invoiceId, DocumentRelationType.FACTURA_DE))
                .thenReturn(Optional.of(ticketId));
        var valuation = mock(TicketReturnValuationService.class);
        when(valuation.value(eq(invoice), any())).thenReturn(new TicketReturnValuationService.Valuation(
                new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1.00"), new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                List.of()));
        var claim = new MemberBalanceCentralGateway.RetentionClaim(
                UUID.randomUUID(), movementId, ticketId, new BigDecimal("1.00"),
                new BigDecimal(".50"));
        var plan = new MemberReturnBalanceRetentionPlanner.Plan(
                ticketId, memberId, new BigDecimal(".50"), List.of(claim),
                MemberReturnBalanceRetentionPlanner.fingerprint(ticketId, new BigDecimal(".50"), List.of(claim)));
        var planner = mock(MemberReturnBalanceRetentionPlanner.class);
        when(planner.plan(eq(ticket), any(), any())).thenReturn(plan);
        when(coordinator.configureRetention(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(centralWithRetention(reservation.getCentralReservationId()));
        service.setRetentionDependencies(documents, valuation, planner);
        service.setDocumentRelations(relations);

        var result = service.configureRetention(
                reservation.getId(), storeId, terminalId, "sale-invoice", invoiceId,
                List.of(new PaymentTerminalRefundLineSelection(UUID.randomUUID(), new BigDecimal("1.00"))));

        assertThat(result.getRetentionAttributedAmount()).isEqualByComparingTo("0.50");
        assertThat(result.getRetentionHeldKnown()).isEqualByComparingTo("0.50");
        assertThat(result.getRetentionSpendable()).isEqualByComparingTo("9.50");
        verify(valuation).value(eq(invoice), any());
        verify(planner).plan(eq(ticket), eq(new BigDecimal("1.00")), eq(new BigDecimal("1.00")));
        verify(coordinator).configureRetention(
                eq(reservation.getCentralReservationId()), eq(storeId), eq(terminalId),
                eq("sale-invoice"), any(), eq(ticketId), eq(new BigDecimal(".50")), eq(List.of(claim)));
    }

    @Test
    void settledSalesInvoiceRejectsFacturaDeOriginThatIsNotATicket() {
        var reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-invalid-origin", central("ACTIVE"), NOW);
        UUID invoiceId = UUID.randomUUID();
        UUID originId = UUID.randomUUID();
        var invoice = mock(CommercialDocument.class);
        var nonTicketOrigin = mock(CommercialDocument.class);
        when(invoice.getId()).thenReturn(invoiceId);
        when(invoice.getTipo()).thenReturn(CommercialDocumentType.FACTURA_VENTA);
        when(invoice.isSettledByOrigin()).thenReturn(true);
        when(nonTicketOrigin.getId()).thenReturn(originId);
        when(nonTicketOrigin.getTipo()).thenReturn(CommercialDocumentType.FACTURA_VENTA);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        var documents = mock(CommercialDocumentRepository.class);
        when(documents.findByIdAndTiendaId(invoiceId, storeId)).thenReturn(Optional.of(invoice));
        when(documents.findByIdAndTiendaId(originId, storeId)).thenReturn(Optional.of(nonTicketOrigin));
        var relations = mock(DocumentRelationRepository.class);
        when(relations.findOriginId(invoiceId, DocumentRelationType.FACTURA_DE))
                .thenReturn(Optional.of(originId));
        service.setRetentionDependencies(documents, mock(TicketReturnValuationService.class),
                mock(MemberReturnBalanceRetentionPlanner.class));
        service.setDocumentRelations(relations);

        assertThatThrownBy(() -> service.configureRetention(
                reservation.getId(), storeId, terminalId, "sale-invalid-origin", invoiceId, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no apunta a un ticket");
        verify(coordinator, never()).configureRetention(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void zeroPlanClearUnavailableIsReportedSoCheckoutCanDisableF10() {
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        UUID centralId = UUID.randomUUID();
        when(reservation.matches(storeId, terminalId, "sale-offline-clear")).thenReturn(true);
        when(reservation.isActive()).thenReturn(true);
        when(reservation.getRetentionRevision()).thenReturn(1L);
        when(reservation.getCentralReservationId()).thenReturn(centralId);
        when(reservations.findForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        var sourceId = UUID.randomUUID();
        var source = mock(CommercialDocument.class);
        when(source.getId()).thenReturn(sourceId);
        var documents = mock(CommercialDocumentRepository.class);
        when(documents.findByIdAndTiendaId(sourceId, storeId)).thenReturn(Optional.of(source));
        var valuation = mock(TicketReturnValuationService.class);
        var planner = mock(MemberReturnBalanceRetentionPlanner.class);
        when(coordinator.configureRetention(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.UNAVAILABLE, "offline"));
        service.setRetentionDependencies(documents, valuation, planner);

        assertThatThrownBy(() -> service.configureRetention(
                reservationId, storeId, terminalId, "sale-offline-clear", sourceId, List.of()))
                .isInstanceOf(MemberBalanceCentralException.class);
        verify(valuation, never()).value(any(), any());
        verify(planner, never()).plan(any(), any(), any());
    }

    @Test
    void retryRecuperaLaMismaVentaDeFormaIdempotente() {
        var reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-retry", central("ACTIVE"), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(reservation));
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-retry")).thenReturn(Optional.of(reservation));
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-retry"))
                .thenReturn(central(reservation.getCentralReservationId(), "ACTIVE"));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-retry");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERED);
        assertThat(result.reservationId()).isEqualTo(reservation.getId());
    }

    @Test
    void retryLiberaReservaActiveSinSesionYRecuperaLaVentaActual() {
        var orphan = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-orphan",
                central("ACTIVE", NOW.minusSeconds(70)), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(orphan));
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-current")).thenReturn(Optional.empty());
        when(coordinator.release(orphan.getCentralReservationId(), storeId, terminalId, "sale-orphan"))
                .thenReturn(central(orphan.getCentralReservationId(), "RELEASED"));
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-current"))
                .thenReturn(central("ACTIVE"));
        var sessions = mock(SalePaymentSessionRepository.class);
        var audit = mock(AuditService.class);
        when(sessions.findBlockingMemberBalanceSessionsByReservationIds(List.of(orphan.getId())))
                .thenReturn(List.of());
        service.setRetryDependencies(sessions, audit, mock(com.tpverp.backend.document.SalePaymentSessionService.class));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-current");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERED);
        assertThat(orphan.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.RELEASED);
        verify(coordinator).release(orphan.getCentralReservationId(), storeId, terminalId, "sale-orphan");
    }

    @Test
    void retryRecuperaReservaActiveConSesionFinalizadaAntesDeLiberarla() {
        var active = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-active-finalized", central("ACTIVE"), NOW);
        var closed = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-active-finalized",
                central(active.getCentralReservationId(), "RELEASED"), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(active));
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-after-finalized")).thenReturn(Optional.empty());
        when(reservations.findById(active.getId())).thenReturn(Optional.of(closed));
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-after-finalized"))
                .thenReturn(central("ACTIVE"));

        var session = mock(SalePaymentSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getStoreId()).thenReturn(storeId);
        when(session.getTerminalId()).thenReturn(terminalId);
        when(session.getStatus()).thenReturn(SalePaymentSessionStatus.FINALIZED);
        when(session.getTicketId()).thenReturn(UUID.randomUUID());
        var sessions = mock(SalePaymentSessionRepository.class);
        when(sessions.findFirstByMemberBalanceReservationIdOrderByUpdatedAtDesc(active.getId()))
                .thenReturn(Optional.of(session));
        var paymentService = mock(com.tpverp.backend.document.SalePaymentSessionService.class);
        service.setRetryDependencies(sessions, mock(AuditService.class), paymentService);

        var result = service.resolveRetry(
                storeId, terminalId, memberId, "sale-after-finalized");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERED);
        verify(paymentService).recoverMemberBalanceFinalization(session.getId());
        verify(coordinator, never()).release(any(), any(), any(), any());
    }

    @Test
    void retryRecuperaReservaActiveConSesionCanceladaAntesDeLiberarla() {
        var active = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-active-cancelled", central("ACTIVE"), NOW);
        var closed = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-active-cancelled",
                central(active.getCentralReservationId(), "RELEASED"), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(active));
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-after-cancelled")).thenReturn(Optional.empty());
        when(reservations.findById(active.getId())).thenReturn(Optional.of(closed));
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-after-cancelled"))
                .thenReturn(central("ACTIVE"));

        var session = mock(SalePaymentSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getStoreId()).thenReturn(storeId);
        when(session.getTerminalId()).thenReturn(terminalId);
        when(session.getStatus()).thenReturn(SalePaymentSessionStatus.CANCELLED);
        var sessions = mock(SalePaymentSessionRepository.class);
        when(sessions.findFirstByMemberBalanceReservationIdOrderByUpdatedAtDesc(active.getId()))
                .thenReturn(Optional.of(session));
        var paymentService = mock(com.tpverp.backend.document.SalePaymentSessionService.class);
        service.setRetryDependencies(sessions, mock(AuditService.class), paymentService);

        var result = service.resolveRetry(
                storeId, terminalId, memberId, "sale-after-cancelled");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERED);
        verify(paymentService).recoverMemberBalanceAbort(session.getId());
        verify(coordinator, never()).release(any(), any(), any(), any());
    }

    @Test
    void retryNoLiberaReservaLigadaACobroVivo() {
        var live = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-live", central("ACTIVE"), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(live));
        var session = mock(SalePaymentSession.class);
        when(session.getStatus()).thenReturn(SalePaymentSessionStatus.COLLECTING);
        var sessions = mock(SalePaymentSessionRepository.class);
        when(sessions.findBlockingMemberBalanceSessionsByReservationIds(List.of(live.getId())))
                .thenReturn(List.of(session));
        service.setRetryDependencies(sessions, null, mock(com.tpverp.backend.document.SalePaymentSessionService.class));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-current");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.BLOCKED_LIVE_SALE);
        verify(coordinator, never()).release(any(), any(), any(), any());
    }

    @Test
    void retryEsFailClosedSiNoPuedeConsultarSesiones() {
        var orphan = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-orphan-no-dependency", central("ACTIVE"), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(orphan));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-current");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.BLOCKED_LIVE_SALE);
        verify(coordinator, never()).release(any(), any(), any(), any());
    }

    @Test
    void retryAuditaReservaAnteriorYNuevaEnUnTakeover() {
        var orphan = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-orphan-audited",
                central("ACTIVE", NOW.minusSeconds(70)), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(orphan));
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-audited")).thenReturn(Optional.empty());
        when(coordinator.release(orphan.getCentralReservationId(), storeId, terminalId, orphan.getSaleId()))
                .thenReturn(central(orphan.getCentralReservationId(), "RELEASED"));
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-audited"))
                .thenReturn(central("ACTIVE"));
        var sessions = mock(SalePaymentSessionRepository.class);
        var audit = mock(AuditService.class);
        when(sessions.findBlockingMemberBalanceSessionsByReservationIds(List.of(orphan.getId())))
                .thenReturn(List.of());
        service.setRetryDependencies(sessions, audit, mock(com.tpverp.backend.document.SalePaymentSessionService.class));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-audited");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERED);
        verify(audit).record(eq("MEMBER_BALANCE_RETRY_RESOLUTION"), eq(AuditResult.EXITO),
                org.mockito.ArgumentMatchers.argThat(details ->
                        "RECOVERED".equals(details.get("outcome"))
                                && orphan.getId().toString().equals(details.get("oldReservationId"))
                                && orphan.getSaleId().equals(details.get("oldSaleId"))
                                && "sale-audited".equals(details.get("newSaleId"))
                                && storeId.toString().equals(details.get("storeId"))
                                && terminalId.toString().equals(details.get("terminalId"))
                                && memberId.toString().equals(details.get("memberId"))));
    }

    @Test
    void retryRecuperaFinalizacionDeReservaConTicketConfirmado() {
        var prepared = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-finalized", central("PREPARED"), NOW);
        prepared.markTicketCommitted(UUID.randomUUID(), NOW);
        var closed = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-finalized", central(prepared.getCentralReservationId(), "CONSUMED"), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(prepared));
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-next")).thenReturn(Optional.empty());
        when(reservations.findById(prepared.getId())).thenReturn(Optional.of(closed));
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-next"))
                .thenReturn(central("ACTIVE"));
        var session = mock(SalePaymentSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getStoreId()).thenReturn(storeId);
        when(session.getTerminalId()).thenReturn(terminalId);
        when(session.getStatus()).thenReturn(SalePaymentSessionStatus.FINALIZED);
        when(session.getTicketId()).thenReturn(UUID.randomUUID());
        var sessions = mock(SalePaymentSessionRepository.class);
        when(sessions.findFirstByMemberBalanceReservationIdOrderByUpdatedAtDesc(prepared.getId()))
                .thenReturn(Optional.of(session));
        var paymentService = mock(com.tpverp.backend.document.SalePaymentSessionService.class);
        service.setRetryDependencies(sessions, null, paymentService);

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-next");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERED);
        verify(paymentService).recoverMemberBalanceFinalization(session.getId());
    }

    @Test
    void retryRecuperaAbortoDeReservaConSesionCancelada() {
        var prepared = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-cancelled", central("PREPARED"), NOW);
        var closed = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-cancelled", central(prepared.getCentralReservationId(), "RELEASED"), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(prepared));
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-next-abort")).thenReturn(Optional.empty());
        when(reservations.findById(prepared.getId())).thenReturn(Optional.of(closed));
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-next-abort"))
                .thenReturn(central("ACTIVE"));
        var session = mock(SalePaymentSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getStoreId()).thenReturn(storeId);
        when(session.getTerminalId()).thenReturn(terminalId);
        when(session.getStatus()).thenReturn(SalePaymentSessionStatus.CANCELLED);
        when(session.getTicketId()).thenReturn(null);
        var sessions = mock(SalePaymentSessionRepository.class);
        when(sessions.findFirstByMemberBalanceReservationIdOrderByUpdatedAtDesc(prepared.getId()))
                .thenReturn(Optional.of(session));
        var paymentService = mock(com.tpverp.backend.document.SalePaymentSessionService.class);
        service.setRetryDependencies(sessions, null, paymentService);

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-next-abort");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERED);
        verify(paymentService).recoverMemberBalanceAbort(session.getId());
    }

    @Test
    void retryNoConfundeUn409NoTipadoConOtraTerminal() {
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of());
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-409")).thenReturn(Optional.empty());
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-409"))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.CONFLICT, 409, "conflicto no tipado"));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-409");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.UNAVAILABLE);
    }

    @Test
    void retryNoPresentaComoOtraTerminalUnConflictoTipadoSinPropietarioVerificable() {
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of());
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-typed-409")).thenReturn(Optional.empty());
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-typed-409"))
                .thenThrow(new MemberBalanceReservationConflictException(
                        "reserva en conflicto", 409, null));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-typed-409");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("member_balance_retry_blocking_owner_unidentified");
    }

    @Test
    void retryNoTomaActiveRecienteComoHuerfana() {
        var active = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-recent",
                central("ACTIVE", NOW.minusSeconds(10)), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(active));
        var sessions = mock(SalePaymentSessionRepository.class);
        when(sessions.findBlockingMemberBalanceSessionsByReservationIds(List.of(active.getId())))
                .thenReturn(List.of());
        service.setRetryDependencies(
                sessions, mock(AuditService.class), mock(com.tpverp.backend.document.SalePaymentSessionService.class));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-current");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.BLOCKED_LIVE_SALE);
        assertThat(result.reservationId()).isNull();
        assertThat(result.saleId()).isEqualTo("sale-current");
        assertThat(result.blockingReservationId()).isEqualTo(active.getId());
        assertThat(result.blockingSaleId()).isEqualTo(active.getSaleId());
        assertThat(result.message()).isEqualTo("member_balance_retry_recent_reservation");
        verify(coordinator, never()).release(any(), any(), any(), any());
        verify(coordinator, never()).reserve(any(), any(), any(), any());
    }

    @Test
    void retryReleasePendingReintentaLiberacionCentralYRecuperaVentaActual() {
        var pending = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-release-pending",
                central("ACTIVE"), NOW);
        pending.markReleasePending(NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(pending));
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-after-release")).thenReturn(Optional.empty());
        when(coordinator.release(pending.getCentralReservationId(), storeId, terminalId,
                pending.getSaleId())).thenReturn(central(pending.getCentralReservationId(), "RELEASED"));
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-after-release"))
                .thenReturn(central("ACTIVE"));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-after-release");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERED);
        assertThat(pending.isClosed()).as("status=%s result=%s message=%s",
                pending.getStatus(), result.outcome(), result.message()).isTrue();
        verify(coordinator).release(pending.getCentralReservationId(), storeId, terminalId,
                pending.getSaleId());
    }

    @Test
    void retryReleasePendingSinConexionPermanecePendiente() {
        var pending = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-release-pending-offline",
                central("ACTIVE"), NOW);
        pending.markReleasePending(NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(pending));
        when(coordinator.release(pending.getCentralReservationId(), storeId, terminalId,
                pending.getSaleId())).thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.UNAVAILABLE, "offline"));

        var result = service.resolveRetry(storeId, terminalId, memberId, "sale-current-offline");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERY_PENDING);
        assertThat(result.message()).isEqualTo("member_balance_retry_release_pending");
        assertThat(pending.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.RELEASE_PENDING);
        verify(coordinator, never()).reserve(any(), any(), any(), any());
    }

    @Test
    void retryRecuperaUnaReservaPreparadaDeOtraVentaAntesDeReservarLaActual() {
        var prepared = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-old-prepared", central("PREPARED"), NOW);
        var closed = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-old-prepared",
                central(prepared.getCentralReservationId(), "RELEASED"), NOW);
        when(reservations.findForRetry(eq(storeId), eq(terminalId), eq(memberId), any()))
                .thenReturn(List.of(prepared));
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-current-after-recovery")).thenReturn(Optional.empty());
        when(reservations.findById(prepared.getId())).thenReturn(Optional.of(closed));
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-current-after-recovery"))
                .thenReturn(central("ACTIVE"));
        var session = mock(SalePaymentSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getStoreId()).thenReturn(storeId);
        when(session.getTerminalId()).thenReturn(terminalId);
        when(session.getStatus()).thenReturn(SalePaymentSessionStatus.CANCELLED);
        var sessions = mock(SalePaymentSessionRepository.class);
        when(sessions.findFirstByMemberBalanceReservationIdOrderByUpdatedAtDesc(prepared.getId()))
                .thenReturn(Optional.of(session));
        var paymentService = mock(com.tpverp.backend.document.SalePaymentSessionService.class);
        service.setRetryDependencies(sessions, null, paymentService);

        var result = service.resolveRetry(
                storeId, terminalId, memberId, "sale-current-after-recovery");

        assertThat(result.outcome()).isEqualTo(LocalMemberBalanceReservationService.RetryOutcome.RECOVERED);
        verify(paymentService).recoverMemberBalanceAbort(session.getId());
    }

    private MemberBalanceCentralGateway.ReservationResponse central(String status) {
        return central(UUID.randomUUID(), status);
    }

    private MemberBalanceCentralGateway.ReservationResponse central(
            String status, Instant heartbeatAt) {
        return central(UUID.randomUUID(), status, heartbeatAt);
    }

    private MemberBalanceCentralGateway.ReservationResponse central(UUID reservationId, String status) {
        return central(reservationId, status, NOW);
    }

    private MemberBalanceCentralGateway.ReservationResponse central(
            UUID reservationId, String status, Instant heartbeatAt) {
        return new MemberBalanceCentralGateway.ReservationResponse(
                reservationId,
                memberId,
                status,
                new BigDecimal("10.00"),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                null,
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("10.00"),
                BigDecimal.ZERO.setScale(2),
                java.util.List.of(),
                heartbeatAt,
                NOW.plusSeconds(120),
                30,
                120);
    }

    private MemberBalanceCentralGateway.ReservationResponse centralWithRetention(UUID reservationId) {
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        return new MemberBalanceCentralGateway.ReservationResponse(
                reservationId, memberId, "ACTIVE", new BigDecimal("10.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10.00"), BigDecimal.ZERO, List.of(new MemberBalanceCentralGateway.ReservedLot(
                        com.tpverp.backend.party.MemberBalanceLotType.LOYALTY, lotId,
                        new BigDecimal("0.50"), NOW, null, movementId, sourceDocumentId)),
                List.of(new MemberBalanceCentralGateway.RetentionClaim(
                        lotId, movementId, sourceDocumentId, new BigDecimal("0.50"),
                        new BigDecimal("0.50"), new BigDecimal("0.50"))), NOW, NOW.plusSeconds(120),
                30, 120, 1L, "retention-fingerprint", new BigDecimal("0.50"),
                new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("9.50"),
                BigDecimal.ZERO);
    }
}
