package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.cash.CashPaymentRecorder;
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
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class TicketReturnServiceTest {

    @Mock DocumentService documents;
    @Mock PaymentTerminalOperationsService terminalPayments;
    @Mock RefundSettlementRecorder settlements;
    @Mock RefundTenderRepository tenders;
    @Mock CashPaymentRecorder cash;
    @Mock CurrentTerminal currentTerminal;
    @Mock Authentication authentication;

    private TicketReturnService service;
    private UUID ticketId;
    private UUID requestId;
    private UUID terminalId;
    private CommercialDocument refundDocument;

    @BeforeEach
    void setUp() {
        service = new TicketReturnService(documents, terminalPayments, settlements, tenders, cash, currentTerminal);
        ticketId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        terminalId = UUID.randomUUID();
        refundDocument = mock(CommercialDocument.class);
        lenient().when(refundDocument.getId()).thenReturn(UUID.randomUUID());
        lenient().when(currentTerminal.terminalId(authentication)).thenReturn(terminalId);
        lenient().when(settlements.record(eq(requestId), eq(ticketId), any(), any(), any(), eq(authentication)))
                .thenReturn(refundDocument);
        lenient().when(tenders.findByRefundDocumentIdOrderByCreatedAtAsc(refundDocument.getId())).thenReturn(List.of());
    }

    @Test
    void cashReturnRequiresOpenDrawerAndRecordsCashPayout() {
        var result = service.create(ticketId, requestId, new BigDecimal("12.10"), List.of(), List.of(), authentication);

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

        service.create(ticketId, requestId, new BigDecimal("5.00"),
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

        assertThatThrownBy(() -> service.create(ticketId, requestId, BigDecimal.ZERO,
                List.of(new TicketReturnService.CardPayout(
                        paymentId, UUID.randomUUID(), "key", BigDecimal.TEN)),
                List.of(), authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pertenece");

        verify(terminalPayments, never()).refundPaymentOnly(any(), any(), any(), any());
        verify(settlements, never()).record(any(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<List<RefundSettlementRecorder.TenderCommand>> payoutCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
