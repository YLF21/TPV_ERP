package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CardTerminalConfiguration;
import com.tpverp.backend.terminal.CardTerminalConfigurationReader;
import com.tpverp.backend.terminal.CurrentTerminal;
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
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class CustomerPendingSaleServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    @Mock DocumentService documents;
    @Mock CustomerPendingSaleCheckoutRepository checkouts;
    @Mock PaymentTerminalOperationService terminalOperations;
    @Mock CardTerminalConfigurationReader configurations;
    @Mock CurrentTerminal currentTerminal;
    @Mock CurrentOrganization organization;
    @Mock DocumentViewAssembler views;
    @Mock Authentication authentication;
    @Mock Store store;
    @Mock Company company;
    @Mock UserAccount user;

    private CustomerPendingSaleService service;
    private UUID terminalId;
    private UUID storeId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        terminalId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(currentTerminal.terminalId(authentication)).thenReturn(terminalId);
        org.mockito.Mockito.lenient().when(organization.currentStore()).thenReturn(store);
        org.mockito.Mockito.lenient().when(store.getId()).thenReturn(storeId);
        org.mockito.Mockito.lenient().when(organization.currentCompany()).thenReturn(company);
        org.mockito.Mockito.lenient().when(organization.currentUser(authentication)).thenReturn(user);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(userId);
        service = new CustomerPendingSaleService(
                documents, checkouts, terminalOperations, configurations,
                currentTerminal, organization, views,
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
    void uncertainCardOperationNeverCreatesDocument() {
        var request = request(List.of(), new BigDecimal("100.00"));
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
        when(checkouts.findByTerminalIdAndCheckoutId(terminalId, request.checkoutId()))
                .thenReturn(Optional.empty());
        when(checkouts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(checkouts.save(any())).thenAnswer(call -> call.getArgument(0));
        var operation = approvedOperation(request, new BigDecimal("30.00"));
        when(terminalOperations.requireFinalizableApprovedCharge(request.checkoutId()))
                .thenReturn(operation);
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
    }

    @Test
    void completedCheckoutReplaysWithoutCreatingAgain() {
        var request = request(List.of(), new BigDecimal("100.00"));
        var hash = CustomerPendingSaleRequestHasher.hash(
                request, BigDecimal.ZERO, new BigDecimal("100.00"));
        var checkout = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), terminalId, storeId, userId,
                hash, NOW);
        var existing = document(new BigDecimal("100.00"));
        checkout.complete(existing.getId());
        when(checkouts.findByTerminalIdAndCheckoutId(terminalId, request.checkoutId()))
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
        when(checkouts.findByTerminalIdAndCheckoutId(terminalId, request.checkoutId()))
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
        when(checkouts.findByTerminalIdAndCheckoutId(terminalId, request.checkoutId()))
                .thenReturn(Optional.empty());
        when(checkouts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var operation = approvedOperation(request, new BigDecimal("30.00"));
        when(operation.getStoreId()).thenReturn(UUID.randomUUID());
        when(terminalOperations.requireFinalizableApprovedCharge(request.checkoutId()))
                .thenReturn(operation);

        assertThatThrownBy(() -> service.create(request, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope");
        verify(documents, never()).createPendingSale(any(), any(), any(), any());
    }

    private PaymentTerminalOperation approvedOperation(
            CustomerPendingSaleController.CreateRequest request, BigDecimal amount) {
        var operation = org.mockito.Mockito.mock(PaymentTerminalOperation.class);
        org.mockito.Mockito.lenient().when(operation.getId()).thenReturn(request.checkoutId());
        org.mockito.Mockito.lenient().when(operation.getTerminalId()).thenReturn(terminalId);
        org.mockito.Mockito.lenient().when(operation.getStoreId()).thenReturn(storeId);
        org.mockito.Mockito.lenient().when(operation.getAmount()).thenReturn(amount);
        org.mockito.Mockito.lenient().when(operation.getRequestHash()).thenReturn(CustomerPendingSaleRequestHasher.hash(
                request, amount, request.quotedTotal()));
        org.mockito.Mockito.lenient().when(operation.getProvider()).thenReturn(PaymentTerminalProvider.REDSYS_TPV_PC);
        org.mockito.Mockito.lenient().when(operation.getAuthorizationCode()).thenReturn("AUTH");
        return operation;
    }

    private CardTerminalConfiguration configuration() {
        var configuration = org.mockito.Mockito.mock(CardTerminalConfiguration.class);
        when(configuration.storeId()).thenReturn(storeId);
        return configuration;
    }

    private CustomerPendingSaleController.CreateRequest request(
            List<CustomerPendingSaleController.PaymentItem> payments,
            BigDecimal quotedTotal) {
        var checkoutId = UUID.randomUUID();
        var identifiedPayments = payments.stream().map(payment ->
                payment.requestId() == null
                        ? new CustomerPendingSaleController.PaymentItem(
                                payment.methodId(), payment.amount(), payment.principal(),
                                payment.delivered(), payment.change(), payment.voucherCode(),
                                payment.reference(), checkoutId)
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
                UUID.randomUUID(), amount, true, null, null, null, null);
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
