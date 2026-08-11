package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.catalog.ProductQuantityPolicy;
import com.tpverp.backend.catalog.ProductType;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private GiftReceiptService giftReceipts;
    private TicketReturnValuationService valuations;
    private SalePaymentSessionRepository paymentSessions;

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

    @Autowired
    void setGiftReceiptService(GiftReceiptService giftReceipts) {
        this.giftReceipts = giftReceipts;
    }

    @Autowired
    void setTicketReturnValuationService(TicketReturnValuationService valuations) {
        this.valuations = valuations;
    }

    @Autowired
    void setSalePaymentSessionRepository(SalePaymentSessionRepository paymentSessions) {
        this.paymentSessions = paymentSessions;
    }

    public TicketReturnValuationService.Valuation value(
            String sourceCode,
            List<ReturnSelection> requested) {
        if (valuations == null) {
            throw new IllegalStateException("return_valuation_service_unavailable");
        }
        var preview = preview(sourceCode);
        var options = preview.lines().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReturnLineOption::lineId, java.util.function.Function.identity()));
        var selected = new java.util.LinkedHashMap<UUID, BigDecimal>();
        for (var request : List.copyOf(requested == null ? List.of() : requested)) {
            var option = options.get(Objects.requireNonNull(request.lineId(), "lineId"));
            if (option == null) {
                throw new IllegalArgumentException(
                        "La linea no esta disponible en el documento indicado");
            }
            var requestedQuantity = Objects.requireNonNull(request.quantity(), "quantity");
            ProductQuantityPolicy.requireValid(option.productType(), requestedQuantity);
            var quantity = requestedQuantity.setScale(3, Money.ROUNDING);
            if (quantity.signum() <= 0
                    || quantity.compareTo(option.refundableQuantity()) > 0) {
                throw new IllegalArgumentException(
                        "La cantidad supera el saldo pendiente de devolucion");
            }
            selected.merge(option.lineId(), quantity, BigDecimal::add);
        }
        return valuations.value(preview.ticket(), selected);
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
        var sourceDocument = documents.find(ticketId);
        var paymentSource = documents.returnPaymentSource(ticketId);
        if (paymentSource == null) {
            paymentSource = sourceDocument;
        }
        var paymentSourceId = paymentSource == null
                ? ticketId
                : paymentSource.getId();
        TicketReturnValuationService.Valuation valuation = null;
        if (valuations != null && !selectedLines.isEmpty()) {
            var selectedQuantities = selectedLines.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            PaymentTerminalRefundLineSelection::lineId,
                            PaymentTerminalRefundLineSelection::quantity,
                            BigDecimal::add,
                            LinkedHashMap::new));
            valuation = valuations.value(sourceDocument, selectedQuantities);
            if (total.compareTo(valuation.refundableAmount()) != 0) {
                throw new IllegalArgumentException(
                        "El importe de devolucion no coincide con la valoracion historica");
            }
        } else {
            documents.validateApprovedCardRefund(ticketId, total, selectedLines);
        }
        if (total.signum() < 0
                || (total.signum() == 0
                        && (valuation == null
                                || valuation.refundableAmount().signum() != 0))) {
            throw new IllegalArgumentException("Se requiere un importe de devolucion");
        }

        // Preflight cash before sending any irreversible request to the acquirer.
        if (cashValue.signum() > 0) {
            cash.requireOpenSession(currentTerminal.terminalId(authentication));
        }
        var preparedCards = cards.stream().map(card -> {
            var original = terminalPayments.findByDocumentPaymentId(card.originalPaymentId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "El pago no tiene una operacion de datafono"));
            if (!paymentSourceId.equals(original.getDocumentId())) {
                throw new IllegalArgumentException(
                        "La operacion de tarjeta no pertenece al documento seleccionado");
            }
            return new PreparedCardPayout(card, original.getId());
        }).toList();

        var operation = sourceDocument != null
                && sourceDocument.getTipo() == CommercialDocumentType.FACTURA_VENTA
                ? SaleOperationCode.RETURN_SALES_INVOICE
                : SaleOperationCode.RETURN_TICKET;
        var authorization = operationSecurity.authorize(
                operation,
                authorizerUsername,
                authorizerPassword,
                authentication);
        audit.record(
                "TICKET_RETURN_AUTHORIZED",
                AuditResult.EXITO,
                authorizationDetails(ticketId, requestId, operation, authorization));

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
        var payouts = List.copyOf(recorded);
        var refundDocument = valuation == null
                ? settlements.record(
                        requestId,
                        ticketId,
                        total,
                        selectedLines,
                        payouts,
                        authentication)
                : settlements.record(
                        requestId,
                        ticketId,
                        total,
                        selectedLines,
                        payouts,
                        valuation,
                        authentication);
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
            SaleOperationCode operation,
            Authorization authorization) {
        var details = new LinkedHashMap<String, Object>();
        details.put("operation", operation.name());
        details.put("sourceDocumentId", ticketId.toString());
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

    @Transactional(readOnly = true)
    public ReturnPreview preview(String ticketNumber) {
        if (giftReceipts != null) {
            var gift = giftReceipts.findReturnContext(ticketNumber);
            if (gift.isPresent()) {
                var context = gift.orElseThrow();
                requireNoCancellationInProgress(context.ticket().getId());
                return new ReturnPreview(
                        ReturnSourceType.GIFT_RECEIPT,
                        context.receipt().getCode(),
                        context.ticket(),
                        context.lines().stream().map(line -> new ReturnLineOption(
                                line.sourceLineId(), line.giftReceiptLineId(), line.productId(),
                                line.code(), line.barcode(), line.barcode2(),
                                line.name(), DocumentLineType.PRODUCT,
                                line.refundableQuantity(), line.unitPrice(),
                                line.refundableTotal(), line.serialNumbers(),
                                line.discount(), line.taxesIncluded(), line.taxRegime(),
                                line.taxPercentage(), line.productType())).toList(),
                        RefundPaymentAvailability.calculate(
                                context.ticket(), tenders,
                                activePaymentReservations(context.ticket())));
            }
        }
        var ticketMatch = documents.findTicketForReturnByNumber(ticketNumber);
        CommercialDocument ticket;
        ReturnSourceType sourceType;
        if (ticketMatch.isPresent()) {
            ticket = ticketMatch.orElseThrow();
            sourceType = ReturnSourceType.TICKET;
            requireNoCancellationInProgress(ticket.getId());
        } else {
            ticket = documents.invoiceForReturnByNumber(ticketNumber);
            sourceType = ReturnSourceType.SALES_INVOICE;
        }
        var paymentSource = documents.returnPaymentSource(ticket.getId());
        return new ReturnPreview(
                sourceType,
                ticket.getNumero(),
                ticket,
                documents.cardRefundLineOptions(ticket.getId()).stream()
                        .filter(line -> line.lineType() == DocumentLineType.PRODUCT)
                        .map(line -> new ReturnLineOption(
                                line.lineId(), null, line.productId(), line.code(),
                                line.barcode(), line.barcode2(), line.name(),
                                line.lineType(), line.refundableQuantity(), line.unitPrice(),
                                line.refundableTotal(), line.refundableSerialNumbers(),
                                line.discount(), line.taxesIncluded(), line.taxRegime(),
                                line.taxPercentage(), line.productType()))
                        .toList(),
                RefundPaymentAvailability.calculate(
                        paymentSource, tenders, activePaymentReservations(paymentSource)));
    }

    private List<SalePaymentAllocation> activePaymentReservations(
            CommercialDocument ticket) {
        if (paymentSessions == null || ticket.getPagos().isEmpty()) {
            return List.of();
        }
        return paymentSessions.findActiveRefundReservations(
                ticket.getPagos().stream().map(DocumentPayment::getId).toList());
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

    public enum ReturnSourceType {
        TICKET,
        GIFT_RECEIPT,
        SALES_INVOICE
    }

    public record ReturnLineOption(
            UUID lineId,
            UUID giftReceiptLineId,
            UUID productId,
            String code,
            String barcode,
            String barcode2,
            String name,
            DocumentLineType lineType,
            BigDecimal refundableQuantity,
            BigDecimal unitPrice,
            BigDecimal refundableTotal,
            List<String> refundableSerialNumbers,
            BigDecimal discount,
            boolean taxesIncluded,
            String taxRegime,
            BigDecimal taxPercentage,
            ProductType productType) {
    }

    public record ReturnSelection(UUID lineId, BigDecimal quantity) {
    }

    public record ReturnPreview(
            ReturnSourceType sourceType,
            String sourceCode,
            CommercialDocument ticket,
            List<ReturnLineOption> lines,
            List<RefundPaymentAvailability.View> paymentAvailability) {
    }
}
