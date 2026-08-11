package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentTerminalOperation;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalOperationsService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class TicketReturnServiceTest {

    @Mock DocumentService documents;
    @Mock PaymentTerminalOperationsService terminalPayments;
    @Mock RefundSettlementRecorder settlements;
    @Mock RefundTenderRepository tenders;
    @Mock CashPaymentRecorder cash;
    @Mock CurrentTerminal currentTerminal;
    @Mock VoucherService vouchers;
    @Mock SaleOperationSecurityService operationSecurity;
    @Mock AuditService audit;
    @Mock Authentication authentication;
    @Mock TicketCancellationOperationRepository cancellations;

    private TicketReturnService service;
    private UUID ticketId;
    private UUID requestId;
    private UUID terminalId;
    private CommercialDocument refundDocument;

    @BeforeEach
    void setUp() {
        service = new TicketReturnService(
                documents, terminalPayments, settlements, tenders, cash,
                currentTerminal, vouchers, cancellations,
                operationSecurity, audit);
        ticketId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        terminalId = UUID.randomUUID();
        refundDocument = mock(CommercialDocument.class);
        lenient().when(refundDocument.getId()).thenReturn(UUID.randomUUID());
        lenient().when(currentTerminal.terminalId(authentication)).thenReturn(terminalId);
        var operator = mock(UserAccount.class);
        lenient().when(operator.getId()).thenReturn(UUID.randomUUID());
        lenient().when(operator.getUserName()).thenReturn("CAJERO");
        lenient().when(operationSecurity.authorize(
                        eq(SaleOperationCode.RETURN_TICKET),
                        any(),
                        any(),
                        eq(authentication)))
                .thenReturn(new Authorization(operator, operator, false));
        lenient().when(settlements.record(eq(requestId), eq(ticketId), any(), any(), any(), eq(authentication)))
                .thenReturn(refundDocument);
        lenient().when(tenders.findByRefundDocumentIdOrderByCreatedAtAsc(refundDocument.getId())).thenReturn(List.of());
    }

    @Test
    void cashReturnRequiresOpenDrawerAndRecordsCashPayout() {
        var result = service.create(ticketId, requestId, new BigDecimal("12.10"), BigDecimal.ZERO, List.of(), List.of(), authentication);

        verify(documents).validateApprovedCardRefund(ticketId, new BigDecimal("12.10"), List.of());
        verify(cash).requireOpenSession(terminalId);
        verify(terminalPayments, never()).refundPaymentOnly(any(), any(), any(), any());
        var payouts = payoutCaptor();
        verify(settlements).record(eq(requestId), eq(ticketId), eq(new BigDecimal("12.10")), eq(List.of()), payouts.capture(), eq(authentication));
        assertThat(payouts.getValue()).singleElement().satisfies(payout -> {
            assertThat(payout.type()).isEqualTo(RefundTenderType.CASH);
            assertThat(payout.amount()).isEqualByComparingTo("12.10");
        });
        assertThat(result.document()).isSameAs(refundDocument);
    }

    @Test
    void salesInvoiceReturnUsesInvoiceSecurityAndItsOriginPaymentSource() {
        var invoice = mock(CommercialDocument.class);
        var originTicket = mock(CommercialDocument.class);
        var originTicketId = UUID.randomUUID();
        var operator = mock(UserAccount.class);
        when(invoice.getTipo()).thenReturn(CommercialDocumentType.FACTURA_VENTA);
        when(originTicket.getId()).thenReturn(originTicketId);
        when(documents.find(ticketId)).thenReturn(invoice);
        when(documents.returnPaymentSource(ticketId)).thenReturn(originTicket);
        when(operationSecurity.authorize(
                eq(SaleOperationCode.RETURN_SALES_INVOICE), any(), any(), eq(authentication)))
                .thenReturn(new Authorization(operator, operator, false));
        when(operator.getId()).thenReturn(UUID.randomUUID());
        when(operator.getUserName()).thenReturn("SUPERVISOR");

        service.create(ticketId, requestId, new BigDecimal("25.00"),
                BigDecimal.ZERO, List.of(), List.of(), authentication);

        verify(operationSecurity).authorize(
                eq(SaleOperationCode.RETURN_SALES_INVOICE), any(), any(), eq(authentication));
        verify(documents).validateApprovedCardRefund(
                ticketId, new BigDecimal("25.00"), List.of());
        verify(cash).requireOpenSession(terminalId);
    }

    @Test
    void invoicePreviewResolvesWithoutThrowingAProbeExceptionInsideTheTransaction() {
        var invoiceNumber = "FV-001-26-000019";
        var invoiceId = UUID.randomUUID();
        var invoice = mock(CommercialDocument.class);
        var originTicket = mock(CommercialDocument.class);
        when(invoice.getId()).thenReturn(invoiceId);
        when(invoice.getNumero()).thenReturn(invoiceNumber);
        when(originTicket.getPagos()).thenReturn(List.of());
        when(documents.findTicketForReturnByNumber(invoiceNumber)).thenReturn(Optional.empty());
        when(documents.invoiceForReturnByNumber(invoiceNumber)).thenReturn(invoice);
        when(documents.returnPaymentSource(invoiceId)).thenReturn(originTicket);
        when(documents.cardRefundLineOptions(invoiceId)).thenReturn(List.of());

        var preview = service.preview(invoiceNumber);

        assertThat(preview.sourceType()).isEqualTo(TicketReturnService.ReturnSourceType.SALES_INVOICE);
        assertThat(preview.sourceCode()).isEqualTo(invoiceNumber);
        verify(documents, never()).ticketForReturnByNumber(any());
    }

    @Test
    void historicalPromotionCanConfirmZeroValueReturnWithoutPayout() {
        var valuations = mock(TicketReturnValuationService.class);
        service.setTicketReturnValuationService(valuations);
        var original = mock(CommercialDocument.class);
        var lineId = UUID.randomUUID();
        var selected = List.of(new com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection(
                lineId, BigDecimal.ONE));
        var valuation = new TicketReturnValuationService.Valuation(
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("20.00"),
                List.of(new TicketReturnValuationService.TaxAdjustment(
                        true, "IVA", new BigDecimal("21.00"),
                        new BigDecimal("10.00"))));
        when(documents.find(ticketId)).thenReturn(original);
        when(valuations.value(eq(original), any())).thenReturn(valuation);
        when(settlements.record(
                eq(requestId),
                eq(ticketId),
                eq(new BigDecimal("0.00")),
                eq(selected),
                eq(List.of()),
                eq(valuation),
                eq(authentication)))
                .thenReturn(refundDocument);

        var result = service.create(
                ticketId,
                requestId,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(),
                selected,
                authentication);

        verify(documents, never()).validateApprovedCardRefund(any(), any(), any());
        verify(cash, never()).requireOpenSession(any());
        assertThat(result.document()).isSameAs(refundDocument);
    }

    @Test
    void mixedReturnCombinesApprovedCardAndCashInOneSettlement() {
        var paymentId = UUID.randomUUID();
        var originalOperationId = UUID.randomUUID();
        var refundOperationId = UUID.randomUUID();
        var original = mock(PaymentTerminalOperation.class);
        var approvedRefund = mock(PaymentTerminalOperation.class);
        when(original.getId()).thenReturn(originalOperationId);
        when(original.getDocumentId()).thenReturn(ticketId);
        when(terminalPayments.findByDocumentPaymentId(paymentId)).thenReturn(Optional.of(original));
        when(terminalPayments.refundPaymentOnly(originalOperationId, refundOperationId, "key", new BigDecimal("7.10")))
                .thenReturn(approvedRefund);
        when(approvedRefund.getStatus()).thenReturn(PaymentTerminalOperationStatus.APPROVED);
        when(approvedRefund.getId()).thenReturn(refundOperationId);

        service.create(ticketId, requestId, new BigDecimal("5.00"), BigDecimal.ZERO,
                List.of(new TicketReturnService.CardPayout(paymentId, refundOperationId, "key", new BigDecimal("7.10"))),
                List.of(), authentication);

        var payouts = payoutCaptor();
        verify(settlements).record(eq(requestId), eq(ticketId), eq(new BigDecimal("12.10")), eq(List.of()), payouts.capture(), eq(authentication));
        assertThat(payouts.getValue()).extracting(RefundSettlementRecorder.TenderCommand::type)
                .containsExactly(RefundTenderType.CASH, RefundTenderType.CARD);
    }

    @Test
    void rejectsCardOperationFromAnotherTicketBeforeSendingRefund() {
        var paymentId = UUID.randomUUID();
        var original = mock(PaymentTerminalOperation.class);
        when(original.getDocumentId()).thenReturn(UUID.randomUUID());
        when(terminalPayments.findByDocumentPaymentId(paymentId)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.create(ticketId, requestId, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new TicketReturnService.CardPayout(
                        paymentId, UUID.randomUUID(), "key", BigDecimal.TEN)),
                List.of(), authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pertenece");

        verify(terminalPayments, never()).refundPaymentOnly(any(), any(), any(), any());
        verify(settlements, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void voucherReturnIssuesStoreCreditWithoutOpeningTheCashDrawer() {
        var voucher = mock(Voucher.class);
        when(vouchers.issueOrFindFromNegativeTicket(refundDocument, new BigDecimal("12.10")))
                .thenReturn(voucher);

        var result = service.create(ticketId, requestId, BigDecimal.ZERO, new BigDecimal("12.10"),
                List.of(), List.of(), authentication);

        verify(cash, never()).requireOpenSession(any());
        verify(terminalPayments, never()).refundPaymentOnly(any(), any(), any(), any());
        var payouts = payoutCaptor();
        verify(settlements).record(eq(requestId), eq(ticketId), eq(new BigDecimal("12.10")),
                eq(List.of()), payouts.capture(), eq(authentication));
        assertThat(payouts.getValue()).singleElement().satisfies(payout -> {
            assertThat(payout.type()).isEqualTo(RefundTenderType.VOUCHER);
            assertThat(payout.amount()).isEqualByComparingTo("12.10");
        });
        assertThat(result.voucher()).contains(voucher);
    }

    @Test
    void authorizesAndAuditsOperatorAndAuthorizerBeforeRecordingReturn() {
        service.create(
                ticketId,
                requestId,
                new BigDecimal("12.10"),
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                "ENCARGADO",
                "super-secret",
                authentication);

        var order = inOrder(operationSecurity, audit, settlements);
        order.verify(operationSecurity).authorize(
                SaleOperationCode.RETURN_TICKET,
                "ENCARGADO",
                "super-secret",
                authentication);
        @SuppressWarnings("unchecked")
        var details = ArgumentCaptor.forClass(java.util.Map.class);
        order.verify(audit).record(
                eq("TICKET_RETURN_AUTHORIZED"),
                eq(com.tpverp.backend.audit.AuditResult.EXITO),
                details.capture());
        order.verify(settlements).record(
                eq(requestId), eq(ticketId), any(), any(), any(), eq(authentication));
        assertThat(details.getValue())
                .containsKeys(
                        "operatorId",
                        "operatorUsername",
                        "authorizerId",
                        "authorizerUsername",
                        "delegated")
                .doesNotContainValue("super-secret");
    }

    @Test
    void deniedReturnPolicyDoesNotMutateMoneyOrFiscalDocument() {
        when(operationSecurity.authorize(
                        SaleOperationCode.RETURN_TICKET,
                        null,
                        "wrong",
                        authentication))
                .thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(() -> service.create(
                ticketId,
                requestId,
                BigDecimal.ZERO,
                new BigDecimal("12.10"),
                List.of(),
                List.of(),
                null,
                "wrong",
                authentication))
                .isInstanceOf(AccessDeniedException.class);

        verify(audit, never()).record(any(), any(), any());
        verify(terminalPayments, never()).refundPaymentOnly(any(), any(), any(), any());
        verify(settlements, never()).record(any(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<List<RefundSettlementRecorder.TenderCommand>> payoutCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
