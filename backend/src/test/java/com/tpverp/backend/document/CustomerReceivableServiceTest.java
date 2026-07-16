package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class CustomerReceivableServiceTest {

    private static final UUID PAYMENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final CommercialDocumentRepository documents = mock(CommercialDocumentRepository.class);
    private final DocumentPaymentRepository payments = mock(DocumentPaymentRepository.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final PaymentTerminalOperationService terminalOperations = mock(PaymentTerminalOperationService.class);
    private final CardTerminalConfigurationReader configurations = mock(CardTerminalConfigurationReader.class);
    private final CurrentTerminal currentTerminal = mock(CurrentTerminal.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final DocumentViewAssembler views = mock(DocumentViewAssembler.class);
    private final Authentication authentication = mock(Authentication.class);
    private final Store store = store();
    private CustomerReceivableService service;

    @BeforeEach
    void setUp() {
        when(organization.currentStore()).thenReturn(store);
        service = new CustomerReceivableService(
                documents, payments, documentService, terminalOperations, configurations,
                currentTerminal, organization, views,
                Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void filtersOnlySalesReceivablesInCurrentStore() {
        var customerId = UUID.randomUUID();
        var overdue = receivable(customerId, LocalDate.of(2026, 7, 1), "100.00");
        var view = CustomerReceivableView.from(overdue, "CLIENTE", LocalDate.of(2026, 7, 16));
        when(documents.findCustomerReceivables(store.getId())).thenReturn(List.of(overdue));
        when(views.receivableView(overdue, LocalDate.of(2026, 7, 16))).thenReturn(view);

        var result = service.list(new CustomerReceivableFilter(
                customerId, null, null, true, null, null, null), authentication);

        assertThat(result).allMatch(value -> value.pendingTotal().signum() > 0 && value.overdue());
        verify(documents).findCustomerReceivables(store.getId());
    }

    @Test
    void replayedPaymentReturnsSameStateWithoutSecondPayment() {
        var document = receivable(UUID.randomUUID(), LocalDate.of(2026, 8, 1), "100.00");
        var transfer = new PaymentMethod(store.getEmpresa().getId(), "TRANSFERENCIA", false, true, false);
        when(documents.findLockedReceivable(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(payments.findByRequestId(PAYMENT_ID)).thenAnswer(invocation -> document.getPagos().stream()
                .filter(payment -> PAYMENT_ID.equals(payment.getRequestId())).findFirst());
        when(documentService.collectReceivable(any(), any(), any())).thenAnswer(invocation -> {
            var command = ((List<PaymentCommand>) invocation.getArgument(1)).getFirst();
            document.addPayment(new DocumentPayment(
                    document, transfer, 1, command.importe(), true, null, null, null,
                    command.reference(), Instant.parse("2026-07-16T10:00:00Z"), null,
                    null, null, null, null, command.requestId()));
            document.updatePaymentStatus();
            return document;
        });
        var expected = CustomerReceivableView.from(document, "CLIENTE", LocalDate.of(2026, 7, 16));
        when(views.receivableView(any(), any())).thenAnswer(invocation ->
                CustomerReceivableView.from(invocation.getArgument(0), "CLIENTE", invocation.getArgument(1)));
        var request = transfer(transfer.getId(), PAYMENT_ID, "20.00", "TR-1");

        var first = service.pay(document.getId(), request, authentication);
        var replay = service.pay(document.getId(), request, authentication);

        assertThat(first.pendingTotal()).isEqualByComparingTo("80.00");
        assertThat(replay).isEqualTo(first);
        assertThat(document.getPagos()).hasSize(1);
    }

    @Test
    void rejectsAmountAboveCurrentLockedPendingBalance() {
        var document = receivable(UUID.randomUUID(), LocalDate.of(2026, 8, 1), "100.00");
        when(documents.findLockedReceivable(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(payments.findByRequestId(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(
                document.getId(), transfer(UUID.randomUUID(), PAYMENT_ID, "100.01", "TR-1"), authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending");
    }

    @Test
    void integratedCardUsesApprovedConfiguredOperationAndLinksExactPayment() {
        var document = receivable(UUID.randomUUID(), LocalDate.of(2026, 8, 1), "100.00");
        var terminalId = UUID.randomUUID();
        var method = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", false);
        var configuration = new CardTerminalConfiguration(
                terminalId, store.getId(), PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.PAYTEF, true, true, "PAYTEF", "config:1", 1,
                "c".repeat(64), Map.of());
        var operation = mock(PaymentTerminalOperation.class);
        when(documents.findLockedReceivable(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(payments.findByRequestId(PAYMENT_ID)).thenReturn(Optional.empty());
        when(currentTerminal.terminalId(authentication)).thenReturn(terminalId);
        when(configurations.required(terminalId)).thenReturn(configuration);
        when(terminalOperations.requireFinalizableApprovedCharge(PAYMENT_ID)).thenReturn(operation);
        when(operation.getTerminalId()).thenReturn(terminalId);
        when(operation.getStoreId()).thenReturn(store.getId());
        when(operation.matchesConfigurationIdentity(configuration)).thenReturn(true);
        when(operation.getRequestHash()).thenReturn(cardHash(
                document.getId(), document.getPendingTotal(), new BigDecimal("20.00"), PAYMENT_ID));
        when(operation.getAmount()).thenReturn(new BigDecimal("20.00"));
        when(operation.getProvider()).thenReturn(PaymentTerminalProvider.PAYTEF);
        when(operation.getExternalReference()).thenReturn("PAYTEF-1");
        when(operation.getAuthorizationCode()).thenReturn("AUTH-1");
        when(documentService.collectReceivable(any(), any(), any())).thenAnswer(invocation -> {
            var command = ((List<PaymentCommand>) invocation.getArgument(1)).getFirst();
            document.addPayment(new DocumentPayment(
                    document, method, 1, command.importe(), true, null, null, null,
                    command.reference(), Instant.parse("2026-07-16T10:00:00Z"),
                    command.cardMode(), command.paymentTerminalProvider(),
                    command.paymentTerminalStatus(), command.cardAuthorizationCode(),
                    command.paymentTerminalId(), command.requestId()));
            document.updatePaymentStatus();
            return document;
        });
        when(views.receivableView(any(), any())).thenAnswer(invocation ->
                CustomerReceivableView.from(invocation.getArgument(0), "CLIENTE", invocation.getArgument(1)));
        var request = new PaymentRequest(List.of(new PaymentRequest.Item(
                method.getId(), new BigDecimal("20.00"), true, null, null, null,
                null, null, null, null, null, null, PAYMENT_ID, PAYMENT_ID)));

        var result = service.pay(document.getId(), request, authentication);

        assertThat(result.pendingTotal()).isEqualByComparingTo("80.00");
        var persisted = document.getPagos().getFirst();
        assertThat(persisted.getPaymentTerminalStatus())
                .isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(persisted.getReferencia()).isEqualTo("PAYTEF-1");
        verify(terminalOperations).linkDocument(PAYMENT_ID, document.getId(), persisted.getId());
    }

    @Test
    void cardChargeIsBoundToDocumentCurrentPendingAmountAndPaymentId() {
        var document = receivable(UUID.randomUUID(), LocalDate.of(2026, 8, 1), "100.00");
        var terminalId = UUID.randomUUID();
        var configuration = new CardTerminalConfiguration(
                terminalId, store.getId(), PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.PAYTEF, true, true, "PAYTEF", "config:1", 1,
                "c".repeat(64), Map.of());
        var expected = new PaymentTerminalResult(
                PaymentTerminalOperationStatus.APPROVED, "APPROVED", "REF", "AUTH", "OK");
        var hash = cardHash(document.getId(), document.getPendingTotal(),
                new BigDecimal("20.00"), PAYMENT_ID);
        when(documents.findLockedReceivable(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(currentTerminal.terminalId(authentication)).thenReturn(terminalId);
        when(configurations.required(terminalId)).thenReturn(configuration);
        when(terminalOperations.charge(PAYMENT_ID, hash, new BigDecimal("20.00"), configuration))
                .thenReturn(expected);

        var result = service.chargeCard(
                document.getId(), new CustomerReceivableController.CardChargeRequest(
                        PAYMENT_ID, new BigDecimal("20.00")), authentication);

        assertThat(result).isEqualTo(expected);
        verify(terminalOperations).charge(
                PAYMENT_ID, hash, new BigDecimal("20.00"), configuration);
    }

    @Test
    void replayWithDifferentAmountIsAnIdempotencyConflict() {
        var document = receivable(UUID.randomUUID(), LocalDate.of(2026, 8, 1), "100.00");
        var transfer = new PaymentMethod(store.getEmpresa().getId(), "TRANSFERENCIA", false, true, false);
        document.addPayment(new DocumentPayment(
                document, transfer, 1, new BigDecimal("20.00"), true, null, null,
                null, "TR-1", Instant.now(), null, null, null, null, null, PAYMENT_ID));
        document.updatePaymentStatus();
        when(documents.findLockedReceivable(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(payments.findByRequestId(PAYMENT_ID))
                .thenReturn(Optional.of(document.getPagos().getFirst()));

        assertThatThrownBy(() -> service.pay(document.getId(),
                transfer(transfer.getId(), PAYMENT_ID, "21.00", "TR-1"), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payment_idempotency_conflict");
    }

    private static PaymentRequest transfer(
            UUID methodId, UUID paymentId, String amount, String reference) {
        return new PaymentRequest(List.of(new PaymentRequest.Item(
                methodId, new BigDecimal(amount), true, null, null, null,
                reference, null, null, null, null, null, paymentId, null)));
    }

    private static String cardHash(
            UUID documentId, BigDecimal pending, BigDecimal amount, UUID paymentId) {
        var canonical = documentId + "|" + Money.euros(pending).toPlainString() + "|"
                + Money.euros(amount).toPlainString() + "|" + paymentId;
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(canonical.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private CommercialDocument receivable(UUID customerId, LocalDate dueDate, String total) {
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 7, 1), UUID.randomUUID(), BigDecimal.ZERO);
        document.setParties(customerId, null, null);
        document.setDueDate(dueDate);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1, "P-1", "Producto", "VENTA",
                new BigDecimal(total), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.confirm("FV-001-26-000001", UUID.randomUUID(), Instant.now(), false);
        return document;
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES");
        return new Store(new Company("B00000000", "Company", address), "001", "Store",
                address, UUID.randomUUID().toString(), "Europe/Madrid", "EUR", "es-ES");
    }
}
