package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.domain.UserAccount;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class CustomerPendingSaleServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    @Mock DocumentService documents;
    @Mock CustomerPendingSaleCheckoutRepository checkouts;
    @Mock CustomerPendingSaleCheckoutReservation reservations;
    @Mock PaymentTerminalOperationService terminalOperations;
    @Mock CardTerminalConfigurationReader configurations;
    @Mock CurrentTerminal currentTerminal;
    @Mock CurrentOrganization organization;
    @Mock CustomerRepository customers;
    @Mock AuditService audit;
    @Mock DocumentViewAssembler views;
    @Mock SaleOperationSecurityService saleOperationSecurity;
    @Mock SaleDocumentMutationAuthorizationService documentMutationAuthorization;
    @Mock PaymentMethodRepository paymentMethods;
    @Mock Authentication authentication;
    @Mock Store store;
    @Mock Company company;
    @Mock UserAccount user;
    @Mock Customer customer;

    private CustomerPendingSaleService service;
    private UUID terminalId;
    private UUID storeId;
    private UUID userId;
    private UUID companyId;
    private UUID cardMethodId;

    @BeforeEach
    void setUp() {
        terminalId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        cardMethodId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(currentTerminal.terminalId(authentication)).thenReturn(terminalId);
        org.mockito.Mockito.lenient().when(organization.currentStore()).thenReturn(store);
        org.mockito.Mockito.lenient().when(store.getId()).thenReturn(storeId);
        org.mockito.Mockito.lenient().when(organization.currentCompany()).thenReturn(company);
        org.mockito.Mockito.lenient().when(company.getId()).thenReturn(companyId);
        org.mockito.Mockito.lenient().when(organization.currentUser(authentication)).thenReturn(user);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(userId);
        org.mockito.Mockito.lenient().when(user.getUserName()).thenReturn("OPERATOR");
        org.mockito.Mockito.lenient().when(customers.findByIdAndCompanyId(any(), eq(companyId)))
                .thenReturn(Optional.of(customer));
        org.mockito.Mockito.lenient().when(customers.findLockedByIdAndCompanyId(any(), eq(companyId)))
                .thenReturn(Optional.of(customer));
        org.mockito.Mockito.lenient().when(customer.isCreditEnabled()).thenReturn(true);
        org.mockito.Mockito.lenient().when(customer.getPaymentTermDays()).thenReturn(30);
        org.mockito.Mockito.lenient().when(customers.outstandingDebt(any())).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.lenient().when(customers.overdueDebt(any(), any())).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.lenient().when(saleOperationSecurity.authorize(
                        any(SaleOperationCode.class),
                        org.mockito.ArgumentMatchers.nullable(String.class),
                        org.mockito.ArgumentMatchers.nullable(String.class),
                        eq(authentication)))
                .thenReturn(new Authorization(user, user, false));
        org.mockito.Mockito.lenient().when(paymentMethods.findByIdAndEmpresaId(
                        any(), eq(companyId)))
                .thenAnswer(invocation -> Optional.of(new PaymentMethod(
                        companyId,
                        cardMethodId.equals(invocation.getArgument(0))
                                ? "TARJETA"
                                : "EFECTIVO",
                        true)));
        service = new CustomerPendingSaleService(
                documents, checkouts, reservations, terminalOperations, configurations,
                currentTerminal, organization, customers, audit, views,
                saleOperationSecurity, documentMutationAuthorization, paymentMethods,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void quoteUsesAuthoritativeDocumentTotal() {
        var request = request(List.of(), new BigDecimal("100.00"));
        var quote = document(new BigDecimal("100.00"));
        when(documents.quotePendingSale(any(), eq(request.dueDate()), eq(authentication)))
                .thenReturn(quote);

        assertThat(service.quote(request, authentication).total())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void quoteUsesInitialPaymentsWhenCalculatingAvailableCredit() {
        var request = request(List.of(standardPayment(new BigDecimal("50.00"))),
                new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(customer.getCreditLimit()).thenReturn(new BigDecimal("100.00"));
        when(customers.outstandingDebt(any())).thenReturn(new BigDecimal("40.00"));

        var credit = service.quote(request, authentication).credit();

        assertThat(credit.outstandingDebt()).isEqualByComparingTo("40.00");
        assertThat(credit.proposedOutstanding()).isEqualByComparingTo("90.00");
        assertThat(credit.availableAfterSale()).isEqualByComparingTo("10.00");
        assertThat(credit.requiresOverride()).isFalse();
    }

    @Test
    void quoteReportsManualAndOverdueBlocksWithoutHidingCreditSummary() {
        var request = request(List.of(), new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(customer.isCreditBlocked()).thenReturn(true);
        when(customer.isBlockOnOverdue()).thenReturn(true);
        when(customers.overdueDebt(any(), any())).thenReturn(new BigDecimal("25.00"));

        var credit = service.quote(request, authentication).credit();

        assertThat(credit.manualBlocked()).isTrue();
        assertThat(credit.overdueBlocked()).isTrue();
        assertThat(credit.blockReason()).isEqualTo("CREDIT_BLOCKED");
        assertThat(credit.overdueDebt()).isEqualByComparingTo("25.00");
    }

    @Test
    void fullyPaidInitialSaleDoesNotRequireCreditOrApplyCreditBlocks() {
        var request = request(List.of(standardPayment(new BigDecimal("100.00"))),
                new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(customer.isCreditEnabled()).thenReturn(false);
        org.mockito.Mockito.lenient().when(customer.isCreditBlocked()).thenReturn(true);
        org.mockito.Mockito.lenient().when(customer.isBlockOnOverdue()).thenReturn(true);
        when(customers.overdueDebt(any(), any())).thenReturn(new BigDecimal("25.00"));
        when(customer.getCreditLimit()).thenReturn(new BigDecimal("10.00"));

        var credit = service.quote(request, authentication).credit();

        assertThat(credit.creditRequired()).isFalse();
        assertThat(credit.blocked()).isFalse();
        assertThat(credit.limitExceeded()).isFalse();
        assertThat(credit.blockReason()).isNull();
    }

    @Test
    void createLocksCustomerAndRejectsConcurrentCreditLimitOverrun() {
        var request = request(List.of(), new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(customer.getCreditLimit()).thenReturn(new BigDecimal("50.00"));
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.customer_credit_limit_exceeded");

        verify(customers).findLockedByIdAndCompanyId(eq(request.customerId()), any());
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
    }

    @Test
    void quoteReportsLimitOverrideWithoutAuthenticatingBeforeMutation() {
        var base = request(List.of(), new BigDecimal("100.00"));
        var overridden = new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(), base.customerId(),
                base.dueDate(), base.globalDiscount(), base.lines(), base.payments(),
                base.quotedTotal(), new CustomerPendingSaleController.CreditOverride("Autorizado"));
        stubQuote(overridden, new BigDecimal("100.00"));
        when(customer.getCreditLimit()).thenReturn(new BigDecimal("50.00"));

        assertThat(service.quote(overridden, authentication).credit().overrideUsed()).isTrue();
        verify(saleOperationSecurity, never()).authorize(
                any(SaleOperationCode.class), any(), any(), any());
    }

    @Test
    void rejectsDueDatesOutsideCustomerTerms() {
        var base = request(List.of(), new BigDecimal("100.00"));
        var outsideTerms = new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(), base.customerId(),
                base.date().plusDays(31), base.globalDiscount(), base.lines(), base.payments(),
                base.quotedTotal());
        stubQuote(outsideTerms, new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.quote(outsideTerms, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.pending_sale_due_date_exceeds_customer_terms");
    }

    @Test
    void changedQuoteIsRejectedBeforeChargingCard() {
        var request = request(List.of(), new BigDecimal("99.00"));
        stubQuote(request, new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.chargeCard(
                new CustomerPendingSaleController.CardChargeRequest(
                        request, new BigDecimal("30.00")), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cambiado");
        verify(terminalOperations, never()).charge(any(), any(), any(), any());
    }

    @Test
    void saleMutationAuthorizationFailureStopsCardBeforeExternalEffect() {
        var request = request(List.of(payment(new BigDecimal("30.00"))),
                new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        doThrow(new org.springframework.security.access.AccessDeniedException("forbidden"))
                .when(documentMutationAuthorization)
                .authorize(any(), any(), any(), eq(authentication),
                        eq("CUSTOMER_PENDING_CARD_CHARGE"), eq(request.checkoutId()));

        assertThatThrownBy(() -> service.chargeCard(
                new CustomerPendingSaleController.CardChargeRequest(
                        request, new BigDecimal("30.00")), authentication))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verifyNoInteractions(terminalOperations);
    }

    @Test
    void integratedCardRejectsANonCardMethodBeforeExternalEffect() {
        var request = request(List.of(payment(new BigDecimal("30.00"))),
                new BigDecimal("100.00"));
        var transferMethod = new PaymentMethod(companyId, "TRANSFERENCIA", true);
        var payment = request.payments().getFirst();
        var invalid = new CustomerPendingSaleController.CreateRequest(
                request.checkoutId(), request.warehouseId(), request.type(), request.date(),
                request.customerId(), request.dueDate(), request.globalDiscount(), request.lines(),
                List.of(new CustomerPendingSaleController.PaymentItem(
                        payment.kind(), transferMethod.getId(), payment.amount(),
                        payment.principal(), payment.delivered(), payment.change(),
                        payment.voucherCode(), payment.reference(), payment.requestId(),
                        payment.paymentTerminalOperationId())),
                request.quotedTotal());
        stubQuote(invalid, new BigDecimal("100.00"));
        when(paymentMethods.findByIdAndEmpresaId(
                transferMethod.getId(), companyId)).thenReturn(Optional.of(transferMethod));

        assertThatThrownBy(() -> service.chargeCard(
                new CustomerPendingSaleController.CardChargeRequest(
                        invalid, new BigDecimal("30.00")), authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("integrated_card_payment_method_required");

        verifyNoInteractions(terminalOperations);
        verify(configurations, never()).required(any());
    }

    @Test
    void integratedCardRejectsManualTerminalConfigurationBeforeExternalEffect() {
        var request = request(List.of(payment(new BigDecimal("30.00"))),
                new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        var configuration = configuration();
        when(configuration.mode()).thenReturn(PaymentCardMode.MANUAL);
        when(configurations.required(terminalId)).thenReturn(configuration);

        assertThatThrownBy(() -> service.chargeCard(
                new CustomerPendingSaleController.CardChargeRequest(
                        request, new BigDecimal("30.00")), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payment_terminal_configuration_not_integrated");

        verify(terminalOperations, never()).charge(any(), any(), any(), any());
    }

    @Test
    void saleMutationAuthorizationFailureStopsDocumentBeforeReservationAndMutation() {
        var request = request(List.of(), new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        doThrow(new org.springframework.security.access.AccessDeniedException("forbidden"))
                .when(documentMutationAuthorization)
                .authorize(any(), any(), any(), eq(authentication),
                        eq("CUSTOMER_PENDING_DOCUMENT"), eq(request.checkoutId()));

        assertThatThrownBy(() -> service.createDocument(request, authentication))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(reservations, never()).insert(any());
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
        verify(documents, never()).createPendingSaleDraft(any(), any(), any());
    }

    @Test
    void uncertainCardOperationNeverCreatesDocument() {
        var request = request(List.of(payment(new BigDecimal("30.00"))),
                new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        var configuration = configuration();
        when(configurations.required(terminalId)).thenReturn(configuration);
        when(terminalOperations.charge(eq(request.checkoutId()), any(),
                eq(new BigDecimal("30.00")), any()))
                .thenReturn(new PaymentTerminalResult(
                        PaymentTerminalOperationStatus.TIMEOUT, "TIMEOUT", null, null,
                        "Resultado incierto"));

        var result = service.chargeCard(
                new CustomerPendingSaleController.CardChargeRequest(
                        request, new BigDecimal("30.00")), authentication);

        assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
        verify(saleOperationSecurity).authorize(
                eq(SaleOperationCode.CREATE_PENDING_RECEIVABLE),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                eq(authentication));
        verify(audit).record(
                eq("CUSTOMER_PENDING_RECEIVABLE_CARD_AUTHORIZED"),
                eq(com.tpverp.backend.audit.AuditResult.EXITO),
                org.mockito.ArgumentMatchers.argThat(details ->
                        !details.toString().contains("authorizerPassword")));
    }

    @Test
    void createsPartialSalesInvoiceWithoutFakePendingPaymentAndLinksApprovedCard() {
        var request = request(List.of(payment(new BigDecimal("30.00"))),
                new BigDecimal("100.00"));
        var quoted = document(new BigDecimal("100.00"));
        var saved = document(new BigDecimal("100.00"));
        var payment = org.mockito.Mockito.mock(DocumentPayment.class);
        when(payment.getRequestId()).thenReturn(request.checkoutId());
        when(payment.getId()).thenReturn(UUID.randomUUID());
        when(saved.getPagos()).thenReturn(List.of(payment));
        when(documents.quotePendingSale(any(), eq(request.dueDate()), eq(authentication)))
                .thenReturn(quoted);
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());
        when(reservations.insert(any())).thenAnswer(call -> call.getArgument(0));
        when(checkouts.save(any())).thenAnswer(call -> call.getArgument(0));
        var operation = approvedOperation(request, new BigDecimal("30.00"));
        when(terminalOperations.find(request.checkoutId())).thenReturn(Optional.of(operation));
        when(terminalOperations.requireFinalizableApprovedCharge(request.checkoutId()))
                .thenReturn(operation);
        var current = configuration();
        when(configurations.required(terminalId)).thenReturn(current);
        when(operation.matchesConfigurationIdentity(current)).thenReturn(true);
        when(documents.createPendingSale(any(), eq(request.dueDate()), any(), eq(authentication)))
                .thenReturn(saved);
        var view = org.mockito.Mockito.mock(CustomerReceivableView.class);
        when(views.receivableView(saved, request.date())).thenReturn(view);

        assertThat(service.create(request, authentication)).isSameAs(view);
        verify(terminalOperations).linkDocument(
                request.checkoutId(), saved.getId(), payment.getId());
        verify(documents).createPendingSale(any(), eq(request.dueDate()),
                org.mockito.ArgumentMatchers.argThat(commands ->
                        commands.size() == 1
                                && commands.getFirst().requestId().equals(request.checkoutId())
                                && commands.getFirst().importe().compareTo(new BigDecimal("30.00")) == 0),
                eq(authentication));
        verify(saleOperationSecurity).authorize(
                eq(SaleOperationCode.CREATE_PENDING_RECEIVABLE),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                eq(authentication));
        verify(saleOperationSecurity, never()).authorize(
                eq(SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT),
                any(), any(), eq(authentication));
    }

    @Test
    void transferPaymentUsesItsIndependentAuthorizationAtTheDocumentMutationBoundary() {
        var base = request(
                List.of(standardPayment(new BigDecimal("100.00"))),
                new BigDecimal("100.00"));
        var transferMethod = new PaymentMethod(
                company.getId(), "TRANSFERENCIA", true);
        var payment = base.payments().getFirst();
        var request = new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(),
                base.customerId(), base.dueDate(), base.globalDiscount(), base.lines(),
                List.of(new CustomerPendingSaleController.PaymentItem(
                        payment.kind(), transferMethod.getId(), payment.amount(),
                        payment.principal(), payment.delivered(), payment.change(),
                        payment.voucherCode(), payment.reference(), payment.requestId(),
                        payment.paymentTerminalOperationId())),
                base.quotedTotal(), null,
                CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_AND_PAY,
                null, null, null,
                java.util.Map.of(
                        SaleOperationCode.CONFIRM_TRANSFER_PAYMENT,
                        new OperationAuthorizationRequest("ENCARGADO", "secret")));
        var saved = document(new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(paymentMethods.findByIdAndEmpresaId(
                transferMethod.getId(), company.getId()))
                .thenReturn(Optional.of(transferMethod));
        when(reservations.find(terminalId, request.checkoutId()))
                .thenReturn(Optional.empty());
        when(reservations.insert(any())).thenAnswer(call -> call.getArgument(0));
        when(checkouts.save(any())).thenAnswer(call -> call.getArgument(0));
        when(documents.createPendingSale(
                any(), eq(request.dueDate()), any(), eq(authentication)))
                .thenReturn(saved);

        assertThat(service.createDocument(request, authentication)).isSameAs(saved);

        verify(saleOperationSecurity).authorize(
                SaleOperationCode.CONFIRM_TRANSFER_PAYMENT,
                "ENCARGADO", "secret", authentication);
        verify(audit).record(
                eq(PosCashService.SALE_OPERATION_AUTHORIZED),
                eq(com.tpverp.backend.audit.AuditResult.EXITO),
                org.mockito.ArgumentMatchers.argThat(details ->
                        "CONFIRM_TRANSFER_PAYMENT".equals(details.get("operationCode"))
                                && !details.toString().contains("secret")));
    }

    @Test
    void manualCardKeepsItsKindAuthorizationAndPaymentModeAtTheDocumentMutationBoundary() {
        var base = request(
                List.of(standardPayment(new BigDecimal("100.00"))),
                new BigDecimal("100.00"));
        var cardMethod = new PaymentMethod(company.getId(), "TARJETA", true);
        var payment = base.payments().getFirst();
        var request = new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(),
                base.customerId(), base.dueDate(), base.globalDiscount(), base.lines(),
                List.of(new CustomerPendingSaleController.PaymentItem(
                        CustomerPendingSaleController.PaymentKind.MANUAL_CARD,
                        cardMethod.getId(), payment.amount(),
                        payment.principal(), payment.delivered(), payment.change(),
                        payment.voucherCode(), "CARD-REF-1", payment.requestId(), null)),
                base.quotedTotal(), null,
                CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_AND_PAY,
                null, null, null,
                java.util.Map.of(
                        SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                        new OperationAuthorizationRequest("ENCARGADO", "secret")));
        var saved = document(new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(paymentMethods.findByIdAndEmpresaId(
                cardMethod.getId(), company.getId()))
                .thenReturn(Optional.of(cardMethod));
        when(reservations.find(terminalId, request.checkoutId()))
                .thenReturn(Optional.empty());
        when(reservations.insert(any())).thenAnswer(call -> call.getArgument(0));
        when(checkouts.save(any())).thenAnswer(call -> call.getArgument(0));
        when(documents.createPendingSale(
                any(), eq(request.dueDate()), any(), eq(authentication)))
                .thenReturn(saved);

        assertThat(service.createDocument(request, authentication)).isSameAs(saved);

        verify(saleOperationSecurity).authorize(
                SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                "ENCARGADO", "secret", authentication);
        verify(audit).record(
                eq(PosCashService.SALE_OPERATION_AUTHORIZED),
                eq(com.tpverp.backend.audit.AuditResult.EXITO),
                org.mockito.ArgumentMatchers.argThat(details ->
                        "CONFIRM_MANUAL_CARD_PAYMENT".equals(
                                 details.get("operationCode"))
                                 && !details.toString().contains("secret")));
        verify(documents).createPendingSale(
                any(), eq(request.dueDate()),
                org.mockito.ArgumentMatchers.argThat(commands ->
                        commands.size() == 1
                                && commands.getFirst().cardMode() == PaymentCardMode.MANUAL),
                eq(authentication));
    }

    @Test
    void limitOverrideAndPendingDebtUseTheirIndependentConfiguredPolicies() {
        var base = withCompletionMode(
                request(List.of(), new BigDecimal("100.00")),
                CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_PENDING);
        var request = new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(),
                base.customerId(), base.dueDate(), base.globalDiscount(), base.lines(),
                base.payments(), base.quotedTotal(),
                new CustomerPendingSaleController.CreditOverride(
                        "Excepcion aprobada", "CREDIT_MANAGER", "creditSecret"),
                base.completionMode(), base.internalComment(), "ENCARGADO", "secret");
        var saved = document(new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(customer.getCreditLimit()).thenReturn(new BigDecimal("50.00"));
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());
        when(reservations.insert(any())).thenAnswer(call -> call.getArgument(0));
        when(checkouts.save(any())).thenAnswer(call -> call.getArgument(0));
        when(documents.createPendingSale(
                any(), eq(request.dueDate()), any(), eq(authentication))).thenReturn(saved);

        assertThat(service.createDocument(request, authentication)).isSameAs(saved);

        verify(saleOperationSecurity).authorize(
                eq(SaleOperationCode.CREATE_PENDING_RECEIVABLE),
                eq("ENCARGADO"), eq("secret"), eq(authentication));
        verify(saleOperationSecurity).authorize(
                eq(SaleOperationCode.CREDIT_OVERRIDE),
                eq("CREDIT_MANAGER"), eq("creditSecret"), eq(authentication));
        verify(audit).record(
                eq("CUSTOMER_PENDING_RECEIVABLE_AUTHORIZED"),
                eq(com.tpverp.backend.audit.AuditResult.EXITO),
                org.mockito.ArgumentMatchers.argThat(details ->
                        !details.containsKey("authorizerPassword")
                                && userId.toString().equals(details.get("operatorUserId"))
                                && userId.toString().equals(details.get("authorizerUserId"))));
        verify(audit).record(
                eq("CUSTOMER_CREDIT_LIMIT_OVERRIDDEN"),
                eq(com.tpverp.backend.audit.AuditResult.EXITO),
                org.mockito.ArgumentMatchers.argThat(details ->
                        "Excepcion aprobada".equals(details.get("reason"))
                                && !details.toString().contains("secret")
                                && !details.toString().contains("creditSecret")));
    }

    @Test
    void documentDraftUsesDraftPersistenceAndSkipsCustomerCreditConsumption() {
        var request = withCompletionMode(
                request(List.of(), new BigDecimal("100.00")),
                CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT);
        var quoted = document(new BigDecimal("100.00"));
        var saved = document(new BigDecimal("100.00"));
        when(documents.quotePendingSale(any(), eq(request.dueDate()), eq(authentication)))
                .thenReturn(quoted);
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());
        when(reservations.insert(any())).thenAnswer(call -> call.getArgument(0));
        when(documents.createPendingSaleDraft(
                any(), eq(request.dueDate()), eq(authentication))).thenReturn(saved);
        when(checkouts.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service.createDocument(request, authentication)).isSameAs(saved);

        verify(documents).createPendingSaleDraft(
                any(), eq(request.dueDate()), eq(authentication));
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
        verify(customers, never()).findLockedByIdAndCompanyId(any(), any());
        verify(saleOperationSecurity, never()).authorize(
                any(SaleOperationCode.class), any(), any(), any());
    }

    @Test
    void pendingDocumentRejectsAnyPaymentBeforeCreatingACommercialDocument() {
        var request = withCompletionMode(
                request(List.of(standardPayment(new BigDecimal("10.00"))),
                        new BigDecimal("100.00")),
                CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_PENDING);

        assertThatThrownBy(() -> service.createDocument(request, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sales_document_pending_cannot_have_payments");

        verify(documents, never()).quotePendingSale(any(), any(), any());
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
        verify(saleOperationSecurity, never()).authorize(
                any(SaleOperationCode.class), any(), any(), any());
    }

    @Test
    void confirmAndPayRequiresAllocationsForTheFullAuthoritativeTotal() {
        var request = withCompletionMode(
                request(List.of(standardPayment(new BigDecimal("99.00"))),
                        new BigDecimal("100.00")),
                CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_AND_PAY);
        stubQuote(request, new BigDecimal("100.00"));
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDocument(request, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sales_document_checkout_payment_total_mismatch");

        verify(documents, never()).createPendingSale(any(), any(), any(), any());
    }

    @Test
    void completedCheckoutReplaysWithoutCreatingAgain() {
        var request = request(List.of(), new BigDecimal("100.00"));
        var hash = CustomerPendingSaleRequestHasher.hash(
                request, new BigDecimal("100.00"));
        var checkout = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), terminalId, storeId, userId,
                hash, NOW);
        var existing = document(new BigDecimal("100.00"));
        checkout.complete(existing.getId(), NOW);
        when(reservations.find(terminalId, request.checkoutId()))
                .thenReturn(Optional.of(checkout));
        when(documents.find(existing.getId())).thenReturn(existing);
        var view = org.mockito.Mockito.mock(CustomerReceivableView.class);
        when(views.receivableView(existing, request.date())).thenReturn(view);

        assertThat(service.create(request, authentication)).isSameAs(view);
        verify(documents, never()).quotePendingSale(any(), any(), any());
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
    }

    @Test
    void checkoutReplayWithDifferentCanonicalHashIsConflict() {
        var request = request(List.of(), new BigDecimal("100.00"));
        var checkout = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), terminalId, storeId, userId,
                "0".repeat(64), NOW);
        when(reservations.find(terminalId, request.checkoutId()))
                .thenReturn(Optional.of(checkout));

        assertThatThrownBy(() -> service.create(request, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency_conflict");
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
    }

    @Test
    void reusedOrCrossScopedApprovedCardIsRejected() {
        var request = request(List.of(payment(new BigDecimal("30.00"))),
                new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());
        var operation = approvedOperation(request, new BigDecimal("30.00"));
        when(operation.getStoreId()).thenReturn(UUID.randomUUID());
        when(terminalOperations.find(request.checkoutId())).thenReturn(Optional.of(operation));
        when(terminalOperations.requireFinalizableApprovedCharge(request.checkoutId()))
                .thenReturn(operation);
        var current = configuration();
        when(configurations.required(terminalId)).thenReturn(current);

        assertThatThrownBy(() -> service.create(request, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope");
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
    }

    @Test
    void approvedCheckoutCardCannotBeOmittedOrReidentifiedAtCreate() {
        var omitted = request(List.of(), new BigDecimal("100.00"));
        stubQuote(omitted, new BigDecimal("100.00"));
        when(reservations.find(terminalId, omitted.checkoutId())).thenReturn(Optional.empty());
        var approved = approvedOperationForHash(
                omitted, new BigDecimal("30.00"), CustomerPendingSaleRequestHasher.hash(
                        withIntegratedPayment(omitted, omitted.checkoutId(), omitted.checkoutId(),
                                new BigDecimal("30.00")), new BigDecimal("100.00")));
        when(terminalOperations.find(omitted.checkoutId())).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.create(omitted, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved_card_payment_required");
        verify(documents, never()).createPendingSale(any(), any(), any(), any());

        var changed = withIntegratedPayment(
                omitted, UUID.randomUUID(), omitted.checkoutId(), new BigDecimal("30.00"));
        assertThatThrownBy(() -> service.create(changed, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved_card_payment_required");
        verify(reservations, org.mockito.Mockito.times(2)).insert(any());
        verify(reservations, never()).release(any(UUID.class), any(UUID.class));
    }

    @Test
    void unresolvedDurableCardChargeBlocksCreateWhenPaymentIsOmitted() {
        for (var status : List.of(
                PaymentTerminalOperationStatus.PENDING,
                PaymentTerminalOperationStatus.SENT,
                PaymentTerminalOperationStatus.TIMEOUT,
                PaymentTerminalOperationStatus.ERROR,
                PaymentTerminalOperationStatus.REVIEW_REQUIRED)) {
            var request = request(List.of(), new BigDecimal("100.00"));
            stubQuote(request, new BigDecimal("100.00"));
            when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());
            var operation = org.mockito.Mockito.mock(PaymentTerminalOperation.class);
            when(operation.getStatus()).thenReturn(status);
            when(operation.getOperationType()).thenReturn(
                    com.tpverp.backend.terminal.PaymentTerminalOperationType.CHARGE);
            when(terminalOperations.find(request.checkoutId())).thenReturn(Optional.of(operation));

            assertThatThrownBy(() -> service.create(request, authentication))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("payment_operation_resolution_required");
        }
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
        verify(reservations, org.mockito.Mockito.times(5)).insert(any());
        verify(reservations, never()).release(any(UUID.class), any(UUID.class));
    }

    @Test
    void reservationRaceWinnerIsReturnedBeforeLoserConsumesCardOperation() {
        var request = request(List.of(payment(new BigDecimal("30.00"))),
                new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());
        when(reservations.insert(any())).thenThrow(new DataIntegrityViolationException("race"));
        var winnerDocument = document(new BigDecimal("100.00"));
        var winner = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), terminalId, storeId, userId,
                CustomerPendingSaleRequestHasher.hash(request, request.quotedTotal()), NOW);
        winner.complete(winnerDocument.getId(), NOW);
        when(reservations.findAfterConflict(terminalId, request.checkoutId())).thenReturn(winner);
        when(documents.find(winnerDocument.getId())).thenReturn(winnerDocument);
        var view = org.mockito.Mockito.mock(CustomerReceivableView.class);
        when(views.receivableView(winnerDocument, request.date())).thenReturn(view);

        assertThat(service.create(request, authentication)).isSameAs(view);
        verify(terminalOperations, never()).requireFinalizableApprovedCharge(any());
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
    }

    @Test
    void canonicalHashCannotCollideByRedistributingDelimitersAcrossStrings() {
        var original = request(List.of(new CustomerPendingSaleController.PaymentItem(
                CustomerPendingSaleController.PaymentKind.STANDARD,
                UUID.randomUUID(), BigDecimal.TEN, true, null, null,
                "A:B", "C|D", UUID.randomUUID(), null)), new BigDecimal("100.00"));
        var payment = original.payments().getFirst();
        var redistributed = new CustomerPendingSaleController.CreateRequest(
                original.checkoutId(), original.warehouseId(), original.type(), original.date(),
                original.customerId(), original.dueDate(), original.globalDiscount(), original.lines(),
                List.of(new CustomerPendingSaleController.PaymentItem(
                        payment.kind(), payment.methodId(), payment.amount(), payment.principal(),
                        payment.delivered(), payment.change(), "A", "B:C|D",
                        payment.requestId(), payment.paymentTerminalOperationId())),
                original.quotedTotal());

        assertThat(CustomerPendingSaleRequestHasher.hash(original, original.quotedTotal()))
                .isNotEqualTo(CustomerPendingSaleRequestHasher.hash(
                        redistributed, redistributed.quotedTotal()));
    }

    @Test
    void canonicalHashDistinguishesDocumentCompletionModes() {
        var base = request(List.of(), new BigDecimal("100.00"));
        var draft = withCompletionMode(
                base, CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT);
        var pending = withCompletionMode(
                base, CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_PENDING);

        assertThat(CustomerPendingSaleRequestHasher.hash(draft, draft.quotedTotal()))
                .isNotEqualTo(CustomerPendingSaleRequestHasher.hash(
                        pending, pending.quotedTotal()));
    }

    @Test
    void canonicalHashIgnoresEphemeralPaymentAuthorizations() {
        var base = request(List.of(standardPayment(BigDecimal.TEN)),
                new BigDecimal("100.00"));
        var first = withOperationAuthorization(
                base,
                SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                new OperationAuthorizationRequest("ENCARGADO-1", "secret-1"));
        var second = withOperationAuthorization(
                base,
                SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                new OperationAuthorizationRequest("ENCARGADO-2", "secret-2"));

        assertThat(CustomerPendingSaleRequestHasher.hash(first, first.quotedTotal()))
                .isEqualTo(CustomerPendingSaleRequestHasher.hash(
                        second, second.quotedTotal()));
        assertThat(first.toString()).doesNotContain("secret-1");
    }

    @Test
    void canonicalHashDistinguishesNullFromLiteralNullPaymentStrings() {
        var absent = request(List.of(new CustomerPendingSaleController.PaymentItem(
                CustomerPendingSaleController.PaymentKind.STANDARD,
                UUID.randomUUID(), BigDecimal.TEN, true, null, null,
                null, null, UUID.randomUUID(), null)), new BigDecimal("100.00"));
        var payment = absent.payments().getFirst();
        var literal = new CustomerPendingSaleController.CreateRequest(
                absent.checkoutId(), absent.warehouseId(), absent.type(), absent.date(),
                absent.customerId(), absent.dueDate(), absent.globalDiscount(), absent.lines(),
                List.of(new CustomerPendingSaleController.PaymentItem(
                        payment.kind(), payment.methodId(), payment.amount(), payment.principal(),
                        payment.delivered(), payment.change(), "null", "null",
                        payment.requestId(), payment.paymentTerminalOperationId())),
                absent.quotedTotal());

        assertThat(CustomerPendingSaleRequestHasher.hash(absent, absent.quotedTotal()))
                .isNotEqualTo(CustomerPendingSaleRequestHasher.hash(
                        literal, literal.quotedTotal()));
    }

    @Test
    void finalizationRejectsChangedTerminalConfigurationIdentity() {
        var request = request(List.of(payment(new BigDecimal("30.00"))),
                new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());
        var operation = approvedOperation(request, new BigDecimal("30.00"));
        when(terminalOperations.find(request.checkoutId())).thenReturn(Optional.of(operation));
        when(terminalOperations.requireFinalizableApprovedCharge(request.checkoutId()))
                .thenReturn(operation);
        var current = configuration();
        when(configurations.required(terminalId)).thenReturn(current);
        when(operation.matchesConfigurationIdentity(current)).thenReturn(false);

        assertThatThrownBy(() -> service.create(request, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration");
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
        verify(reservations).insert(any());
        verify(reservations, never()).release(any(UUID.class), any(UUID.class));
    }

    @Test
    void uniqueReservationRaceRequeriesWinnerAsReplayConflictOrInProgress() {
        var request = request(List.of(), new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());
        when(reservations.insert(any())).thenThrow(new DataIntegrityViolationException("race"));
        var hash = CustomerPendingSaleRequestHasher.hash(request, new BigDecimal("100.00"));
        var winner = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), terminalId, storeId, userId, hash, NOW);
        when(reservations.findAfterConflict(terminalId, request.checkoutId()))
                .thenReturn(winner);
        when(reservations.claim(eq(terminalId), eq(request.checkoutId()), eq(storeId),
                eq(userId), eq(hash), any(UUID.class), any(Instant.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("pending_sale_checkout_in_progress"));

        assertThatThrownBy(() -> service.create(request, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in_progress");
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
    }

    @Test
    void documentFailureKeepsLeasedReservationForFencedRetryAfterRollback() {
        var request = request(List.of(), new BigDecimal("100.00"));
        stubQuote(request, new BigDecimal("100.00"));
        when(reservations.find(terminalId, request.checkoutId())).thenReturn(Optional.empty());
        when(reservations.insert(any())).thenAnswer(call -> call.getArgument(0));
        when(documents.createPendingSale(any(), any(), any(), eq(authentication)))
                .thenThrow(new IllegalStateException("document rollback"));

        assertThatThrownBy(() -> service.create(request, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document rollback");

        verify(reservations, never()).release(any(UUID.class), any(UUID.class));
        verify(checkouts, never()).save(any());
        verify(terminalOperations, never()).linkDocument(any(), any(), any());
    }

    @Test
    void replayChangesToPaymentMethodAmountReferenceOrChangeConflict() {
        var original = request(List.of(new CustomerPendingSaleController.PaymentItem(
                CustomerPendingSaleController.PaymentKind.STANDARD,
                UUID.randomUUID(), new BigDecimal("10.00"), true,
                new BigDecimal("12.00"), new BigDecimal("2.00"), null, "REF",
                UUID.randomUUID(), null)), new BigDecimal("100.00"));
        var checkout = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), original.checkoutId(), terminalId, storeId, userId,
                CustomerPendingSaleRequestHasher.hash(original, new BigDecimal("100.00")), NOW);
        when(reservations.find(terminalId, original.checkoutId())).thenReturn(Optional.of(checkout));

        for (var changed : List.of(
                replaceStandardPayment(original, UUID.randomUUID(), new BigDecimal("10.00"), "REF", new BigDecimal("2.00")),
                replaceStandardPayment(original, original.payments().getFirst().methodId(), new BigDecimal("9.00"), "REF", new BigDecimal("2.00")),
                replaceStandardPayment(original, original.payments().getFirst().methodId(), new BigDecimal("10.00"), "OTHER", new BigDecimal("2.00")),
                replaceStandardPayment(original, original.payments().getFirst().methodId(), new BigDecimal("10.00"), "REF", new BigDecimal("1.00")))) {
            assertThatThrownBy(() -> service.create(changed, authentication))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("idempotency_conflict");
        }
    }

    private PaymentTerminalOperation approvedOperation(
            CustomerPendingSaleController.CreateRequest request, BigDecimal amount) {
        return approvedOperationForHash(
                request, amount, CustomerPendingSaleRequestHasher.hash(
                        request, request.quotedTotal()));
    }

    private PaymentTerminalOperation approvedOperationForHash(
            CustomerPendingSaleController.CreateRequest request, BigDecimal amount, String hash) {
        var operation = org.mockito.Mockito.mock(PaymentTerminalOperation.class);
        org.mockito.Mockito.lenient().when(operation.getId()).thenReturn(request.checkoutId());
        org.mockito.Mockito.lenient().when(operation.getTerminalId()).thenReturn(terminalId);
        org.mockito.Mockito.lenient().when(operation.getStoreId()).thenReturn(storeId);
        org.mockito.Mockito.lenient().when(operation.getAmount()).thenReturn(amount);
        org.mockito.Mockito.lenient().when(operation.getRequestHash()).thenReturn(hash);
        org.mockito.Mockito.lenient().when(operation.getProvider()).thenReturn(PaymentTerminalProvider.REDSYS_TPV_PC);
        org.mockito.Mockito.lenient().when(operation.getAuthorizationCode()).thenReturn("AUTH");
        org.mockito.Mockito.lenient().when(operation.getStatus())
                .thenReturn(PaymentTerminalOperationStatus.APPROVED);
        org.mockito.Mockito.lenient().when(operation.getOperationType()).thenReturn(
                com.tpverp.backend.terminal.PaymentTerminalOperationType.CHARGE);
        return operation;
    }

    private CardTerminalConfiguration configuration() {
        var configuration = org.mockito.Mockito.mock(CardTerminalConfiguration.class);
        org.mockito.Mockito.lenient().when(configuration.terminalId()).thenReturn(terminalId);
        org.mockito.Mockito.lenient().when(configuration.storeId()).thenReturn(storeId);
        org.mockito.Mockito.lenient().when(configuration.enabled()).thenReturn(true);
        org.mockito.Mockito.lenient().when(configuration.mode()).thenReturn(
                PaymentCardMode.INTEGRATED);
        org.mockito.Mockito.lenient().when(configuration.provider()).thenReturn(
                PaymentTerminalProvider.REDSYS_TPV_PC);
        return configuration;
    }

    private CustomerPendingSaleController.CreateRequest request(
            List<CustomerPendingSaleController.PaymentItem> payments,
            BigDecimal quotedTotal) {
        var checkoutId = UUID.randomUUID();
        var identifiedPayments = payments.stream().map(payment ->
                payment.kind() == CustomerPendingSaleController.PaymentKind.INTEGRATED_CARD
                        && payment.requestId() == null
                        ? new CustomerPendingSaleController.PaymentItem(
                                payment.kind(), payment.methodId(), payment.amount(), payment.principal(),
                                payment.delivered(), payment.change(), payment.voucherCode(),
                                payment.reference(), checkoutId, checkoutId)
                        : payment).toList();
        return new CustomerPendingSaleController.CreateRequest(
                checkoutId, UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 6, 8), UUID.randomUUID(), LocalDate.of(2026, 7, 8),
                BigDecimal.ZERO,
                List.of(new DocumentRequest.LineRequest(
                        UUID.randomUUID(), BigDecimal.ONE, "CLIENT", "Client", null,
                        BigDecimal.ONE, BigDecimal.ZERO, true, "IVA",
                        new BigDecimal("21"), null, null, null, null)),
                identifiedPayments, quotedTotal);
    }

    private CustomerPendingSaleController.PaymentItem payment(BigDecimal amount) {
        return new CustomerPendingSaleController.PaymentItem(
                CustomerPendingSaleController.PaymentKind.INTEGRATED_CARD,
                cardMethodId, amount, true, null, null, null, null, null, null);
    }

    private CustomerPendingSaleController.PaymentItem standardPayment(BigDecimal amount) {
        return new CustomerPendingSaleController.PaymentItem(
                CustomerPendingSaleController.PaymentKind.STANDARD,
                UUID.randomUUID(), amount, true, null, null, null, null, UUID.randomUUID(), null);
    }

    private CustomerPendingSaleController.CreateRequest withOperationAuthorization(
            CustomerPendingSaleController.CreateRequest base,
            SaleOperationCode code,
            OperationAuthorizationRequest authorization) {
        return new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(),
                base.customerId(), base.dueDate(), base.globalDiscount(),
                base.lines(), base.payments(), base.quotedTotal(),
                base.creditOverride(), base.completionMode(), base.internalComment(),
                base.authorizerUsername(), base.authorizerPassword(),
                java.util.Map.of(code, authorization));
    }

    private CustomerPendingSaleController.CreateRequest withIntegratedPayment(
            CustomerPendingSaleController.CreateRequest base,
            UUID requestId,
            UUID operationId,
            BigDecimal amount) {
        return new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(), base.customerId(),
                base.dueDate(), base.globalDiscount(), base.lines(),
                List.of(new CustomerPendingSaleController.PaymentItem(
                        CustomerPendingSaleController.PaymentKind.INTEGRATED_CARD,
                        cardMethodId, amount, true, null, null, null, null,
                requestId, operationId)), base.quotedTotal());
    }

    private CustomerPendingSaleController.CreateRequest withCompletionMode(
            CustomerPendingSaleController.CreateRequest base,
            CustomerPendingSaleController.SalesDocumentCompletionMode completionMode) {
        return new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(),
                base.customerId(), base.dueDate(), base.globalDiscount(), base.lines(),
                base.payments(), base.quotedTotal(), base.creditOverride(), completionMode);
    }

    private CustomerPendingSaleController.CreateRequest replaceStandardPayment(
            CustomerPendingSaleController.CreateRequest base,
            UUID methodId,
            BigDecimal amount,
            String reference,
            BigDecimal change) {
        var old = base.payments().getFirst();
        return new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(), base.customerId(),
                base.dueDate(), base.globalDiscount(), base.lines(),
                List.of(new CustomerPendingSaleController.PaymentItem(
                        old.kind(), methodId, amount, old.principal(), old.delivered(), change,
                        old.voucherCode(), reference, old.requestId(), old.paymentTerminalOperationId())),
                base.quotedTotal());
    }

    private CommercialDocument document(BigDecimal total) {
        var document = org.mockito.Mockito.mock(CommercialDocument.class);
        org.mockito.Mockito.lenient().when(document.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(document.getTotal()).thenReturn(total);
        return document;
    }

    private void stubQuote(
            CustomerPendingSaleController.CreateRequest request, BigDecimal total) {
        var quote = document(total);
        when(documents.quotePendingSale(any(), eq(request.dueDate()), eq(authentication)))
                .thenReturn(quote);
    }
}
