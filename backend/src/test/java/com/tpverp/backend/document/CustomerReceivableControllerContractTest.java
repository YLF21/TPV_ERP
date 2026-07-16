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

class CustomerReceivableControllerContractTest {

    @Test
    void exposesReadAndPaymentEndpointsWithSeparatePermissions() throws Exception {
        assertThat(CustomerReceivableController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/customer-receivables");

        assertReadEndpoint("list", CustomerReceivableFilter.class, "");
        assertReadEndpoint("detail", UUID.class, "/{documentId}");
        assertPayEndpoint("chargeCard", UUID.class,
                CustomerReceivableController.CardChargeRequest.class, "/{documentId}/card-charges");
        assertPayEndpoint("pay", UUID.class, PaymentRequest.class, "/{documentId}/payments");
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
                .contains("CUSTOMER_RECEIVABLES_PAY");
        assertThat(method.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
    }
}
