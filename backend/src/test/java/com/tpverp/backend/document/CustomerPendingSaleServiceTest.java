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
                documents, checkouts, reservations, terminalOperations, configurations,
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
        org.mockito.Mockito.lenient().when(configuration.storeId()).thenReturn(storeId);
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
                UUID.randomUUID(), amount, true, null, null, null, null, null, null);
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
                        UUID.randomUUID(), amount, true, null, null, null, null,
                        requestId, operationId)), base.quotedTotal());
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
