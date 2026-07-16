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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerPendingSaleService {

    private final DocumentService documents;
    private final CustomerPendingSaleCheckoutRepository checkouts;
    private final PaymentTerminalOperationService terminalOperations;
    private final CardTerminalConfigurationReader configurations;
    private final CurrentTerminal currentTerminal;
    private final CurrentOrganization organization;
    private final DocumentViewAssembler views;
    private final Clock clock;

    public CustomerPendingSaleService(
            DocumentService documents,
            CustomerPendingSaleCheckoutRepository checkouts,
            PaymentTerminalOperationService terminalOperations,
            CardTerminalConfigurationReader configurations,
            CurrentTerminal currentTerminal,
            CurrentOrganization organization,
            DocumentViewAssembler views,
            Clock clock) {
        this.documents = documents;
        this.checkouts = checkouts;
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
        var hash = CustomerPendingSaleRequestHasher.hash(sale, amount, total);
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
        var cardAmount = cardAmount(request);
        var replayHash = CustomerPendingSaleRequestHasher.hash(
                request, cardAmount, Objects.requireNonNull(request.quotedTotal(), "quotedTotal"));

        var existing = checkouts.findByTerminalIdAndCheckoutId(
                terminalId, request.checkoutId());
        if (existing.isPresent()) {
            return replayOrContinue(
                    existing.orElseThrow(), replayHash, storeId, userId, request);
        }

        var total = authoritativeQuote(request, authentication).getTotal();
        requireQuotedTotal(request, total);
        var hash = CustomerPendingSaleRequestHasher.hash(request, cardAmount, total);

        var checkout = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), terminalId, storeId, userId,
                hash, Instant.now(clock));
        checkouts.saveAndFlush(checkout);

        PaymentTerminalOperation cardOperation = null;
        if (cardAmount.signum() > 0) {
            cardOperation = terminalOperations.requireFinalizableApprovedCharge(
                    request.checkoutId());
            requireCardIdentity(cardOperation, hash, cardAmount, terminalId, storeId);
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
        checkout.complete(document.getId());
        checkouts.save(checkout);
        return views.receivableView(document, request.date());
    }

    private CustomerReceivableView replayOrContinue(
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
        if (!checkout.isCompleted()) {
            throw new IllegalStateException("pending_sale_checkout_in_progress");
        }
        return views.receivableView(documents.find(checkout.getDocumentId()), request.date());
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

    private static BigDecimal cardAmount(
            CustomerPendingSaleController.CreateRequest request) {
        return (request.payments() == null ? List.<CustomerPendingSaleController.PaymentItem>of()
                : request.payments()).stream()
                .filter(payment -> request.checkoutId().equals(paymentRequestId(request, payment)))
                .map(CustomerPendingSaleController.PaymentItem::amount)
                .map(Money::euros)
                .reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
    }

    private static UUID paymentRequestId(
            CustomerPendingSaleController.CreateRequest request,
            CustomerPendingSaleController.PaymentItem payment) {
        return payment.requestId();
    }

    private static List<PaymentCommand> paymentCommands(
            CustomerPendingSaleController.CreateRequest request,
            PaymentTerminalOperation operation,
            UUID terminalId) {
        var payments = request.payments() == null
                ? List.<CustomerPendingSaleController.PaymentItem>of() : request.payments();
        return payments.stream().map(payment -> {
            var integrated = request.checkoutId().equals(paymentRequestId(request, payment));
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
            String hash,
            BigDecimal amount,
            UUID terminalId,
            UUID storeId) {
        if (!operation.getTerminalId().equals(terminalId)
                || !operation.getStoreId().equals(storeId)) {
            throw new IllegalStateException("payment_operation_scope_mismatch");
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
