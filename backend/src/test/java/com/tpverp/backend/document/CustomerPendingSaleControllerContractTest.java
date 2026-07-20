package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;
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
        var create = CustomerPendingSaleController.class.getDeclaredMethod("create",
                CustomerPendingSaleController.CreateRequest.class,
                org.springframework.security.core.Authentication.class);
        assertThat(create.getReturnType()).isEqualTo(CustomerPendingSaleController.CreateResponse.class);
    }

    @Test
    void bothPosReceivableTypesAreDirectStockDocuments() {
        var invoice = request(CommercialDocumentType.FACTURA_VENTA).toCommand();
        var note = request(CommercialDocumentType.ALBARAN_VENTA).toCommand();

        assertThat(invoice.directo()).isTrue();
        assertThat(note.directo()).isTrue();
    }

    private static CustomerPendingSaleController.CreateRequest request(
            CommercialDocumentType type) {
        return new CustomerPendingSaleController.CreateRequest(
                UUID.randomUUID(), UUID.randomUUID(), type,
                java.time.LocalDate.of(2026, 7, 16), UUID.randomUUID(),
                java.time.LocalDate.of(2026, 8, 16), java.math.BigDecimal.ZERO,
                java.util.List.of(new DocumentRequest.LineRequest(
                        UUID.randomUUID(), java.math.BigDecimal.ONE, "P", "Producto", null,
                        java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, true, "IVA",
                        new java.math.BigDecimal("21"), null, null, null, null)),
                java.util.List.of(), java.math.BigDecimal.TEN);
    }

    private static void assertEndpoint(String name, Class<?> requestType, String path)
            throws Exception {
        Method method = CustomerPendingSaleController.class.getDeclaredMethod(
                name, requestType, org.springframework.security.core.Authentication.class);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("CUSTOMER_RECEIVABLES_CREATE")
                .doesNotContain("GESTION_VENTAS", "VENTA");
    }
}
