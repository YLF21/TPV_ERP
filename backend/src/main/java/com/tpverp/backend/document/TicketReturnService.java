package com.tpverp.backend.document;

import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalOperationsService;
import com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class TicketReturnService {

    private final DocumentService documents;
    private final PaymentTerminalOperationsService terminalPayments;
    private final RefundSettlementRecorder settlements;
    private final RefundTenderRepository tenders;
    private final CashPaymentRecorder cash;
    private final CurrentTerminal currentTerminal;

    public TicketReturnService(
            DocumentService documents,
            PaymentTerminalOperationsService terminalPayments,
            RefundSettlementRecorder settlements,
            RefundTenderRepository tenders,
            CashPaymentRecorder cash,
            CurrentTerminal currentTerminal) {
        this.documents = documents;
        this.terminalPayments = terminalPayments;
        this.settlements = settlements;
        this.tenders = tenders;
        this.cash = cash;
        this.currentTerminal = currentTerminal;
    }

    public ReturnResult create(
            UUID ticketId,
            UUID requestId,
            BigDecimal cashAmount,
            List<CardPayout> requestedCards,
            List<PaymentTerminalRefundLineSelection> lines,
            Authentication authentication) {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(requestId, "requestId");
        var cashValue = cashAmount == null ? Money.euros(BigDecimal.ZERO) : Money.euros(cashAmount);
        if (cashValue.signum() < 0) throw new IllegalArgumentException("El efectivo no puede ser negativo");
        var cards = requestedCards == null ? List.<CardPayout>of() : List.copyOf(requestedCards);
        var selectedLines = lines == null ? List.<PaymentTerminalRefundLineSelection>of() : List.copyOf(lines);
        var seenPayments = new HashSet<UUID>();
        var total = cashValue;
        for (var card : cards) {
            Objects.requireNonNull(card, "card");
            if (!seenPayments.add(card.originalPaymentId())) {
                throw new IllegalArgumentException("Un pago de tarjeta no puede reembolsarse dos veces en la misma solicitud");
            }
            var value = Money.euros(card.amount());
            if (value.signum() <= 0) throw new IllegalArgumentException("El importe de tarjeta debe ser positivo");
            total = total.add(value);
        }
        total = Money.euros(total);
        if (total.signum() <= 0) throw new IllegalArgumentException("Se requiere un importe de devolucion");
        documents.validateApprovedCardRefund(ticketId, total, selectedLines);

        // Preflight cash before sending any irreversible request to the acquirer.
        if (cashValue.signum() > 0) {
            cash.requireOpenSession(currentTerminal.terminalId(authentication));
        }

        var recorded = new ArrayList<RefundSettlementRecorder.TenderCommand>();
        if (cashValue.signum() > 0) {
            recorded.add(new RefundSettlementRecorder.TenderCommand(
                    RefundTenderType.CASH, cashValue, null, null, null));
        }
        for (var card : cards) {
            var original = terminalPayments.findByDocumentPaymentId(card.originalPaymentId())
                    .orElseThrow(() -> new IllegalArgumentException("El pago no tiene una operacion de datafono"));
            if (!ticketId.equals(original.getDocumentId())) {
                throw new IllegalArgumentException("La operacion de tarjeta no pertenece al ticket seleccionado");
            }
            var refund = terminalPayments.refundPaymentOnly(
                    original.getId(), card.operationId(), card.idempotencyKey(), card.amount());
            if (refund.getStatus() == PaymentTerminalOperationStatus.PENDING
                    || refund.getStatus() == PaymentTerminalOperationStatus.SENT
                    || refund.getStatus() == PaymentTerminalOperationStatus.TIMEOUT) {
                refund = terminalPayments.query(refund.getId());
            }
            if (refund.getStatus() != PaymentTerminalOperationStatus.APPROVED) {
                throw new IllegalStateException("La devolucion de tarjeta no quedo aprobada: " + refund.getStatus());
            }
            recorded.add(new RefundSettlementRecorder.TenderCommand(
                    RefundTenderType.CARD,
                    Money.euros(card.amount()),
                    card.originalPaymentId(),
                    refund.getId(),
                    refund.getExternalReference()));
        }
        var refundDocument = settlements.record(
                requestId, ticketId, total, selectedLines, List.copyOf(recorded), authentication);
        return new ReturnResult(
                refundDocument,
                tenders.findByRefundDocumentIdOrderByCreatedAtAsc(refundDocument.getId()));
    }

    public List<DocumentService.CardRefundLineOption> options(UUID ticketId) {
        return documents.cardRefundLineOptions(ticketId);
    }

    public record CardPayout(
            UUID originalPaymentId,
            UUID operationId,
            String idempotencyKey,
            BigDecimal amount) {
    }

    public record ReturnResult(CommercialDocument document, List<RefundTender> payouts) {
    }
}
