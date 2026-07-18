package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CardTerminalConfiguration;
import com.tpverp.backend.terminal.CardTerminalConfigurationReader;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperation;
import com.tpverp.backend.terminal.PaymentTerminalOperationService;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerReceivableService {

    private final CommercialDocumentRepository documents;
    private final DocumentPaymentRepository payments;
    private final DocumentService documentService;
    private final PaymentTerminalOperationService terminalOperations;
    private final CardTerminalConfigurationReader configurations;
    private final CurrentTerminal currentTerminal;
    private final CurrentOrganization organization;
    private final DocumentViewAssembler views;
    private final CustomerReceivablePaymentReservationCoordinator paymentReservations;
    private final CustomerReceivablePaymentReservationRepository paymentReservationRepository;
    private final CustomerReceivableTransactionRunner transactions;
    private final Clock clock;

    public CustomerReceivableService(
            CommercialDocumentRepository documents,
            DocumentPaymentRepository payments,
            DocumentService documentService,
            PaymentTerminalOperationService terminalOperations,
            CardTerminalConfigurationReader configurations,
            CurrentTerminal currentTerminal,
            CurrentOrganization organization,
            DocumentViewAssembler views,
            CustomerReceivablePaymentReservationCoordinator paymentReservations,
            CustomerReceivablePaymentReservationRepository paymentReservationRepository,
            CustomerReceivableTransactionRunner transactions,
            Clock clock) {
        this.documents = documents;
        this.payments = payments;
        this.documentService = documentService;
        this.terminalOperations = terminalOperations;
        this.configurations = configurations;
        this.currentTerminal = currentTerminal;
        this.organization = organization;
        this.views = views;
        this.paymentReservations = paymentReservations;
        this.paymentReservationRepository = paymentReservationRepository;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CustomerReceivableView> list(
            CustomerReceivableFilter filter, Authentication authentication) {
        var effective = filter == null
                ? new CustomerReceivableFilter(null, null, null, null, null, null, null)
                : filter;
        var businessDate = businessDate();
        var normalizedSearch = normalized(effective.search());
        var search = normalizedSearch == null
                ? null : normalizedSearch.toLowerCase(Locale.ROOT);
        return documents.findCustomerReceivables(organization.currentStore().getId()).stream()
                .filter(document -> document.getClienteId() != null)
                .map(document -> views.receivableView(document, businessDate))
                .filter(view -> effective.customerId() == null
                        || effective.customerId().equals(view.customerId()))
                .filter(view -> effective.status() == null || effective.status() == view.status())
                .filter(view -> effective.overdue() == null
                        || effective.overdue() == view.overdue())
                .filter(view -> effective.documentType() == null
                        || effective.documentType() == view.documentType())
                .filter(view -> effective.dueFrom() == null || (view.dueDate() != null
                        && !view.dueDate().isBefore(effective.dueFrom())))
                .filter(view -> effective.dueTo() == null || (view.dueDate() != null
                        && !view.dueDate().isAfter(effective.dueTo())))
                .filter(view -> search == null || contains(view.documentNumber(), search)
                        || contains(view.customerName(), search))
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerReceivableView detail(UUID documentId, Authentication authentication) {
        var document = documents.findCustomerReceivable(
                        Objects.requireNonNull(documentId, "documentId"),
                        organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("customer_receivable_not_found"));
        return views.receivableView(document, businessDate());
    }

    public PaymentTerminalResult chargeCard(
            UUID documentId,
            CustomerReceivableController.CardChargeRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        var amount = positive(request.amount());
        documentId = Objects.requireNonNull(documentId, "documentId");
        var storeId = organization.currentStore().getId();
        var terminalId = currentTerminal.terminalId(authentication);
        var userId = organization.currentUser(authentication).getId();
        var configuration = configurations.required(terminalId);
        requireConfigurationScope(configuration, terminalId, storeId);
        var hash = paymentHash(documentId, request.paymentId(), amount);
        var owner = UUID.randomUUID();
        var acquired = paymentReservations.acquire(
                request.paymentId(), documentId, storeId, terminalId, userId, hash, amount,
                CustomerReceivablePaymentReservation.Kind.INTEGRATED_CARD, owner);
        var status = acquired.reservation().getStatus();
        if (status == CustomerReceivablePaymentReservation.Status.RESERVED) {
            paymentReservations.markDispatching(request.paymentId(), owner);
        }
        var result = terminalOperations.charge(
                request.paymentId(), hash, amount, configuration);
        if (status != CustomerReceivablePaymentReservation.Status.APPROVED
                && status != CustomerReceivablePaymentReservation.Status.COMPLETED) {
            paymentReservations.recordTerminalResult(
                    request.paymentId(), result.status(), result.finalOutcome());
        }
        return result;
    }

    public PaymentTerminalResult queryCard(
            UUID documentId, UUID paymentId, Authentication authentication) {
        var storeId = organization.currentStore().getId();
        var terminalId = currentTerminal.terminalId(authentication);
        paymentReservations.validateRecoveryScope(
                paymentId, documentId, storeId, terminalId);
        var operation = terminalOperations.recover(paymentId, UUID.randomUUID());
        paymentReservations.synchronize(documentId, storeId, terminalId, operation);
        return new PaymentTerminalResult(
                operation.getStatus(), "QUERY_RESULT", operation.getExternalReference(),
                operation.getAuthorizationCode(), "Estado consultado en el datafono",
                operation.isFinalOutcome());
    }

    public CustomerReceivableView pay(
            UUID documentId, PaymentRequest request, Authentication authentication) {
        var item = singlePayment(request);
        var paymentId = Objects.requireNonNull(item.requestId(), "paymentId");
        var storeId = organization.currentStore().getId();
        var amount = positive(item.importe());
        var terminalId = currentTerminal.terminalId(authentication);
        var userId = organization.currentUser(authentication).getId();
        if (payments.findByRequestId(paymentId).isPresent()) {
            return transactions.run(() -> finalizeReservedPayment(
                    documentId, storeId, terminalId, item, paymentId, authentication, null));
        }
        var kind = item.paymentTerminalOperationId() == null
                ? CustomerReceivablePaymentReservation.Kind.STANDARD
                : CustomerReceivablePaymentReservation.Kind.INTEGRATED_CARD;
        var owner = UUID.randomUUID();
        var acquisition = paymentReservations.acquire(
                paymentId, documentId, storeId, terminalId, userId,
                paymentHash(documentId, paymentId, amount), amount, kind, owner);
        try {
            return transactions.run(() -> finalizeReservedPayment(
                    documentId, storeId, terminalId, item, paymentId,
                    authentication, acquisition.reservation()));
        } catch (RuntimeException failure) {
            paymentReservations.release(paymentId, owner);
            throw failure;
        }
    }

    private CustomerReceivableView finalizeReservedPayment(
            UUID documentId, UUID storeId, UUID terminalId, PaymentRequest.Item item,
            UUID paymentId, Authentication authentication,
            CustomerReceivablePaymentReservation reservation) {
        var document = locked(documentId, storeId);
        requireReceivableType(document);
        var replay = payments.findByRequestId(paymentId);
        if (replay.isPresent()) {
            requireReplayIdentity(replay.orElseThrow(), document, item);
            if (reservation != null
                    && reservation.getStatus()
                    != CustomerReceivablePaymentReservation.Status.COMPLETED) {
                reservation.complete(replay.orElseThrow().getId(), Instant.now(clock));
                paymentReservationRepository.save(reservation);
            }
            return views.receivableView(document, businessDate());
        }

        requireCollectable(document);
        var amount = positive(item.importe());
        requireAtMostPending(amount, document.getPendingTotal());
        if (item.paymentTerminalOperationId() != null
                && (reservation == null || reservation.getStatus()
                != CustomerReceivablePaymentReservation.Status.APPROVED)) {
            throw new IllegalStateException("payment_operation_not_finalizable");
        }
        var command = paymentCommand(document, item, paymentId, terminalId, storeId);
        var saved = documentService.collectReceivable(document, List.of(command), authentication);

        DocumentPayment persisted = null;
        if (item.paymentTerminalOperationId() != null) {
            persisted = saved.getPagos().stream()
                    .filter(value -> paymentId.equals(value.getRequestId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "approved_card_payment_not_persisted"));
            terminalOperations.linkDocument(
                    item.paymentTerminalOperationId(), saved.getId(), persisted.getId());
        } else {
            persisted = saved.getPagos().stream()
                    .filter(value -> paymentId.equals(value.getRequestId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("receivable_payment_not_persisted"));
        }
        if (reservation != null) {
            reservation.complete(persisted.getId(), Instant.now(clock));
            paymentReservationRepository.save(reservation);
        }
        return views.receivableView(saved, businessDate());
    }

    private PaymentCommand paymentCommand(
            CommercialDocument document,
            PaymentRequest.Item item,
            UUID paymentId,
            UUID terminalId,
            UUID storeId) {
        if (item.paymentTerminalOperationId() == null) {
            if (item.cardMode() == PaymentCardMode.INTEGRATED) {
                throw new IllegalStateException("approved_card_payment_required");
            }
            return item.toCommand();
        }
        if (!paymentId.equals(item.paymentTerminalOperationId())) {
            throw new IllegalStateException("payment_operation_identity_mismatch");
        }
        var operation = terminalOperations.requireFinalizableApprovedCharge(
                item.paymentTerminalOperationId());
        var configuration = configurations.required(terminalId);
        requireConfigurationScope(configuration, terminalId, storeId);
        requireCardIdentity(operation, configuration, document, paymentId, item.importe());
        return new PaymentCommand(
                item.metodoPagoId(), item.importe(), item.principal(), item.entregado(),
                item.cambio(), item.voucherCode(), operation.getExternalReference(),
                PaymentCardMode.INTEGRATED, operation.getProvider(),
                PaymentTerminalOperationStatus.APPROVED, operation.getAuthorizationCode(),
                terminalId, paymentId);
    }

    private static void requireCardIdentity(
            PaymentTerminalOperation operation,
            CardTerminalConfiguration configuration,
            CommercialDocument document,
            UUID paymentId,
            BigDecimal amount) {
        if (!operation.getTerminalId().equals(configuration.terminalId())
                || !operation.getStoreId().equals(configuration.storeId())) {
            throw new IllegalStateException("payment_operation_scope_mismatch");
        }
        if (!operation.matchesConfigurationIdentity(configuration)) {
            throw new IllegalStateException("payment_operation_configuration_mismatch");
        }
        var normalizedAmount = Money.euros(amount);
        if (!operation.getRequestHash().equals(
                paymentHash(document.getId(), paymentId, normalizedAmount))
                || operation.getAmount().compareTo(normalizedAmount) != 0) {
            throw new IllegalStateException("payment_operation_identity_mismatch");
        }
    }

    private void requireReplayIdentity(
            DocumentPayment existing,
            CommercialDocument requestedDocument,
            PaymentRequest.Item item) {
        var sameDocument = existing.getDocumento().getId().equals(requestedDocument.getId());
        var sameCore = existing.getMetodoPago().getId().equals(item.metodoPagoId())
                && existing.getImporte().compareTo(Money.euros(item.importe())) == 0
                && Objects.equals(existing.getEntregado(), nullableMoney(item.entregado()))
                && Objects.equals(existing.getCambio(), nullableMoney(item.cambio()))
                && Objects.equals(existing.getVoucherCode(), normalized(item.voucherCode()));
        var integrated = existing.getCardMode() == PaymentCardMode.INTEGRATED;
        var sameReference = integrated
                ? true
                : Objects.equals(existing.getReferencia(), normalized(item.reference()));
        var sameOperation = !integrated
                || (item.paymentTerminalOperationId() != null
                && terminalOperations.findByDocumentPaymentId(existing.getId())
                        .map(PaymentTerminalOperation::getId)
                        .filter(item.paymentTerminalOperationId()::equals)
                        .isPresent());
        if (!sameDocument || !sameCore || !sameReference || !sameOperation) {
            throw new IllegalStateException("payment_idempotency_conflict");
        }
    }

    private CommercialDocument locked(UUID documentId, UUID storeId) {
        return documents.findLockedReceivable(
                        Objects.requireNonNull(documentId, "documentId"), storeId)
                .orElseThrow(() -> new IllegalArgumentException("customer_receivable_not_found"));
    }

    private static PaymentRequest.Item singlePayment(PaymentRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.pagos() == null || request.pagos().size() != 1) {
            throw new IllegalArgumentException("single_receivable_payment_required");
        }
        return Objects.requireNonNull(request.pagos().getFirst(), "payment");
    }

    private static void requireReceivableType(CommercialDocument document) {
        if (document.getTipo() != CommercialDocumentType.ALBARAN_VENTA
                && document.getTipo() != CommercialDocumentType.FACTURA_VENTA) {
            throw new IllegalStateException(
                    "message.document.only_receivable_document_can_be_paid");
        }
        if (document.getClienteId() == null) {
            throw new IllegalStateException("customer_receivable_customer_required");
        }
    }

    private static void requireCollectable(CommercialDocument document) {
        requireReceivableType(document);
        if ((document.getEstado() != DocumentStatus.PENDIENTE
                && document.getEstado() != DocumentStatus.PARCIAL)
                || document.getPendingTotal().signum() <= 0) {
            throw new IllegalStateException("customer_receivable_not_collectable");
        }
    }

    private static void requireAtMostPending(BigDecimal amount, BigDecimal pending) {
        if (amount.compareTo(Money.euros(pending)) > 0) {
            throw new IllegalArgumentException(
                    "message.document.payment_exceeds_pending_total");
        }
    }

    private static BigDecimal positive(BigDecimal amount) {
        var value = Money.euros(amount);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("payment debe ser positivo");
        }
        return value;
    }

    private static void requireConfigurationScope(
            CardTerminalConfiguration configuration, UUID terminalId, UUID storeId) {
        if (!configuration.terminalId().equals(terminalId)
                || !configuration.storeId().equals(storeId)) {
            throw new IllegalStateException("payment_operation_scope_mismatch");
        }
    }

    private LocalDate businessDate() {
        return LocalDate.now(clock.withZone(
                ZoneId.of(organization.currentStore().getTimezone())));
    }

    private static String paymentHash(UUID documentId, UUID paymentId, BigDecimal amount) {
        var canonical = documentId + "|" + Money.euros(amount).toPlainString() + "|" + paymentId;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : Money.euros(value);
    }

    private static boolean contains(String value, String search) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(search);
    }
}
