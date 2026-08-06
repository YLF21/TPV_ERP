package com.tpverp.backend.document;

import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentTerminalOperationService;
import com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundSettlementRecorder {

    private final DocumentService documents;
    private final RefundTenderRepository tenders;
    private final CashPaymentRecorder cash;
    private final CurrentTerminal currentTerminal;
    private final PaymentTerminalOperationService terminalOperations;
    private final Clock clock;

    public RefundSettlementRecorder(
            DocumentService documents,
            RefundTenderRepository tenders,
            CashPaymentRecorder cash,
            CurrentTerminal currentTerminal,
            PaymentTerminalOperationService terminalOperations,
            Clock clock) {
        this.documents = documents;
        this.tenders = tenders;
        this.cash = cash;
        this.currentTerminal = currentTerminal;
        this.terminalOperations = terminalOperations;
        this.clock = clock;
    }

    @Transactional
    public CommercialDocument record(
            UUID requestId,
            UUID originalDocumentId,
            BigDecimal total,
            List<PaymentTerminalRefundLineSelection> lines,
            List<TenderCommand> payouts,
            Authentication authentication) {
        return record(
                requestId,
                originalDocumentId,
                total,
                lines,
                payouts,
                null,
                authentication);
    }

    @Transactional
    public CommercialDocument record(
            UUID requestId,
            UUID originalDocumentId,
            BigDecimal total,
            List<PaymentTerminalRefundLineSelection> lines,
            List<TenderCommand> payouts,
            TicketReturnValuationService.Valuation valuation,
            Authentication authentication) {
        var firstCardOperation = payouts.stream()
                .filter(value -> value.type() == RefundTenderType.CARD)
                .map(TenderCommand::terminalOperationId)
                .findFirst().orElse(null);
        var refund = documents.createApprovedReturn(
                requestId,
                originalDocumentId,
                total,
                lines,
                firstCardOperation,
                valuation,
                authentication);
        if (tenders.findByRefundDocumentIdOrderByCreatedAtAsc(refund.getId()).isEmpty()) {
            for (var payout : payouts) {
                tenders.save(new RefundTender(
                        refund,
                        payout.type(),
                        payout.amount(),
                        payout.originalPaymentId(),
                        payout.terminalOperationId(),
                        payout.reference(),
                        Instant.now(clock)));
            }
            var cashAmount = payouts.stream()
                    .filter(value -> value.type() == RefundTenderType.CASH)
                    .map(TenderCommand::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            cash.recordRefund(currentTerminal.terminalId(authentication), refund, cashAmount);
            for (var payout : payouts) {
                if (payout.type() == RefundTenderType.CARD) {
                    terminalOperations.linkDocument(payout.terminalOperationId(), refund.getId(), null);
                }
            }
        }
        return refund;
    }

    public record TenderCommand(
            RefundTenderType type,
            BigDecimal amount,
            UUID originalPaymentId,
            UUID terminalOperationId,
            String reference) {
    }

    @Transactional
    public CommercialDocument recordExistingNegativeTicket(
            CommercialDocument refund,
            List<TenderCommand> payouts,
            Authentication authentication) {
        if (refund == null || refund.getTotal().signum() >= 0) {
            throw new IllegalArgumentException("refund_document_must_be_negative");
        }
        var expected = refund.getTotal().abs();
        var actual = payouts.stream().map(TenderCommand::amount)
                .map(Money::euros).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Money.euros(actual).compareTo(expected) != 0) {
            throw new IllegalArgumentException("refund_settlement_total_mismatch");
        }
        if (!tenders.findByRefundDocumentIdOrderByCreatedAtAsc(refund.getId()).isEmpty()) {
            return refund;
        }
        for (var payout : payouts) {
            tenders.save(new RefundTender(
                    refund,
                    payout.type(),
                    payout.amount(),
                    payout.originalPaymentId(),
                    payout.terminalOperationId(),
                    payout.reference(),
                    Instant.now(clock)));
        }
        var cashAmount = payouts.stream()
                .filter(value -> value.type() == RefundTenderType.CASH)
                .map(TenderCommand::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        cash.recordRefund(currentTerminal.terminalId(authentication), refund, cashAmount);
        payouts.stream()
                .filter(value -> value.type() == RefundTenderType.CARD
                        && value.terminalOperationId() != null)
                .forEach(value -> terminalOperations.linkDocument(
                        value.terminalOperationId(), refund.getId(), null));
        return refund;
    }
}
