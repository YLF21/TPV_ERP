package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.transaction.annotation.Transactional;

class CustomerReceivableControllerContractTest {

    @Test
    void exposesReadAndPaymentEndpointsWithSeparatePermissions() throws Exception {
        assertThat(CustomerReceivableController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/customer-receivables");

        assertReadEndpoint("list", CustomerReceivableFilter.class, "");
        assertReadEndpoint("detail", UUID.class, "/{documentId}");
        assertReadEndpoint("paymentHistory", CustomerReceivablePaymentHistoryFilter.class,
                "/payment-history");
        assertPrintEndpoint("printDocument", new Class<?>[] {UUID.class},
                "/{documentId}/print-document");
        assertPrintEndpoint("paymentReceipt", new Class<?>[] {UUID.class, UUID.class},
                "/{documentId}/payments/{paymentId}/receipt");
        assertPrintEndpoint("printPayment", new Class<?>[] {UUID.class, UUID.class},
                "/{documentId}/payments/{paymentId}/print");
        assertPayEndpoint("chargeCard", UUID.class,
                CustomerReceivableController.CardChargeRequest.class, "/{documentId}/card-charges");
        assertPayEndpoint("pay", UUID.class, PaymentRequest.class, "/{documentId}/payments");
        var pay = CustomerReceivableController.class.getDeclaredMethod("pay", UUID.class,
                PaymentRequest.class, org.springframework.security.core.Authentication.class);
        assertThat(pay.getReturnType()).isEqualTo(CustomerReceivableController.PaymentResponse.class);
    }

    private static void assertPrintEndpoint(
            String name, Class<?>[] argumentTypes, String path)
            throws Exception {
        Method method = CustomerReceivableController.class.getDeclaredMethod(name, argumentTypes);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("CUSTOMER_RECEIVABLES_READ")
                .doesNotContain("CUSTOMER_RECEIVABLES_CREATE", "CUSTOMER_RECEIVABLES_PAY");
        assertThat(method.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
    }

    @Test
    void paymentRequestCarriesStablePaymentAndTerminalOperationIds() {
        var paymentId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var item = new PaymentRequest.Item(
                UUID.randomUUID(), new BigDecimal("20.00"), true, null, null, null,
                null, null, null, null, null, null, paymentId, operationId);

        assertThat(item.requestId()).isEqualTo(paymentId);
        assertThat(item.paymentTerminalOperationId()).isEqualTo(operationId);
    }

    @Test
    void cardChargeDoesNotHoldAnOuterTransactionAcrossTerminalIo() throws Exception {
        var method = CustomerReceivableService.class.getDeclaredMethod(
                "chargeCard", UUID.class,
                CustomerReceivableController.CardChargeRequest.class,
                org.springframework.security.core.Authentication.class);

        assertThat(method.getAnnotation(Transactional.class)).isNull();
    }

    private static void assertReadEndpoint(String name, Class<?> argument, String path)
            throws Exception {
        Method method = CustomerReceivableController.class.getDeclaredMethod(
                name, argument, org.springframework.security.core.Authentication.class);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("CUSTOMER_RECEIVABLES_READ");
    }

    private static void assertPayEndpoint(
            String name, Class<?> idType, Class<?> requestType, String path) throws Exception {
        Method method = CustomerReceivableController.class.getDeclaredMethod(
                name, idType, requestType, org.springframework.security.core.Authentication.class);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("CUSTOMER_RECEIVABLES_PAY")
                .doesNotContain("GESTION_VENTAS", "VENTA");
        assertThat(method.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
    }
}
