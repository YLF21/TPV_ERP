package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CardTerminalConfigurationReader;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentTerminalOperationService;
import com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class SalePaymentPositiveExchangeTest {

    @Test
    void positiveExchangeCreatesRectificationAndSaleInTheSameFinalization() {
        var sessions = mock(SalePaymentSessionRepository.class);
        var sales = mock(PosCashService.class);
        var documents = mock(DocumentService.class);
        var snapshots = mock(PosCardDocumentSnapshot.class);
        var methods = mock(PaymentMethodRepository.class);
        var organization = mock(CurrentOrganization.class);
        var currentTerminal = mock(CurrentTerminal.class);
        var configurations = mock(CardTerminalConfigurationReader.class);
        var operations = mock(PaymentTerminalOperationService.class);
        var cashPayments = mock(CashPaymentRecorder.class);
        var valuations = mock(TicketReturnValuationService.class);
        var settlements = mock(RefundSettlementRecorder.class);
        var auth = mock(Authentication.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var user = mock(UserAccount.class);
        var storeId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();
        var sourceTicketId = UUID.randomUUID();
        var sourceLineId = UUID.randomUUID();
        var cash = new PaymentMethod(companyId, "EFECTIVO", true);
        var compensation = new PaymentMethod(
                companyId, PaymentMethodService.EXCHANGE_COMPENSATION_METHOD, true);

        when(auth.getPrincipal()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(currentTerminal.terminalId(auth)).thenReturn(terminalId);

        var session = SalePaymentSession.reserve(
                sessionId, storeId, terminalId, userId, "hash", "{}",
                new BigDecimal("1.10"));
        session.addAllocation(
                        UUID.randomUUID(), "cash", SalePaymentAllocationKind.CASH,
                        new BigDecimal("1.10"), null, null)
                .approve(null, null, null);
        when(sessions.findLocked(sessionId)).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(methods.findByEmpresaIdAndNombreAndActivoTrue(companyId, "EFECTIVO"))
                .thenReturn(Optional.of(cash));
        when(methods.findByEmpresaIdAndNombreAndActivoTrue(
                companyId, PaymentMethodService.EXCHANGE_COMPENSATION_METHOD))
                .thenReturn(Optional.of(compensation));

        var returned = new DocumentLineCommand(
                UUID.randomUUID(), BigDecimal.ONE.negate(), "OLD", "Devuelto", "VENTA",
                new BigDecimal("100.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21"), DocumentLineType.PRODUCT, null, null, null,
                List.of(), false, false, TicketReturnService.ReturnSourceType.TICKET,
                "001-260804-00001", sourceTicketId, sourceLineId, null);
        var sold = new DocumentLineCommand(
                UUID.randomUUID(), BigDecimal.ONE, "NEW", "Comprado", "VENTA",
                new BigDecimal("101.10"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21"), DocumentLineType.PRODUCT, null, null, null,
                List.of(), false, false, null, null, UUID.randomUUID(), null, null);
        var snapshot = new ApprovedCardTicketSnapshot(
                storeId, UUID.randomUUID(), LocalDate.of(2026, 8, 5), null,
                cash.getId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1.10"), List.of(returned, sold));
        when(snapshots.deserialize("{}")).thenReturn(snapshot);

        var original = mock(CommercialDocument.class);
        when(documents.find(sourceTicketId)).thenReturn(original);
        var valuation = new TicketReturnValuationService.Valuation(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        when(valuations.value(eq(original), any())).thenReturn(valuation);

        var refund = confirmedDocument("001-260805-00001", new BigDecimal("-100.00"));
        var sale = confirmedDocument("001-260805-00002", new BigDecimal("101.10"));
        var cashPayment = mock(DocumentPayment.class);
        when(cashPayment.getMetodoPago()).thenReturn(cash);
        when(cashPayment.getImporte()).thenReturn(new BigDecimal("1.10"));
        when(sale.getPagos()).thenReturn(List.of(cashPayment));
        when(documents.createApprovedReturn(
                eq(sessionId), eq(sourceTicketId), eq(new BigDecimal("100.00")),
                anyList(), eq(null), eq(valuation), eq(auth))).thenReturn(refund);
        when(documents.createApprovedExchangeSaleFromSnapshot(
                eq(snapshot), anyList(), eq(refund), eq(auth))).thenReturn(sale);
        var exchangePrint = TicketPrintView.fromExchange(sale, refund);
        when(documents.ticketPrintViewFromExchange(sale, refund)).thenReturn(exchangePrint);
        when(settlements.recordExistingNegativeTicket(
                eq(refund), anyList(), eq(auth))).thenReturn(refund);

        var service = new SalePaymentSessionService(
                sessions, sales, documents, snapshots, methods, organization,
                currentTerminal, configurations, operations, cashPayments);
        service.setTicketReturnValuationService(valuations);
        service.setRefundSettlementRecorder(settlements);

        var result = service.finalizeSession(sessionId, auth);

        assertThat(result.session().getTicketId()).isEqualTo(sale.getId());
        assertThat(result.printTicket().total()).isEqualByComparingTo("1.10");
        verify(documents).createApprovedExchangeSaleFromSnapshot(
                eq(snapshot), anyList(), eq(refund), eq(auth));
        verify(settlements).recordExistingNegativeTicket(eq(refund), anyList(), eq(auth));
    }

    @Test
    void zeroValuedPromotionalReturnDoesNotCreateZeroCompensationPayment() {
        var sessions = mock(SalePaymentSessionRepository.class);
        var sales = mock(PosCashService.class);
        var documents = mock(DocumentService.class);
        var snapshots = mock(PosCardDocumentSnapshot.class);
        var methods = mock(PaymentMethodRepository.class);
        var organization = mock(CurrentOrganization.class);
        var currentTerminal = mock(CurrentTerminal.class);
        var configurations = mock(CardTerminalConfigurationReader.class);
        var operations = mock(PaymentTerminalOperationService.class);
        var cashPayments = mock(CashPaymentRecorder.class);
        var valuations = mock(TicketReturnValuationService.class);
        var settlements = mock(RefundSettlementRecorder.class);
        var auth = mock(Authentication.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var user = mock(UserAccount.class);
        var storeId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();
        var sourceTicketId = UUID.randomUUID();
        var sourceLineId = UUID.randomUUID();
        var cash = new PaymentMethod(companyId, "EFECTIVO", true);

        when(auth.getPrincipal()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(currentTerminal.terminalId(auth)).thenReturn(terminalId);

        var session = SalePaymentSession.reserve(
                sessionId, storeId, terminalId, userId, "hash", "{}",
                new BigDecimal("5.70"));
        session.addAllocation(
                        UUID.randomUUID(), "cash", SalePaymentAllocationKind.CASH,
                        new BigDecimal("5.70"), null, null)
                .approve(null, null, null);
        when(sessions.findLocked(sessionId)).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(methods.findByEmpresaIdAndNombreAndActivoTrue(companyId, "EFECTIVO"))
                .thenReturn(Optional.of(cash));

        var returned = new DocumentLineCommand(
                UUID.randomUUID(), BigDecimal.ONE.negate(), "OLD", "Devuelto", "VENTA",
                new BigDecimal("8.20"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21"), DocumentLineType.PRODUCT, null, null, null,
                List.of(), false, false, TicketReturnService.ReturnSourceType.TICKET,
                "001-260806-00003", sourceTicketId, sourceLineId, null);
        var sold = new DocumentLineCommand(
                UUID.randomUUID(), BigDecimal.ONE, "NEW", "Comprado", "VENTA",
                new BigDecimal("5.70"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21"), DocumentLineType.PRODUCT, null, null, null,
                List.of(), false, false, null, null, UUID.randomUUID(), null, null);
        var adjustment = new DocumentLineCommand(
                null, BigDecimal.ONE, "AJUSTE", "Ajuste por perdida de promocion", "AJUSTE",
                new BigDecimal("8.20"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21"), DocumentLineType.RETURN_ADJUSTMENT,
                null, null, null, List.of());
        var snapshot = new ApprovedCardTicketSnapshot(
                storeId, UUID.randomUUID(), LocalDate.of(2026, 8, 6), null,
                cash.getId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("5.70"), List.of(returned, sold, adjustment));
        when(snapshots.deserialize("{}")).thenReturn(snapshot);

        var original = mock(CommercialDocument.class);
        when(documents.find(sourceTicketId)).thenReturn(original);
        var valuation = new TicketReturnValuationService.Valuation(
                new BigDecimal("8.20"), new BigDecimal("8.20"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        when(valuations.value(eq(original), any())).thenReturn(valuation);

        var refund = confirmedDocument("001-260806-00004", BigDecimal.ZERO);
        var sale = confirmedDocument("001-260806-00005", new BigDecimal("5.70"));
        var cashPayment = mock(DocumentPayment.class);
        when(cashPayment.getMetodoPago()).thenReturn(cash);
        when(cashPayment.getImporte()).thenReturn(new BigDecimal("5.70"));
        when(sale.getPagos()).thenReturn(List.of(cashPayment));
        when(documents.createApprovedReturn(
                eq(sessionId), eq(sourceTicketId),
                argThat(amount -> amount.compareTo(BigDecimal.ZERO) == 0),
                anyList(), eq(null), eq(valuation), eq(auth))).thenReturn(refund);
        when(documents.createApprovedExchangeSaleFromSnapshot(
                eq(snapshot), anyList(), eq(refund), eq(auth))).thenReturn(sale);

        var service = new SalePaymentSessionService(
                sessions, sales, documents, snapshots, methods, organization,
                currentTerminal, configurations, operations, cashPayments);
        service.setTicketReturnValuationService(valuations);
        service.setRefundSettlementRecorder(settlements);

        var result = service.finalizeSession(sessionId, auth);

        assertThat(result.session().getTicketId()).isEqualTo(sale.getId());
        verify(methods, never()).findByEmpresaIdAndNombreAndActivoTrue(
                companyId, PaymentMethodService.EXCHANGE_COMPENSATION_METHOD);
        verify(documents).createApprovedExchangeSaleFromSnapshot(
                eq(snapshot),
                argThat(payments -> payments.size() == 1
                        && payments.getFirst().metodoPagoId().equals(cash.getId())
                        && payments.getFirst().importe().compareTo(new BigDecimal("5.70")) == 0),
                eq(refund), eq(auth));
    }

    private static CommercialDocument confirmedDocument(String number, BigDecimal total) {
        var document = mock(CommercialDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getNumero()).thenReturn(number);
        when(document.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);
        when(document.getConfirmadoEn()).thenReturn(Instant.parse("2026-08-05T10:00:00Z"));
        when(document.getTotal()).thenReturn(total);
        when(document.getBaseTotal()).thenReturn(total);
        when(document.getImpuestoTotal()).thenReturn(BigDecimal.ZERO);
        when(document.getLineas()).thenReturn(List.of());
        when(document.getPagos()).thenReturn(List.of());
        return document;
    }
}
