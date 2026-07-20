package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CardTerminalConfigurationReader;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperation;
import com.tpverp.backend.terminal.PaymentTerminalOperationService;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerPendingSaleService {

    private static final Duration CHECKOUT_LEASE = Duration.ofSeconds(30);

    private final DocumentService documents;
    private final CustomerPendingSaleCheckoutRepository checkouts;
    private final CustomerPendingSaleCheckoutReservation reservations;
    private final PaymentTerminalOperationService terminalOperations;
    private final CardTerminalConfigurationReader configurations;
    private final CurrentTerminal currentTerminal;
    private final CurrentOrganization organization;
    private final DocumentViewAssembler views;
    private final Clock clock;

    public CustomerPendingSaleService(
            DocumentService documents,
            CustomerPendingSaleCheckoutRepository checkouts,
            CustomerPendingSaleCheckoutReservation reservations,
            PaymentTerminalOperationService terminalOperations,
            CardTerminalConfigurationReader configurations,
            CurrentTerminal currentTerminal,
            CurrentOrganization organization,
            DocumentViewAssembler views,
            Clock clock) {
        this.documents = documents;
        this.checkouts = checkouts;
        this.reservations = reservations;
        this.terminalOperations = terminalOperations;
        this.configurations = configurations;
        this.currentTerminal = currentTerminal;
        this.organization = organization;
        this.views = views;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Quote quote(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        return new Quote(authoritativeQuote(request, authentication).getTotal());
    }

    public PaymentTerminalResult chargeCard(
            CustomerPendingSaleController.CardChargeRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        var sale = Objects.requireNonNull(request.sale(), "sale");
        var amount = positive(request.amount(), "amount");
        var terminalId = currentTerminal.terminalId(authentication);
        var total = authoritativeQuote(sale, authentication).getTotal();
        requireQuotedTotal(sale, total);
        var cardPayment = requireIntegratedCardPayment(sale);
        if (Money.euros(cardPayment.amount()).compareTo(amount) != 0) {
            throw new IllegalArgumentException("card_charge_amount_mismatch");
        }
        var hash = CustomerPendingSaleRequestHasher.hash(sale, total);
        var configuration = configurations.required(terminalId);
        if (!organization.currentStore().getId().equals(configuration.storeId())) {
            throw new IllegalStateException("payment_operation_scope_mismatch");
        }
        return terminalOperations.charge(sale.checkoutId(), hash, amount, configuration);
    }

    @Transactional
    public CustomerReceivableView create(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        var terminalId = currentTerminal.terminalId(authentication);
        var storeId = organization.currentStore().getId();
        var userId = organization.currentUser(authentication).getId();
        var owner = UUID.randomUUID();
        var replayHash = CustomerPendingSaleRequestHasher.hash(
                request, Objects.requireNonNull(request.quotedTotal(), "quotedTotal"));

        var existing = reservations.find(
                terminalId, request.checkoutId());
        CustomerPendingSaleCheckout checkout = null;
        if (existing.isPresent()) {
            var current = existing.orElseThrow();
            var replay = replayIfCompleted(
                    current, replayHash, storeId, userId, request);
            if (replay.isPresent()) return replay.orElseThrow();
            checkout = reservations.claim(terminalId, request.checkoutId(), storeId, userId,
                    replayHash, owner, Instant.now(clock).plus(CHECKOUT_LEASE),
                    Instant.now(clock));
        }

        var total = authoritativeQuote(request, authentication).getTotal();
        requireQuotedTotal(request, total);
        var hash = CustomerPendingSaleRequestHasher.hash(request, total);

        if (existing.isEmpty()) {
            checkout = CustomerPendingSaleCheckout.reserve(
                    UUID.randomUUID(), request.checkoutId(), terminalId, storeId, userId,
                    hash, owner, Instant.now(clock).plus(CHECKOUT_LEASE), Instant.now(clock));
            try {
                reservations.insert(checkout);
            } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
                var winner = reservations.findAfterConflict(terminalId, request.checkoutId());
                var replay = replayIfCompleted(winner, hash, storeId, userId, request);
                if (replay.isPresent()) return replay.orElseThrow();
                checkout = reservations.claim(terminalId, request.checkoutId(), storeId, userId,
                        hash, owner, Instant.now(clock).plus(CHECKOUT_LEASE),
                        Instant.now(clock));
            }
        }

        try {
            reservations.lockOwned(checkout.getId(), owner);
            var declaredCard = integratedCardPayment(request);
            var durableCharge = terminalOperations.find(request.checkoutId())
                    .filter(CustomerPendingSaleService::isDurableCharge);
            if (durableCharge.isPresent() && declaredCard.isEmpty()) {
                throw unresolvedCard(durableCharge.orElseThrow());
            }
            PaymentTerminalOperation cardOperation = null;
            if (declaredCard.isPresent()) {
                requireExactCardAssociation(request, declaredCard.orElseThrow());
                cardOperation = terminalOperations.requireFinalizableApprovedCharge(
                        request.checkoutId());
                var configuration = configurations.required(terminalId);
                requireCardIdentity(cardOperation, configuration, hash,
                        declaredCard.orElseThrow().amount(), terminalId, storeId);
            }
            var commands = paymentCommands(request, cardOperation, terminalId);
            var document = documents.createPendingSale(
                    request.toCommand(), request.dueDate(), commands, authentication);
            if (cardOperation != null) {
                var payment = document.getPagos().stream()
                        .filter(value -> request.checkoutId().equals(value.getRequestId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "approved_card_payment_not_persisted"));
                terminalOperations.linkDocument(
                        cardOperation.getId(), document.getId(), payment.getId());
            }
            checkout.complete(document.getId(), Instant.now(clock));
            checkouts.save(checkout);
            return views.receivableView(document, request.date());
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    private static boolean isDurableCharge(PaymentTerminalOperation operation) {
        return operation.getOperationType()
                == com.tpverp.backend.terminal.PaymentTerminalOperationType.CHARGE;
    }

    private static IllegalStateException unresolvedCard(PaymentTerminalOperation operation) {
        return operation.getStatus() == PaymentTerminalOperationStatus.APPROVED
                ? new IllegalStateException("approved_card_payment_required")
                : new IllegalStateException("payment_operation_resolution_required");
    }

    private Optional<CustomerReceivableView> replayIfCompleted(
            CustomerPendingSaleCheckout checkout,
            String hash,
            UUID storeId,
            UUID userId,
            CustomerPendingSaleController.CreateRequest request) {
        if (!checkout.matchesScope(storeId, userId)) {
            throw new IllegalStateException("pending_sale_checkout_scope_mismatch");
        }
        if (!checkout.matchesHash(hash)) {
            throw new IllegalStateException("pending_sale_checkout_idempotency_conflict");
        }
        if (!checkout.isCompleted()) return Optional.empty();
        return Optional.of(views.receivableView(
                documents.find(checkout.getDocumentId()), request.date()));
    }

    private CommercialDocument authoritativeQuote(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        return documents.quotePendingSale(
                request.toCommand(), request.dueDate(), authentication);
    }

    private static void requireQuotedTotal(
            CustomerPendingSaleController.CreateRequest request, BigDecimal total) {
        if (request.quotedTotal() == null
                || Money.euros(request.quotedTotal()).compareTo(Money.euros(total)) != 0) {
            throw new IllegalStateException(
                    "El total de la venta ha cambiado; vuelve a cotizar el documento");
        }
    }

    private static Optional<CustomerPendingSaleController.PaymentItem> integratedCardPayment(
            CustomerPendingSaleController.CreateRequest request) {
        var cards = payments(request).stream()
                .filter(payment -> payment.kind()
                        == CustomerPendingSaleController.PaymentKind.INTEGRATED_CARD)
                .toList();
        if (cards.size() > 1) {
            throw new IllegalArgumentException("single_integrated_card_payment_required");
        }
        return cards.stream().findFirst();
    }

    private static CustomerPendingSaleController.PaymentItem requireIntegratedCardPayment(
            CustomerPendingSaleController.CreateRequest request) {
        var payment = integratedCardPayment(request)
                .orElseThrow(() -> new IllegalArgumentException(
                        "integrated_card_payment_required"));
        requireExactCardAssociation(request, payment);
        return payment;
    }

    private static void requireExactCardAssociation(
            CustomerPendingSaleController.CreateRequest request,
            CustomerPendingSaleController.PaymentItem payment) {
        if (!request.checkoutId().equals(payment.requestId())
                || !request.checkoutId().equals(payment.paymentTerminalOperationId())) {
            throw new IllegalStateException("approved_card_payment_required");
        }
    }

    private static List<CustomerPendingSaleController.PaymentItem> payments(
            CustomerPendingSaleController.CreateRequest request) {
        return List.copyOf(request.payments() == null ? List.of() : request.payments());
    }

    private static List<PaymentCommand> paymentCommands(
            CustomerPendingSaleController.CreateRequest request,
            PaymentTerminalOperation operation,
            UUID terminalId) {
        return payments(request).stream().map(payment -> {
            var integrated = payment.kind()
                    == CustomerPendingSaleController.PaymentKind.INTEGRATED_CARD;
            if (integrated && operation == null) {
                throw new IllegalStateException("payment_operation_not_finalizable");
            }
            return new PaymentCommand(
                    payment.methodId(), payment.amount(), payment.principal(), payment.delivered(),
                    payment.change(), payment.voucherCode(),
                    integrated ? operation.getExternalReference() : payment.reference(),
                    integrated ? PaymentCardMode.INTEGRATED : null,
                    integrated ? operation.getProvider() : null,
                    integrated ? PaymentTerminalOperationStatus.APPROVED : null,
                    integrated ? operation.getAuthorizationCode() : null,
                    integrated ? terminalId : null,
                    payment.requestId());
        }).toList();
    }

    private static void requireCardIdentity(
            PaymentTerminalOperation operation,
            com.tpverp.backend.terminal.CardTerminalConfiguration configuration,
            String hash,
            BigDecimal amount,
            UUID terminalId,
            UUID storeId) {
        if (!operation.getTerminalId().equals(terminalId)
                || !operation.getStoreId().equals(storeId)) {
            throw new IllegalStateException("payment_operation_scope_mismatch");
        }
        if (!operation.matchesConfigurationIdentity(configuration)) {
            throw new IllegalStateException("payment_operation_configuration_mismatch");
        }
        if (!Objects.equals(operation.getRequestHash(), hash)
                || operation.getAmount().compareTo(amount) != 0) {
            throw new IllegalStateException("payment_operation_identity_mismatch");
        }
    }

    private static BigDecimal positive(BigDecimal amount, String field) {
        var value = Money.euros(amount);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " debe ser positivo");
        }
        return value;
    }

    public record Quote(BigDecimal total) {
    }
}
