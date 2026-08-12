package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CardTerminalConfiguration;
import com.tpverp.backend.terminal.CardTerminalConfigurationReader;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperation;
import com.tpverp.backend.terminal.PaymentTerminalOperationService;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import com.tpverp.backend.terminal.PaymentTerminalResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
    private final SaleOperationSecurityService saleOperationSecurity;
    private final SaleDocumentMutationAuthorizationService documentMutationAuthorization;
    private final SaleDocumentAuthorizationManifestService authorizationManifests;
    private final PaymentMethodRepository paymentMethods;
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
            SaleOperationSecurityService saleOperationSecurity,
            SaleDocumentMutationAuthorizationService documentMutationAuthorization,
            SaleDocumentAuthorizationManifestService authorizationManifests,
            PaymentMethodRepository paymentMethods,
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
        this.saleOperationSecurity = saleOperationSecurity;
        this.documentMutationAuthorization = documentMutationAuthorization;
        this.authorizationManifests = authorizationManifests;
        this.paymentMethods = paymentMethods;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Quote quote(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        var total = authoritativeQuote(request, authentication).getTotal();
        return new Quote(total, assessCredit(request, total, false));
    }

    @Transactional(readOnly = true)
    public Quote quoteDraft(
            UUID draftId,
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        var quoted = effectiveQuote(draftId, request, authentication);
        return new Quote(quoted.document().getTotal(),
                assessCredit(request, quoted.document().getTotal(), false));
    }

    public PaymentTerminalResult chargeCard(
            CustomerPendingSaleController.CardChargeRequest request,
            Authentication authentication) {
        return chargeCard(null, request, authentication);
    }

    public PaymentTerminalResult chargeDraftCard(
            UUID draftId,
            CustomerPendingSaleController.CardChargeRequest request,
            Authentication authentication) {
        return chargeCard(Objects.requireNonNull(draftId, "draftId"), request, authentication);
    }

    private PaymentTerminalResult chargeCard(
            UUID sourceDraftId,
            CustomerPendingSaleController.CardChargeRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        var sale = Objects.requireNonNull(request.sale(), "sale");
        var amount = positive(request.amount(), "amount");
        var terminalId = currentTerminal.terminalId(authentication);
        var quoted = effectiveQuote(sourceDraftId, sale, authentication);
        var total = quoted.document().getTotal();
        requireQuotedTotal(sale, total);
        if (quoted.persistedUnchanged()) {
            authorizePersistedDraft(quoted.document(), sale, authentication);
        } else {
            authorizeDocumentMutations(
                    sale, authentication, "CUSTOMER_PENDING_CARD_CHARGE");
        }
        var cardPayment = requireIntegratedCardPayment(sale);
        requireCardPaymentMethod(cardPayment, "integrated_card_payment_method_required");
        var credit = assessCredit(sale, total, false);
        requireCreditAllowed(credit);
        var creditAuthorization = authorizePendingCredit(
                credit,
                overrideReason(sale.creditOverride()),
                sale.authorizerUsername(),
                sale.authorizerPassword(),
                overrideAuthorizerUsername(sale.creditOverride()),
                overrideAuthorizerPassword(sale.creditOverride()),
                authentication);
        if (Money.euros(cardPayment.amount()).compareTo(amount) != 0) {
            throw new IllegalArgumentException("card_charge_amount_mismatch");
        }
        var hash = CustomerPendingSaleRequestHasher.hash(sale, total, sourceDraftId);
        var configuration = configurations.required(terminalId);
        requireIntegratedCardConfiguration(
                configuration, terminalId, organization.currentStore().getId());
        var result = terminalOperations.charge(
                sale.checkoutId(), hash, amount, configuration);
        if (creditAuthorization.pendingReceivable() != null) {
            recordPendingCardAuthorization(
                    sale.checkoutId(),
                    sale.customerId(),
                    credit,
                    overrideReason(sale.creditOverride()),
                    creditAuthorization);
        }
        return result;
    }

    @Transactional
    public CustomerReceivableView create(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        var document = createDocument(request, authentication);
        return views.receivableView(document, request.date());
    }

    @Transactional
    public CommercialDocument createDocument(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        return processDocument(null, request, authentication);
    }

    @Transactional
    public CommercialDocument updateDraft(
            UUID draftId,
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        if (request.completionMode()
                != CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT) {
            throw new IllegalArgumentException("sales_document_draft_mode_required");
        }
        return processDocument(Objects.requireNonNull(draftId, "draftId"), request, authentication);
    }

    @Transactional
    public CommercialDocument completeDraft(
            UUID draftId,
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        if (request.completionMode() == null
                || request.completionMode()
                == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT) {
            throw new IllegalArgumentException("sales_document_checkout_mode_required");
        }
        return processDocument(Objects.requireNonNull(draftId, "draftId"), request, authentication);
    }

    private CommercialDocument processDocument(
            UUID sourceDraftId,
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        var completionMode = request.completionMode();
        validateCompletionMode(request, completionMode);
        var terminalId = currentTerminal.terminalId(authentication);
        var storeId = organization.currentStore().getId();
        var userId = organization.currentUser(authentication).getId();
        var owner = UUID.randomUUID();
        var replayHash = CustomerPendingSaleRequestHasher.hash(
                request, Objects.requireNonNull(request.quotedTotal(), "quotedTotal"),
                sourceDraftId);

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

        var quoted = effectiveQuote(sourceDraftId, request, authentication);
        var total = quoted.document().getTotal();
        requireQuotedTotal(request, total);
        var mutationAuthorization = quoted.persistedUnchanged()
                ? authorizePersistedDraft(quoted.document(), request, authentication)
                : new DraftMutationAuthorization(
                        authorizeDocumentMutations(
                                request, authentication, "CUSTOMER_PENDING_DOCUMENT"),
                        true);
        if (completionMode == CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_AND_PAY
                && declaredPayments(request).compareTo(total) != 0) {
            throw new IllegalArgumentException("sales_document_checkout_payment_total_mismatch");
        }
        var credit = completionMode == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT
                ? null
                : assessCredit(request, total, true);
        var creditAuthorization = credit == null
                ? null
                : authorizePendingCredit(
                        credit,
                        overrideReason(request.creditOverride()),
                        request.authorizerUsername(),
                        request.authorizerPassword(),
                        overrideAuthorizerUsername(request.creditOverride()),
                        overrideAuthorizerPassword(request.creditOverride()),
                        authentication);
        var hash = CustomerPendingSaleRequestHasher.hash(request, total, sourceDraftId);

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
            if (completionMode == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT
                    && declaredCard.isPresent()) {
                throw new IllegalArgumentException("sales_document_draft_cannot_have_payments");
            }
            var durableCharge = terminalOperations.find(request.checkoutId())
                    .filter(CustomerPendingSaleService::isDurableCharge);
            if (durableCharge.isPresent() && declaredCard.isEmpty()) {
                throw unresolvedCard(durableCharge.orElseThrow());
            }
            PaymentTerminalOperation cardOperation = null;
            if (declaredCard.isPresent()) {
                requireCardPaymentMethod(
                        declaredCard.orElseThrow(),
                        "integrated_card_payment_method_required");
                requireExactCardAssociation(request, declaredCard.orElseThrow());
                cardOperation = terminalOperations.requireFinalizableApprovedCharge(
                        request.checkoutId());
                var configuration = configurations.required(terminalId);
                requireIntegratedCardConfiguration(configuration, terminalId, storeId);
                requireCardIdentity(cardOperation, configuration, hash,
                        declaredCard.orElseThrow().amount(), terminalId, storeId);
            }
            authorizeStandardPayments(request, authentication);
            var commands = paymentCommands(request, cardOperation, terminalId);
            CommercialDocument document;
            if (sourceDraftId == null) {
                document = completionMode
                        == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT
                        ? documents.createPendingSaleDraft(
                                request.toCommand(), request.dueDate(), authentication)
                        : documents.createPendingSale(
                                request.toCommand(), request.dueDate(), commands, authentication);
                if (completionMode
                        == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT) {
                    authorizationManifests.record(
                            document, Objects.requireNonNull(mutationAuthorization.proof()));
                }
            } else if (completionMode
                    == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT) {
                document = quoted.persistedUnchanged()
                        ? quoted.document()
                        : documents.updatePendingSaleDraft(
                                sourceDraftId, request.draftVersion(), request.toCommand(),
                                request.dueDate(), authentication);
                if (mutationAuthorization.replaceManifest()) {
                    authorizationManifests.replace(
                            document, Objects.requireNonNull(mutationAuthorization.proof()));
                }
            } else {
                document = documents.completePendingSaleDraft(
                        sourceDraftId,
                        request.draftVersion(),
                        quoted.persistedUnchanged() ? null : request.toCommand(),
                        request.dueDate(),
                        commands,
                        authentication);
            }
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
            if (creditAuthorization != null
                    && creditAuthorization.pendingReceivable() != null) {
                recordPendingReceivableAuthorization(
                        request.checkoutId(),
                        document,
                        request.customerId(),
                        credit,
                        creditAuthorization.pendingReceivable());
            }
            if (credit != null && credit.overrideUsed()) {
                recordCreditOverride(
                        request.creditOverride().reason(),
                        document,
                        request.customerId(),
                        credit,
                        creditAuthorization.creditOverride());
            }
            return document;
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    private static void validateCompletionMode(
            CustomerPendingSaleController.CreateRequest request,
            CustomerPendingSaleController.SalesDocumentCompletionMode completionMode) {
        var payments = request.payments() == null ? List.of() : request.payments();
        if ((completionMode == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT
                || completionMode == CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_PENDING)
                && !payments.isEmpty()) {
            throw new IllegalArgumentException(
                    completionMode == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT
                            ? "sales_document_draft_cannot_have_payments"
                            : "sales_document_pending_cannot_have_payments");
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

    private Optional<CommercialDocument> replayIfCompleted(
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
        return Optional.of(documents.find(checkout.getDocumentId()));
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
            boolean lockAndEnforce) {
        var newDebt = money(total.subtract(declaredPayments(request)));
        return assessCredit(
                request.customerId(),
                request.date(),
                request.dueDate(),
                newDebt,
                overrideReason(request.creditOverride()),
                lockAndEnforce);
    }

    private SaleDocumentMutationAuthorizationService.AuthorizationProof
            authorizeDocumentMutations(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication,
            String sourceType) {
        return documentMutationAuthorization.authorize(
                request.toCommand(),
                request.lines(),
                request.operationAuthorizations(),
                authentication,
                sourceType,
                request.checkoutId());
    }

    private DraftMutationAuthorization authorizePersistedDraft(
            CommercialDocument document,
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        var storedValidation = authorizationManifests.findValidation(document);
        if (storedValidation.isEmpty()) {
            return new DraftMutationAuthorization(
                    authorizeDocumentMutations(
                            request, authentication, "SALES_DOCUMENT_LEGACY_DRAFT_IMPORT"),
                    true);
        }
        var validation = storedValidation.orElseThrow();
        if (!validation.policyChanged()) {
            return new DraftMutationAuthorization(null, false);
        }
        var proof = documentMutationAuthorization.reauthorize(
                document,
                validation.operations(),
                request.operationAuthorizations(),
                authentication,
                "SALES_DOCUMENT_DRAFT_CONFIRMATION_REAUTHORIZATION",
                document.getId());
        return new DraftMutationAuthorization(proof, true);
    }

    private EffectiveQuote effectiveQuote(
            UUID sourceDraftId,
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        if (sourceDraftId == null) {
            return new EffectiveQuote(authoritativeQuote(request, authentication), false);
        }
        var persisted = documents.pendingSaleDraft(
                sourceDraftId, request.draftVersion(), authentication);
        if (documents.pendingSaleDraftMatches(persisted, request)) {
            return new EffectiveQuote(persisted, true);
        }
        return new EffectiveQuote(authoritativeQuote(request, authentication), false);
    }

    private CreditAssessment assessCredit(
            UUID customerId,
            LocalDate date,
            LocalDate dueDate,
            BigDecimal newDebt,
            String creditOverrideReason,
            boolean lockAndEnforce) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(dueDate, "dueDate");
        if (dueDate.isBefore(date)) {
            throw new IllegalArgumentException("message.document.pending_sale_due_date_before_issue_date");
        }
        var companyId = organization.currentCompany().getId();
        Customer customer = (lockAndEnforce
                ? customers.findLockedByIdAndCompanyId(customerId, companyId)
                : customers.findByIdAndCompanyId(customerId, companyId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "message.document.active_customer_not_found"));
        return assessLockedCustomer(
                customer,
                date,
                dueDate,
                newDebt,
                creditOverrideReason,
                lockAndEnforce);
    }

    @Transactional
    PendingCreditAuthorization authorizePendingTicket(
            UUID customerId,
            LocalDate date,
            BigDecimal newDebt,
            String creditOverrideReason,
            String authorizerUsername,
            String authorizerPassword,
            String creditOverrideAuthorizerUsername,
            String creditOverrideAuthorizerPassword,
            Authentication authentication) {
        Objects.requireNonNull(date, "date");
        var companyId = organization.currentCompany().getId();
        var customer = customers.findLockedByIdAndCompanyId(customerId, companyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "message.document.active_customer_not_found"));
        var dueDate = date.plusDays(customer.getPaymentTermDays());
        var credit = assessLockedCustomer(
                customer,
                date,
                dueDate,
                newDebt,
                creditOverrideReason,
                true);
        var authorization = authorizePendingCredit(
                credit,
                creditOverrideReason,
                authorizerUsername,
                authorizerPassword,
                creditOverrideAuthorizerUsername,
                creditOverrideAuthorizerPassword,
                authentication);
        return new PendingCreditAuthorization(
                credit,
                authorization.pendingReceivable(),
                authorization.creditOverride());
    }

    private CreditAssessment assessLockedCustomer(
            Customer customer,
            LocalDate date,
            LocalDate dueDate,
            BigDecimal newDebt,
            String creditOverrideReason,
            boolean lockAndEnforce) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(dueDate, "dueDate");
        if (dueDate.isBefore(date)) {
            throw new IllegalArgumentException("message.document.pending_sale_due_date_before_issue_date");
        }
        var latestDueDate = date.plusDays(customer.getPaymentTermDays());
        if (dueDate.isAfter(latestDueDate)) {
            throw new IllegalArgumentException("message.document.pending_sale_due_date_exceeds_customer_terms");
        }
        var outstanding = money(customers.outstandingDebt(customer.getId()));
        var overdue = money(customers.overdueDebt(customer.getId(), date));
        var normalizedNewDebt = money(newDebt);
        if (normalizedNewDebt.signum() < 0) {
            normalizedNewDebt = Money.euros(BigDecimal.ZERO);
        }
        var creditRequired = normalizedNewDebt.signum() > 0;
        var proposed = money(outstanding.add(normalizedNewDebt));
        var limit = customer.getCreditLimit();
        var available = limit == null ? null : money(limit.subtract(outstanding));
        var availableAfter = limit == null ? null : money(limit.subtract(proposed));
        var manualBlocked = creditRequired && customer.isCreditBlocked();
        var overdueBlocked = creditRequired
                && customer.isBlockOnOverdue() && overdue.signum() > 0;
        var limitExceeded = creditRequired
                && limit != null && proposed.compareTo(limit) > 0;
        var overrideUsed = false;
        if (limitExceeded && creditOverrideReason != null) {
            if (creditOverrideReason.isBlank()) {
                throw new IllegalArgumentException("message.document.credit_override_reason_required");
            }
            overrideUsed = true;
        }
        var credit = new CreditAssessment(
                customer.isCreditEnabled(), creditRequired, manualBlocked || overdueBlocked,
                customer.isBlockOnOverdue(), creditBlockReason(
                customer.isCreditEnabled(), creditRequired,
                manualBlocked, overdueBlocked, limitExceeded),
                manualBlocked, overdueBlocked, limitExceeded,
                limit, outstanding, overdue, available, customer.getPaymentTermDays(),
                proposed, availableAfter, limitExceeded && !overrideUsed, overrideUsed,
                latestDueDate);
        if (lockAndEnforce) {
            requireCreditAllowed(credit);
        }
        return credit;
    }

    private PendingCreditAuthorization authorizePendingCredit(
            CreditAssessment credit,
            String creditOverrideReason,
            String authorizerUsername,
            String authorizerPassword,
            String creditOverrideAuthorizerUsername,
            String creditOverrideAuthorizerPassword,
            Authentication authentication) {
        if (!credit.creditRequired()) {
            return new PendingCreditAuthorization(credit, null, null);
        }
        var pendingAuthorization = saleOperationSecurity.authorize(
                SaleOperationCode.CREATE_PENDING_RECEIVABLE,
                authorizerUsername,
                authorizerPassword,
                authentication);
        Authorization overrideAuthorization = null;
        if (credit.overrideUsed()) {
            if (creditOverrideReason == null || creditOverrideReason.isBlank()) {
                throw new IllegalArgumentException(
                        "message.document.credit_override_reason_required");
            }
            overrideAuthorization = saleOperationSecurity.authorize(
                    SaleOperationCode.CREDIT_OVERRIDE,
                    dedicatedOverrideCredentials(
                            creditOverrideAuthorizerUsername,
                            creditOverrideAuthorizerPassword)
                            ? creditOverrideAuthorizerUsername
                            : authorizerUsername,
                    dedicatedOverrideCredentials(
                            creditOverrideAuthorizerUsername,
                            creditOverrideAuthorizerPassword)
                            ? creditOverrideAuthorizerPassword
                            : authorizerPassword,
                    authentication);
        }
        return new PendingCreditAuthorization(
                credit, pendingAuthorization, overrideAuthorization);
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
            throw new CustomerCreditLimitExceededException();
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
            String reason,
            CommercialDocument document,
            UUID customerId,
            CreditAssessment credit,
            Authorization authorization) {
        var details = new LinkedHashMap<String, Object>();
        details.put("documentId", document.getId());
        details.put("customerId", customerId);
        details.put("reason", reason.trim());
        details.put("outstandingDebt", credit.outstandingDebt());
        details.put("proposedOutstanding", credit.proposedOutstanding());
        details.put("creditLimit", credit.limit());
        addAuthorizationDetails(details, authorization);
        audit.record("CUSTOMER_CREDIT_LIMIT_OVERRIDDEN", AuditResult.EXITO, details);
    }

    void recordPendingTicketAuthorization(
            UUID checkoutId,
            CommercialDocument document,
            UUID customerId,
            String creditOverrideReason,
            PendingCreditAuthorization authorization) {
        recordPendingReceivableAuthorization(
                checkoutId,
                document,
                customerId,
                authorization.credit(),
                authorization.pendingReceivable());
        if (authorization.credit().overrideUsed()) {
            recordCreditOverride(
                    creditOverrideReason,
                    document,
                    customerId,
                    authorization.credit(),
                    authorization.creditOverride());
        }
    }

    private void recordPendingReceivableAuthorization(
            UUID checkoutId,
            CommercialDocument document,
            UUID customerId,
            CreditAssessment credit,
            Authorization authorization) {
        var details = new LinkedHashMap<String, Object>();
        details.put("checkoutId", checkoutId.toString());
        details.put("documentId", document.getId().toString());
        details.put("customerId", customerId.toString());
        details.put("newDebt", money(
                credit.proposedOutstanding().subtract(credit.outstandingDebt())));
        addAuthorizationDetails(details, authorization);
        audit.record("CUSTOMER_PENDING_RECEIVABLE_AUTHORIZED", AuditResult.EXITO, details);
    }

    private void recordPendingCardAuthorization(
            UUID checkoutId,
            UUID customerId,
            CreditAssessment credit,
            String creditOverrideReason,
            PendingCreditAuthorization authorization) {
        var details = new LinkedHashMap<String, Object>();
        details.put("checkoutId", checkoutId.toString());
        details.put("customerId", customerId.toString());
        details.put("newDebt", money(
                credit.proposedOutstanding().subtract(credit.outstandingDebt())));
        details.put("creditOverride", authorization.creditOverride() != null);
        if (authorization.creditOverride() != null) {
            details.put("creditOverrideReason", creditOverrideReason.trim());
        }
        addAuthorizationDetails(details, authorization.pendingReceivable());
        if (authorization.creditOverride() != null) {
            details.put(
                    "creditOverrideAuthorizerUserId",
                    authorization.creditOverride().authorizer().getId().toString());
            details.put(
                    "creditOverrideAuthorizerUsername",
                    authorization.creditOverride().authorizer().getUserName());
            details.put(
                    "creditOverrideDelegated",
                    authorization.creditOverride().delegated());
        }
        audit.record(
                "CUSTOMER_PENDING_RECEIVABLE_CARD_AUTHORIZED",
                AuditResult.EXITO,
                details);
    }

    private static void addAuthorizationDetails(
            LinkedHashMap<String, Object> details,
            Authorization authorization) {
        if (authorization == null) {
            return;
        }
        details.put("operatorUserId", authorization.operator().getId().toString());
        details.put("operatorUsername", authorization.operator().getUserName());
        details.put("authorizerUserId", authorization.authorizer().getId().toString());
        details.put("authorizerUsername", authorization.authorizer().getUserName());
        details.put("delegated", authorization.delegated());
    }

    private static String overrideReason(
            CustomerPendingSaleController.CreditOverride override) {
        return override == null ? null : override.reason();
    }

    private static String overrideAuthorizerUsername(
            CustomerPendingSaleController.CreditOverride override) {
        return override == null ? null : override.authorizerUsername();
    }

    private static String overrideAuthorizerPassword(
            CustomerPendingSaleController.CreditOverride override) {
        return override == null ? null : override.authorizerPassword();
    }

    private static boolean dedicatedOverrideCredentials(
            String username,
            String password) {
        return username != null || password != null;
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

    private List<PaymentCommand> paymentCommands(
            CustomerPendingSaleController.CreateRequest request,
            PaymentTerminalOperation operation,
            UUID terminalId) {
        return payments(request).stream().map(payment -> {
            var integrated = payment.kind()
                    == CustomerPendingSaleController.PaymentKind.INTEGRATED_CARD;
            var method = requireActivePaymentMethod(payment);
            var cardMethod = isCardMethod(method);
            if (integrated && !cardMethod) {
                throw new IllegalArgumentException(
                        "integrated_card_payment_method_required");
            }
            if (payment.kind() == CustomerPendingSaleController.PaymentKind.MANUAL_CARD
                    && !cardMethod) {
                throw new IllegalArgumentException(
                        "manual_card_payment_method_required");
            }
            var manualCard = payment.kind()
                    == CustomerPendingSaleController.PaymentKind.MANUAL_CARD
                    || (payment.kind() == CustomerPendingSaleController.PaymentKind.STANDARD
                    && cardMethod);
            if (integrated && operation == null) {
                throw new IllegalStateException("payment_operation_not_finalizable");
            }
            return new PaymentCommand(
                    payment.methodId(), payment.amount(), payment.principal(), payment.delivered(),
                    payment.change(), payment.voucherCode(),
                    integrated ? operation.getExternalReference() : payment.reference(),
                    integrated ? PaymentCardMode.INTEGRATED
                            : manualCard ? PaymentCardMode.MANUAL : null,
                    integrated ? operation.getProvider() : null,
                    integrated ? PaymentTerminalOperationStatus.APPROVED : null,
                    integrated ? operation.getAuthorizationCode() : null,
                    integrated ? terminalId : null,
                    payment.requestId());
        }).toList();
    }

    private void authorizeStandardPayments(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        var operations = EnumSet.noneOf(SaleOperationCode.class);
        for (var payment : payments(request)) {
            if (payment.kind()
                    == CustomerPendingSaleController.PaymentKind.INTEGRATED_CARD) {
                continue;
            }
            var method = requireActivePaymentMethod(payment);
            if (payment.kind() == CustomerPendingSaleController.PaymentKind.MANUAL_CARD
                    && !isCardMethod(method)) {
                throw new IllegalArgumentException(
                        "manual_card_payment_method_required");
            }
            if (isCardMethod(method)) {
                operations.add(SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT);
            } else if ("TRANSFERENCIA".equals(method.getNombre())) {
                operations.add(SaleOperationCode.CONFIRM_TRANSFER_PAYMENT);
            }
        }
        var credentials = request.operationAuthorizations() == null
                ? Map.<SaleOperationCode, OperationAuthorizationRequest>of()
                : request.operationAuthorizations();
        for (var code : operations) {
            var requested = credentials.getOrDefault(
                    code, OperationAuthorizationRequest.empty());
            var authorization = saleOperationSecurity.authorize(
                    code,
                    requested.authorizerUsername(),
                    requested.authorizerPassword(),
                    authentication);
            auditStandardPaymentAuthorization(
                    request.checkoutId(), code, authorization);
        }
    }

    private PaymentMethod requireCardPaymentMethod(
            CustomerPendingSaleController.PaymentItem payment,
            String errorCode) {
        var method = requireActivePaymentMethod(payment);
        if (!isCardMethod(method)) {
            throw new IllegalArgumentException(errorCode);
        }
        return method;
    }

    private PaymentMethod requireActivePaymentMethod(
            CustomerPendingSaleController.PaymentItem payment) {
        return paymentMethods.findByIdAndEmpresaId(
                        payment.methodId(), organization.currentCompany().getId())
                .filter(PaymentMethod::isActivo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "message.payment_method.active_not_found"));
    }

    private static boolean isCardMethod(PaymentMethod method) {
        return "TARJETA".equals(method.getNombre());
    }

    private static void requireIntegratedCardConfiguration(
            CardTerminalConfiguration configuration,
            UUID terminalId,
            UUID storeId) {
        if (!configuration.enabled()
                || configuration.mode() != PaymentCardMode.INTEGRATED
                || configuration.provider() == null
                || configuration.provider() == PaymentTerminalProvider.NONE) {
            throw new IllegalStateException(
                    "payment_terminal_configuration_not_integrated");
        }
        if (!terminalId.equals(configuration.terminalId())
                || !storeId.equals(configuration.storeId())) {
            throw new IllegalStateException("payment_operation_scope_mismatch");
        }
    }

    private void auditStandardPaymentAuthorization(
            UUID checkoutId,
            SaleOperationCode code,
            Authorization authorization) {
        var details = new LinkedHashMap<String, Object>();
        details.put("operationCode", code.name());
        details.put("sourceType", "CUSTOMER_PENDING_SALE");
        details.put("sourceId", checkoutId.toString());
        details.put("operatorId", authorization.operator().getId().toString());
        details.put("operatorUsername", authorization.operator().getUserName());
        details.put("authorizerId", authorization.authorizer().getId().toString());
        details.put("authorizerUsername", authorization.authorizer().getUserName());
        details.put("delegated", authorization.delegated());
        audit.record(
                PosCashService.SALE_OPERATION_AUTHORIZED,
                AuditResult.EXITO,
                Map.copyOf(details));
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

    private record EffectiveQuote(
            CommercialDocument document, boolean persistedUnchanged) {}

    private record DraftMutationAuthorization(
            SaleDocumentMutationAuthorizationService.AuthorizationProof proof,
            boolean replaceManifest) {}

    public record Quote(BigDecimal total, CreditAssessment credit) {}

    record PendingCreditAuthorization(
            CreditAssessment credit,
            Authorization pendingReceivable,
            Authorization creditOverride) {
    }

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
