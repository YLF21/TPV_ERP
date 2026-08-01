package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tpverp.backend.cash.CashCloseRequest;
import com.tpverp.backend.cash.CashController;
import com.tpverp.backend.cash.CashDrawerController;
import com.tpverp.backend.cash.CashEntryRequest;
import com.tpverp.backend.cash.CashWithdrawalRequest;
import com.tpverp.backend.catalog.ProductEditAuthorizationController;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.CustomerPendingSaleController;
import com.tpverp.backend.document.DiscountAuthorizationController;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.document.DocumentRequest;
import com.tpverp.backend.document.ParkedSaleController;
import com.tpverp.backend.document.PaymentRequest;
import com.tpverp.backend.document.SaleOperationAuthorizationsRequest;
import com.tpverp.backend.document.SalePaymentSessionController;
import com.tpverp.backend.document.TicketCancellationService;
import com.tpverp.backend.document.TicketController;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.terminal.PaymentTerminalOperationController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class OperationalCredentialInputRedactionTest {

    private static final String SECRET = "sentinel-password-never-log";
    private static final String AUTHORIZER = "ENCARGADO";
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("credentialInputs")
    void operationalCredentialInputsNeverExposePasswordsInLogsOrSerialization(Object input)
            throws Exception {
        assertThat(input.toString())
                .doesNotContain(SECRET)
                .contains("<redacted>");
        assertThat(JSON.writeValueAsString(input)).doesNotContain(SECRET);
    }

    private static Stream<Object> credentialInputs() {
        var requestId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var operationAuthorizations = Map.of(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                new OperationAuthorizationRequest(AUTHORIZER, SECRET));
        return Stream.of(
                new OperationAuthorizationRequest(AUTHORIZER, SECRET),
                new SaleOperationAuthorizationsRequest(operationAuthorizations),
                new PaymentRequest(
                        List.of(new PaymentRequest.Item(
                                UUID.randomUUID(), BigDecimal.ONE, true,
                                null, null, null)),
                        operationAuthorizations),
                documentRequest(operationAuthorizations),
                new CashCloseRequest(
                        BigDecimal.TEN, List.of(), BigDecimal.ONE, "Cierre", List.of(),
                        requestId, AUTHORIZER, SECRET),
                new CashEntryRequest(
                        BigDecimal.TEN, "Entrada", AUTHORIZER, SECRET, List.of()),
                new CashWithdrawalRequest(
                        BigDecimal.TEN, "Retirada", List.of(), true, AUTHORIZER, SECRET),
                new CashController.EntryRequest(
                        terminalId, BigDecimal.TEN, "Entrada", AUTHORIZER, SECRET, List.of()),
                new CashController.WithdrawalRequest(
                        terminalId, BigDecimal.TEN, "Retirada", List.of(), true,
                        AUTHORIZER, SECRET),
                new CashController.CloseRequest(
                        terminalId, BigDecimal.TEN, List.of(), BigDecimal.ONE,
                        "Cierre", List.of(), requestId, AUTHORIZER, SECRET),
                new CashDrawerController.AuthorizationRequest(
                        terminalId, AUTHORIZER, SECRET),
                new ProductEditAuthorizationController.AuthorizationRequest(
                        UUID.randomUUID(), AUTHORIZER, SECRET),
                new DiscountAuthorizationController.AuthorizationRequest(
                        AUTHORIZER, SECRET, BigDecimal.TEN),
                pendingSaleRequest(AUTHORIZER, SECRET),
                new CustomerPendingSaleController.CreditOverride(
                        "Superar limite", AUTHORIZER, SECRET),
                new SalePaymentSessionController.FinalizeRequest(
                        new SalePaymentSessionController.CreditOverride(
                                "Superar limite", AUTHORIZER, SECRET),
                        AUTHORIZER,
                        SECRET),
                new SalePaymentSessionController.CreditOverride(
                        "Superar limite", AUTHORIZER, SECRET),
                new SalePaymentSessionController.CompensationAck(
                        "Compensacion revisada", AUTHORIZER, SECRET),
                new TicketController.CancelRequest(
                        requestId, "Error de venta", AUTHORIZER, SECRET, Map.of()),
                new TicketController.ConvertToInvoiceRequest(
                        UUID.randomUUID(), AUTHORIZER, SECRET),
                new TicketController.ReturnRequest(
                        requestId, AUTHORIZER, SECRET, BigDecimal.ZERO, BigDecimal.ZERO,
                        List.of(), List.of()),
                new TicketCancellationService.CancellationCommand(
                        requestId, UUID.randomUUID(), "Error de venta",
                        AUTHORIZER, SECRET, Map.of()),
                new PaymentTerminalOperationController.AdjustmentRequest(
                        UUID.randomUUID(), "void-key", SECRET, AUTHORIZER, SECRET),
                new PaymentTerminalOperationController.RefundRequest(
                        UUID.randomUUID(), "refund-key", SECRET, BigDecimal.ONE,
                        List.of(), AUTHORIZER, SECRET),
                new ParkedSaleController.DeleteRequest(AUTHORIZER, SECRET));
    }

    private static DocumentRequest documentRequest(
            Map<SaleOperationCode, OperationAuthorizationRequest> authorizations) {
        return new DocumentRequest(
                UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 7, 31),
                UUID.randomUUID(),
                null,
                null,
                BigDecimal.ZERO,
                false,
                List.of(new DocumentRequest.LineRequest(
                        UUID.randomUUID(),
                        BigDecimal.ONE,
                        "P1",
                        "Producto",
                        "VENTA",
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        true,
                        "IVA",
                        new BigDecimal("21"),
                        DocumentLineType.PRODUCT,
                        null,
                        null,
                        null)),
                null,
                authorizations);
    }

    private static CustomerPendingSaleController.CreateRequest pendingSaleRequest(
            String authorizer, String password) {
        return new CustomerPendingSaleController.CreateRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 31),
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 31),
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                BigDecimal.ZERO,
                null,
                CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT,
                "Borrador",
                authorizer,
                password,
                Map.of());
    }
}
