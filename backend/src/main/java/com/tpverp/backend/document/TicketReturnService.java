package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalOperationsService;
import com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private final VoucherService vouchers;
    private final TicketCancellationOperationRepository cancellations;
    private final SaleOperationSecurityService operationSecurity;
    private final AuditService audit;

    public TicketReturnService(
            DocumentService documents,
            PaymentTerminalOperationsService terminalPayments,
            RefundSettlementRecorder settlements,
            RefundTenderRepository tenders,
            CashPaymentRecorder cash,
            CurrentTerminal currentTerminal,
            VoucherService vouchers,
            TicketCancellationOperationRepository cancellations,
            SaleOperationSecurityService operationSecurity,
            AuditService audit) {
        this.documents = documents;
        this.terminalPayments = terminalPayments;
        this.settlements = settlements;
        this.tenders = tenders;
        this.cash = cash;
        this.currentTerminal = currentTerminal;
        this.vouchers = vouchers;
        this.cancellations = cancellations;
        this.operationSecurity = operationSecurity;
        this.audit = audit;
    }

    public ReturnResult create(
            UUID ticketId,
            UUID requestId,
            BigDecimal cashAmount,
            BigDecimal voucherAmount,
            List<CardPayout> requestedCards,
            List<PaymentTerminalRefundLineSelection> lines,
            Authentication authentication) {
        return create(
                ticketId,
                requestId,
                cashAmount,
                voucherAmount,
                requestedCards,
                lines,
                null,
                null,
                authentication);
    }

    public ReturnResult create(
            UUID ticketId,
            UUID requestId,
            BigDecimal cashAmount,
            BigDecimal voucherAmount,
            List<CardPayout> requestedCards,
            List<PaymentTerminalRefundLineSelection> lines,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(requestId, "requestId");
        requireNoCancellationInProgress(ticketId);
        var cashValue = cashAmount == null ? Money.euros(BigDecimal.ZERO) : Money.euros(cashAmount);
        var voucherValue = voucherAmount == null ? Money.euros(BigDecimal.ZERO) : Money.euros(voucherAmount);
        if (cashValue.signum() < 0) throw new IllegalArgumentException("El efectivo no puede ser negativo");
        if (voucherValue.signum() < 0) throw new IllegalArgumentException("El vale no puede ser negativo");
        var cards = requestedCards == null ? List.<CardPayout>of() : List.copyOf(requestedCards);
        var selectedLines = lines == null ? List.<PaymentTerminalRefundLineSelection>of() : List.copyOf(lines);
        var seenPayments = new HashSet<UUID>();
        var total = cashValue.add(voucherValue);
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
        var preparedCards = cards.stream().map(card -> {
            var original = terminalPayments.findByDocumentPaymentId(card.originalPaymentId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "El pago no tiene una operacion de datafono"));
            if (!ticketId.equals(original.getDocumentId())) {
                throw new IllegalArgumentException(
                        "La operacion de tarjeta no pertenece al ticket seleccionado");
            }
            return new PreparedCardPayout(card, original.getId());
        }).toList();

        var authorization = operationSecurity.authorize(
                SaleOperationCode.RETURN_TICKET,
                authorizerUsername,
                authorizerPassword,
                authentication);
        audit.record(
                "TICKET_RETURN_AUTHORIZED",
                AuditResult.EXITO,
                authorizationDetails(ticketId, requestId, authorization));

        var recorded = new ArrayList<RefundSettlementRecorder.TenderCommand>();
        if (cashValue.signum() > 0) {
            recorded.add(new RefundSettlementRecorder.TenderCommand(
                    RefundTenderType.CASH, cashValue, null, null, null));
        }
        if (voucherValue.signum() > 0) {
            recorded.add(new RefundSettlementRecorder.TenderCommand(
                    RefundTenderType.VOUCHER, voucherValue, null, null, null));
        }
        for (var prepared : preparedCards) {
            var card = prepared.card();
            var refund = terminalPayments.refundPaymentOnly(
                    prepared.originalOperationId(),
                    card.operationId(),
                    card.idempotencyKey(),
                    card.amount());
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
        var issuedVoucher = voucherValue.signum() > 0
                ? Optional.of(vouchers.issueOrFindFromNegativeTicket(refundDocument, voucherValue))
                : Optional.<Voucher>empty();
        return new ReturnResult(
                refundDocument,
                tenders.findByRefundDocumentIdOrderByCreatedAtAsc(refundDocument.getId()),
                issuedVoucher);
    }

    private static java.util.Map<String, Object> authorizationDetails(
            UUID ticketId,
            UUID requestId,
            Authorization authorization) {
        var details = new LinkedHashMap<String, Object>();
        details.put("operation", SaleOperationCode.RETURN_TICKET.name());
        details.put("ticketId", ticketId.toString());
        details.put("requestId", requestId.toString());
        details.put("operatorId", authorization.operator().getId().toString());
        details.put("operatorUsername", authorization.operator().getUserName());
        details.put("authorizerId", authorization.authorizer().getId().toString());
        details.put("authorizerUsername", authorization.authorizer().getUserName());
        details.put("delegated", authorization.delegated());
        return java.util.Map.copyOf(details);
    }

    public List<DocumentService.CardRefundLineOption> options(UUID ticketId) {
        requireNoCancellationInProgress(ticketId);
        return documents.cardRefundLineOptions(ticketId);
    }

    public ReturnPreview preview(String ticketNumber) {
        var ticket = documents.ticketForReturnByNumber(ticketNumber);
        requireNoCancellationInProgress(ticket.getId());
        return new ReturnPreview(
                ticket,
                documents.cardRefundLineOptions(ticket.getId()));
    }

    private void requireNoCancellationInProgress(UUID ticketId) {
        if (cancellations.hasActiveCancellation(ticketId)) {
            throw new IllegalStateException(
                    "el ticket tiene una anulación en curso");
        }
    }

    public record CardPayout(
            UUID originalPaymentId,
            UUID operationId,
            String idempotencyKey,
            BigDecimal amount) {
    }

    private record PreparedCardPayout(
            CardPayout card,
            UUID originalOperationId) {
    }

    public record ReturnResult(
            CommercialDocument document,
            List<RefundTender> payouts,
            Optional<Voucher> voucher) {
    }

    public record ReturnPreview(
            CommercialDocument ticket,
            List<DocumentService.CardRefundLineOption> lines) {
    }
}
