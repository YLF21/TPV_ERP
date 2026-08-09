package com.tpverp.backend.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentTerminalOperation;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalOperationsService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TicketCancellationService {

    private final DocumentService documents;
    private final CommercialDocumentRepository documentRepository;
    private final TicketCancellationOperationRepository operations;
    private final VoucherEventRepository voucherEvents;
    private final SaleOperationSecurityService operationSecurity;
    private final UserAccountRepository users;
    private final PaymentTerminalOperationsService cardTerminals;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public TicketCancellationService(
            DocumentService documents,
            CommercialDocumentRepository documentRepository,
            TicketCancellationOperationRepository operations,
            VoucherEventRepository voucherEvents,
            SaleOperationSecurityService operationSecurity,
            UserAccountRepository users,
            PaymentTerminalOperationsService cardTerminals,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.documentRepository = documentRepository;
        this.operations = operations;
        this.voucherEvents = voucherEvents;
        this.operationSecurity = operationSecurity;
        this.users = users;
        this.cardTerminals = cardTerminals;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
    }

    public DocumentService.TicketCancellationValidation latestPreview(
            Authentication authentication) {
        var candidateIds = documentRepository.findLatestCancellableTicketIds(
                organization.currentStore().getId(),
                currentTerminal.terminalId(authentication),
                PageRequest.of(0, 25));
        for (var ticketId : candidateIds) {
            if (operations.hasActiveCancellation(ticketId)) {
                continue;
            }
            try {
                return documents.validateTicketCancellation(ticketId);
            } catch (IllegalStateException ignored) {
                // Sigue buscando el último ticket realmente anulable.
            }
        }
        throw new IllegalArgumentException(
                "no existe un ticket anterior anulable en esta terminal");
    }

    public DocumentService.TicketCancellationValidation previewByNumber(
            String ticketNumber) {
        var ticket = ticketByNumber(ticketNumber);
        if (operations.hasActiveCancellation(ticket.getId())) {
            throw new IllegalStateException("el ticket tiene una anulación en curso");
        }
        return documents.validateTicketCancellation(ticket.getId());
    }

    public CommercialDocument latestConvertibleTicket(Authentication authentication) {
        var ticketId = documentRepository.findLatestConvertibleTicketIds(
                        organization.currentStore().getId(),
                        currentTerminal.terminalId(authentication),
                        PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no existe un ticket anterior convertible en esta terminal"));
        return documents.findDetailed(ticketId);
    }

    public CommercialDocument ticketByNumber(String ticketNumber) {
        if (ticketNumber == null || ticketNumber.isBlank()) {
            throw new IllegalArgumentException("el código de ticket es obligatorio");
        }
        return documents.detailedTicketByNumber(ticketNumber);
    }

    public CancellationResult cancel(
            CancellationCommand command,
            Authentication authentication) {
        if (command == null || command.requestId() == null) {
            throw new IllegalArgumentException("requestId es obligatorio");
        }
        var prepared = transactions.execute(status -> prepare(command, authentication));
        if (prepared == null) {
            throw new IllegalStateException("no se pudo preparar la anulación");
        }
        if (prepared.operation().getStatus() == TicketCancellationStatus.COMPLETED) {
            return completedResult(prepared.operation());
        }

        transactions.executeWithoutResult(status -> {
            var operation = operations.findLockedById(command.requestId())
                    .orElseThrow();
            operation.startCompensation(clock.instant());
            operations.save(operation);
        });

        var cardAdjustments = new ArrayList<CardAdjustment>();
        try {
            for (var payment : prepared.validation().integratedCardPayments()) {
                cardAdjustments.add(compensateCard(
                        command.requestId(), payment));
            }
        } catch (CardResultUncertain uncertain) {
            updateOperation(command.requestId(), operation ->
                    operation.reviewRequired(uncertain.getMessage(), clock.instant()));
            throw new IllegalStateException(uncertain.getMessage());
        } catch (RuntimeException failure) {
            updateOperation(command.requestId(), operation ->
                    operation.failed(safeMessage(failure), clock.instant()));
            throw failure;
        }

        updateOperation(command.requestId(), operation ->
                operation.ready(clock.instant()));
        try {
            var applied = documents.applyCompensatedTicketCancellation(
                    prepared.validation().ticket().getId(),
                    authentication,
                    prepared.operation().getReason(),
                    prepared.authorization().authorizer().getId(),
                    prepared.authorization().authorizer().getUserName(),
                    prepared.authorization().delegated(),
                    prepared.operation().getManualCompensations());
            updateOperation(command.requestId(), operation ->
                    operation.complete(clock.instant()));
            var completedOperation = operations.findById(command.requestId())
                    .orElse(prepared.operation());
            return new CancellationResult(
                    applied.ticket(),
                    applied.vouchers().restored().stream()
                            .map(voucher -> new RestoredVoucher(
                                    voucher.code(), voucher.balance()))
                            .toList(),
                    applied.vouchers().invalidated().stream()
                            .map(Voucher::code)
                            .toList(),
                    applied.openCashDrawer(),
                    List.copyOf(cardAdjustments),
                    cancellationReceipt(applied.ticket(), completedOperation));
        } catch (RuntimeException failure) {
            updateOperation(command.requestId(), operation ->
                    operation.failed(safeMessage(failure), clock.instant()));
            throw failure;
        }
    }

    private PreparedCancellation prepare(
            CancellationCommand command,
            Authentication authentication) {
        var authorization = operationSecurity.authorize(
                SaleOperationCode.CANCEL_TICKET,
                command.authorizerUsername(),
                command.authorizerPassword(),
                authentication);
        var manualCompensations = normalizedReferences(command.manualCompensations());
        var requestHash = requestHash(
                command, authorization.authorizer().getId(), manualCompensations);
        var existing = operations.findById(command.requestId());
        if (existing.isPresent()) {
            var operation = existing.orElseThrow();
            operation.requireCompatible(command.ticketId(), requestHash);
            var ticket = documents.find(operation.getTicketId());
            if (ticket.getEstado() == DocumentStatus.ANULADO
                    && operation.getStatus() != TicketCancellationStatus.COMPLETED) {
                operation.complete(clock.instant());
                operations.save(operation);
            }
            if (operation.getStatus() == TicketCancellationStatus.COMPLETED) {
                return new PreparedCancellation(operation, null, authorization);
            }
            var validation = documents.validateTicketCancellation(command.ticketId());
            requireManualReferences(validation.manualReferences(), manualCompensations);
            return new PreparedCancellation(operation, validation, authorization);
        }
        documentRepository.findLockedDocument(
                        command.ticketId(), organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "documento no encontrado"));
        var validation = documents.validateTicketCancellation(command.ticketId());
        requireManualReferences(validation.manualReferences(), manualCompensations);
        operations.findActiveByTicketId(command.ticketId()).ifPresent(operation -> {
            throw new IllegalStateException(
                    "el ticket ya tiene una anulación en curso");
        });
        var operation = operations.save(new TicketCancellationOperation(
                command.requestId(),
                validation.ticket().getId(),
                validation.ticket().getTiendaId(),
                currentTerminal.terminalId(authentication),
                authorization.operator().getId(),
                authorization.authorizer().getId(),
                command.reason(),
                requestHash,
                manualCompensations,
                clock.instant()));
        return new PreparedCancellation(operation, validation, authorization);
    }

    private CardAdjustment compensateCard(
            UUID requestId,
            DocumentService.IntegratedCardCancellation payment) {
        var original = cardTerminals.findByDocumentPaymentId(payment.paymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "no se encontró el cobro integrado de la línea de pago"));
        var remaining = Money.euros(
                original.getAmount().subtract(original.getRefundedAmount()));
        if (remaining.signum() == 0) {
            return new CardAdjustment(
                    payment.paymentId(), null, PaymentTerminalOperationStatus.APPROVED);
        }
        if (remaining.compareTo(payment.amount()) != 0) {
            throw new IllegalStateException(
                    "el cobro de tarjeta ya tiene una devolución parcial");
        }
        var operationId = deterministicOperationId(requestId, payment.paymentId());
        var refund = cardTerminals.refundPaymentOnly(
                original.getId(),
                operationId,
                "ticket-cancel:" + requestId + ":" + payment.paymentId(),
                payment.amount());
        if (isUncertain(refund.getStatus())) {
            refund = cardTerminals.query(operationId);
        }
        var finalRefund = refund;
        updateOperation(requestId, operation -> operation.recordCardOperation(
                payment.paymentId(), operationId,
                finalRefund.getStatus().name(), clock.instant()));
        if (refund.getStatus() == PaymentTerminalOperationStatus.APPROVED) {
            return new CardAdjustment(
                    payment.paymentId(), operationId, refund.getStatus());
        }
        if (isUncertain(refund.getStatus())) {
            throw new CardResultUncertain(
                    "el resultado de la devolución de tarjeta es incierto; "
                            + "el ticket sigue activo y requiere revisar el datáfono");
        }
        throw new IllegalStateException(
                "la devolución de tarjeta no fue aprobada: " + refund.getStatus());
    }

    /**
     * Rebuilds the durable response when the cancellation completed but the client
     * did not receive it. Drawer opening is deliberately not repeated.
     */
    private CancellationResult completedResult(TicketCancellationOperation operation) {
        var ticket = documents.findDetailed(operation.getTicketId());
        var events = voucherEvents.findAllByDocumentIdOrderByOccurredAtAsc(
                operation.getTicketId());
        var restored = events.stream()
                .filter(event -> event.getType() == VoucherEventType.RESTORED)
                .map(event -> new RestoredVoucher(
                        event.getVoucher().code(), event.getAmount()))
                .toList();
        var invalidated = events.stream()
                .filter(event -> event.getType() == VoucherEventType.INVALIDATED)
                .map(event -> event.getVoucher().code())
                .toList();
        return new CancellationResult(
                ticket,
                restored,
                invalidated,
                false,
                List.of(),
                cancellationReceipt(ticket, operation));
    }

    private CancellationReceipt cancellationReceipt(
            CommercialDocument ticket,
            TicketCancellationOperation operation) {
        var references = operation.getManualCompensations();
        var payments = ticket.getPagos().stream()
                .sorted(java.util.Comparator.comparingInt(DocumentPayment::getPosicion))
                .map(payment -> new CancellationReceiptPayment(
                        payment.getMetodoPago().getNombre(),
                        payment.getImporte(),
                        firstText(
                                references.get(payment.getId().toString()),
                                payment.getReferencia(),
                                payment.getCardAuthorizationCode(),
                                payment.getVoucherCode())))
                .toList();
        return new CancellationReceipt(
                operation.getId(),
                ticket.getNumero(),
                ticket.getConfirmadoEn() == null
                        ? ticket.getCreadoEn() : ticket.getConfirmadoEn(),
                ticket.getAnuladoEn(),
                ticket.getTotal(),
                operation.getReason(),
                username(operation.getOperatorUserId()),
                username(operation.getAuthorizerUserId()),
                !operation.getOperatorUserId().equals(operation.getAuthorizerUserId()),
                payments);
    }

    private String username(UUID userId) {
        return users.findById(userId)
                .map(user -> user.getUserName() == null || user.getUserName().isBlank()
                        ? user.getNombre() : user.getUserName())
                .orElse(userId.toString());
    }

    private static String firstText(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private void updateOperation(
            UUID requestId,
            java.util.function.Consumer<TicketCancellationOperation> update) {
        transactions.executeWithoutResult(status -> {
            var operation = operations.findLockedById(requestId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "operación de anulación no encontrada"));
            update.accept(operation);
            operations.save(operation);
        });
    }

    private static void requireManualReferences(
            List<DocumentService.ManualCancellationReference> requirements,
            Map<String, String> references) {
        var requiredIds = requirements.stream()
                .map(requirement -> requirement.paymentId().toString())
                .collect(java.util.stream.Collectors.toSet());
        if (!requiredIds.equals(references.keySet())) {
            throw new IllegalArgumentException(
                    "las referencias manuales no corresponden a los pagos del ticket");
        }
        for (var requirement : requirements) {
            var value = references.get(requirement.paymentId().toString());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "la referencia de devolución es obligatoria para "
                                + requirement.paymentMethod());
            }
        }
    }

    private static Map<String, String> normalizedReferences(
            Map<String, String> references) {
        var normalized = new TreeMap<String, String>();
        if (references != null) {
            if (references.size() > 20) {
                throw new IllegalArgumentException("demasiadas referencias manuales");
            }
            references.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()) {
                    var normalizedKey = key.trim();
                    UUID.fromString(normalizedKey);
                    var normalizedValue = value.trim();
                    if (normalizedValue.length() > 128) {
                        throw new IllegalArgumentException(
                                "la referencia manual no puede superar 128 caracteres");
                    }
                    normalized.put(normalizedKey, normalizedValue);
                }
            });
        }
        return Map.copyOf(normalized);
    }

    private static String requestHash(
            CancellationCommand command,
            UUID authorizerId,
            Map<String, String> manualCompensations) {
        var canonical = new StringBuilder()
                .append(command.ticketId()).append('|')
                .append(command.reason() == null ? "" : command.reason().trim()).append('|')
                .append(authorizerId).append('|');
        new TreeMap<>(manualCompensations).forEach((key, value) ->
                canonical.append(key).append('=').append(value).append(';'));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static UUID deterministicOperationId(UUID requestId, UUID paymentId) {
        return UUID.nameUUIDFromBytes(
                (requestId + ":" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isUncertain(PaymentTerminalOperationStatus status) {
        return status == PaymentTerminalOperationStatus.PENDING
                || status == PaymentTerminalOperationStatus.SENT
                || status == PaymentTerminalOperationStatus.TIMEOUT
                || status == PaymentTerminalOperationStatus.REVIEW_REQUIRED
                || status == PaymentTerminalOperationStatus.ERROR;
    }

    private static String safeMessage(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? "fallo al compensar la anulación"
                : failure.getMessage();
    }

    private record PreparedCancellation(
            TicketCancellationOperation operation,
            DocumentService.TicketCancellationValidation validation,
            OperationalPermissionAuthorizationService.Authorization authorization) {
    }

    private static final class CardResultUncertain extends RuntimeException {
        private CardResultUncertain(String message) {
            super(message);
        }
    }

    public record CancellationCommand(
            UUID requestId,
            UUID ticketId,
            String reason,
            String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String authorizerPassword,
            Map<String, String> manualCompensations) {

        @Override
        public String toString() {
            return "CancellationCommand[requestId=" + requestId
                    + ", ticketId=" + ticketId
                    + ", reason=" + reason
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }
    }

    public record CardAdjustment(
            UUID paymentId,
            UUID operationId,
            PaymentTerminalOperationStatus status) {
    }

    public record CancellationResult(
            CommercialDocument ticket,
            List<RestoredVoucher> restoredVouchers,
            List<String> invalidatedVoucherCodes,
            boolean openCashDrawer,
            List<CardAdjustment> cardAdjustments,
            CancellationReceipt receipt) {
    }

    public record CancellationReceipt(
            UUID operationId,
            String originalTicketNumber,
            java.time.Instant originalIssuedAt,
            java.time.Instant cancelledAt,
            BigDecimal total,
            String reason,
            String operatorUsername,
            String authorizerUsername,
            boolean delegated,
            List<CancellationReceiptPayment> payments) {
    }

    public record CancellationReceiptPayment(
            String method,
            BigDecimal amount,
            String reference) {
    }

    public record RestoredVoucher(String code, BigDecimal balance) {
    }
}
