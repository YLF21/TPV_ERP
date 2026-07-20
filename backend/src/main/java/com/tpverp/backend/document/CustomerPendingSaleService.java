package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.application.PermissionChecks;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
    private final CustomerRepository customers;
    private final AuditService audit;
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
            CustomerRepository customers,
            AuditService audit,
            DocumentViewAssembler views,
            Clock clock) {
        this.documents = documents;
        this.checkouts = checkouts;
        this.reservations = reservations;
        this.terminalOperations = terminalOperations;
        this.configurations = configurations;
        this.currentTerminal = currentTerminal;
        this.organization = organization;
        this.customers = customers;
        this.audit = audit;
        this.views = views;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Quote quote(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        var total = authoritativeQuote(request, authentication).getTotal();
        return new Quote(total, assessCredit(request, total, authentication, false));
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
        var credit = assessCredit(sale, total, authentication, false);
        requireCreditAllowed(credit);
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
        var credit = assessCredit(request, total, authentication, true);
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
            if (credit.overrideUsed()) {
                recordCreditOverride(request, document, credit);
            }
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

    private CreditAssessment assessCredit(
            CustomerPendingSaleController.CreateRequest request,
            BigDecimal total,
            Authentication authentication,
            boolean lockAndEnforce) {
        Objects.requireNonNull(request.date(), "date");
        Objects.requireNonNull(request.dueDate(), "dueDate");
        if (request.dueDate().isBefore(request.date())) {
            throw new IllegalArgumentException("message.document.pending_sale_due_date_before_issue_date");
        }
        var companyId = organization.currentCompany().getId();
        Customer customer = (lockAndEnforce
                ? customers.findLockedByIdAndCompanyId(request.customerId(), companyId)
                : customers.findByIdAndCompanyId(request.customerId(), companyId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "message.document.active_customer_not_found"));
        var latestDueDate = request.date().plusDays(customer.getPaymentTermDays());
        if (request.dueDate().isAfter(latestDueDate)) {
            throw new IllegalArgumentException("message.document.pending_sale_due_date_exceeds_customer_terms");
        }

        var outstanding = money(customers.outstandingDebt(customer.getId()));
        var overdue = money(customers.overdueDebt(customer.getId(), request.date()));
        var newDebt = money(total.subtract(declaredPayments(request)));
        if (newDebt.signum() < 0) {
            newDebt = Money.euros(BigDecimal.ZERO);
        }
        var creditRequired = newDebt.signum() > 0;
        var proposed = money(outstanding.add(newDebt));
        var limit = customer.getCreditLimit();
        var available = limit == null ? null : money(limit.subtract(outstanding));
        var availableAfter = limit == null ? null : money(limit.subtract(proposed));

        var manualBlocked = creditRequired && customer.isCreditBlocked();
        var overdueBlocked = creditRequired
                && customer.isBlockOnOverdue() && overdue.signum() > 0;
        var limitExceeded = creditRequired
                && limit != null && proposed.compareTo(limit) > 0;
        var overrideUsed = false;
        if (limitExceeded && request.creditOverride() != null) {
            var reason = request.creditOverride().reason();
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("message.document.credit_override_reason_required");
            }
            if (!PermissionChecks.hasRole(authentication, "ADMIN")
                    && !PermissionChecks.hasAuthority(
                    authentication, CorePermissionBootstrap.CUSTOMER_CREDIT_OVERRIDE)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "message.document.customer_credit_override_permission_required");
            }
            overrideUsed = true;
        }
        if (limitExceeded && lockAndEnforce && !overrideUsed) {
            throw new IllegalStateException("message.document.customer_credit_limit_exceeded");
        }
        if (lockAndEnforce) {
            requireCreditAllowed(new CreditAssessment(
                    customer.isCreditEnabled(), creditRequired, manualBlocked || overdueBlocked,
                    customer.isBlockOnOverdue(), creditBlockReason(
                    customer.isCreditEnabled(), creditRequired,
                    manualBlocked, overdueBlocked, limitExceeded),
                    manualBlocked, overdueBlocked, limitExceeded,
                    limit, outstanding, overdue, available, customer.getPaymentTermDays(),
                    proposed, availableAfter, limitExceeded && !overrideUsed, overrideUsed,
                    latestDueDate));
        }

        return new CreditAssessment(
                customer.isCreditEnabled(), creditRequired, manualBlocked || overdueBlocked,
                customer.isBlockOnOverdue(), creditBlockReason(
                customer.isCreditEnabled(), creditRequired,
                manualBlocked, overdueBlocked, limitExceeded),
                manualBlocked, overdueBlocked, limitExceeded,
                limit, outstanding, overdue, available, customer.getPaymentTermDays(),
                proposed, availableAfter, limitExceeded && !overrideUsed, overrideUsed,
                latestDueDate);
    }

    private static String creditBlockReason(
            boolean enabled, boolean creditRequired,
            boolean manualBlocked, boolean overdueBlocked,
            boolean limitExceeded) {
        if (!creditRequired) return null;
        if (!enabled) return "CREDIT_DISABLED";
        if (manualBlocked) return "CREDIT_BLOCKED";
        if (overdueBlocked) return "OVERDUE_DEBT";
        return limitExceeded ? "CREDIT_LIMIT_EXCEEDED" : null;
    }

    private static void requireCreditAllowed(CreditAssessment credit) {
        if (credit.creditRequired() && !credit.enabled()) {
            throw new IllegalStateException("message.document.customer_credit_disabled");
        }
        if (credit.manualBlocked()) {
            throw new IllegalStateException("message.document.customer_credit_blocked");
        }
        if (credit.overdueBlocked()) {
            throw new IllegalStateException("message.document.customer_credit_blocked_by_overdue_debt");
        }
        if (credit.requiresOverride()) {
            throw new IllegalStateException("message.document.customer_credit_limit_exceeded");
        }
    }

    private static BigDecimal declaredPayments(
            CustomerPendingSaleController.CreateRequest request) {
        return payments(request).stream()
                .map(CustomerPendingSaleController.PaymentItem::amount)
                .filter(Objects::nonNull)
                .map(Money::euros)
                .reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
    }

    private static BigDecimal money(BigDecimal value) {
        return Money.euros(value == null ? BigDecimal.ZERO : value);
    }

    private void recordCreditOverride(
            CustomerPendingSaleController.CreateRequest request,
            CommercialDocument document,
            CreditAssessment credit) {
        var details = new LinkedHashMap<String, Object>();
        details.put("documentId", document.getId());
        details.put("customerId", request.customerId());
        details.put("reason", request.creditOverride().reason().trim());
        details.put("outstandingDebt", credit.outstandingDebt());
        details.put("proposedOutstanding", credit.proposedOutstanding());
        details.put("creditLimit", credit.limit());
        audit.record("CUSTOMER_CREDIT_LIMIT_OVERRIDDEN", AuditResult.EXITO, details);
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

    public record Quote(BigDecimal total, CreditAssessment credit) {}

    public record CreditAssessment(
            boolean enabled,
            boolean creditRequired,
            boolean blocked,
            boolean blockOnOverdue,
            String blockReason,
            boolean manualBlocked,
            boolean overdueBlocked,
            boolean limitExceeded,
            BigDecimal limit,
            BigDecimal outstandingDebt,
            BigDecimal overdueDebt,
            BigDecimal availableCredit,
            int paymentTermDays,
            BigDecimal proposedOutstanding,
            BigDecimal availableAfterSale,
            boolean requiresOverride,
            boolean overrideUsed,
            LocalDate latestDueDate) {}
}
