package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class CustomerPendingSaleControllerContractTest {

    @Test
    void exposesAuthoritativePendingSaleEndpointsWithReceivablePermission() throws Exception {
        assertThat(CustomerPendingSaleController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/pos/customer-pending-sales");

        assertEndpoint("quote", CustomerPendingSaleController.CreateRequest.class,
                "/quote");
        assertEndpoint("chargeCard", CustomerPendingSaleController.CardChargeRequest.class,
                "/card-charges");
        assertEndpoint("create", CustomerPendingSaleController.CreateRequest.class, "");
    }

    private static void assertEndpoint(String name, Class<?> requestType, String path)
            throws Exception {
        Method method = CustomerPendingSaleController.class.getDeclaredMethod(
                name, requestType, org.springframework.security.core.Authentication.class);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("CUSTOMER_RECEIVABLES_CREATE")
                .contains("GESTION_VENTAS")
                .contains("VENTA");
    }
}
