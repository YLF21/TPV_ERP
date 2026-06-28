package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class DocumentControllerContractTest {

    @Test
    void exposesExpectedApiRootsWithMethodSecurity() {
        assertController(PaymentMethodController.class, "/api/v1/payment-methods");
        assertController(DeliveryNoteController.class, "/api/v1/delivery-notes");
        assertController(TicketController.class, "/api/v1/tickets");
        assertController(InvoiceController.class, "/api/v1/invoices");
        assertController(ParkedSaleController.class, "/api/v1/parked-sales");
        assertController(SaleLineDeletionController.class, "/api/v1/sale-line-deletions");
        assertController(VoucherController.class, "/api/v1/vouchers");
    }

    @Test
    void exposesTicketToInvoiceConversionEndpoint() throws NoSuchMethodException {
        var method = TicketController.class.getDeclaredMethod(
                "convertToInvoice", UUID.class,
                TicketController.ConvertToInvoiceRequest.class,
                org.springframework.security.core.Authentication.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/{id}/invoice");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    @Test
    void salesManagementCanListPaymentMethods() throws NoSuchMethodException {
        var method = PaymentMethodController.class.getDeclaredMethod("list", UUID.class);

        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    @Test
    void deliveryNotesExposePaymentEndpoint() throws NoSuchMethodException {
        var method = DeliveryNoteController.class.getDeclaredMethod(
                "pay", UUID.class, PaymentRequest.class,
                org.springframework.security.core.Authentication.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/{id}/pay");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    private void assertController(Class<?> type, String path) {
        assertThat(type.getAnnotation(RequestMapping.class).value()).containsExactly(path);
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(Method::isSynthetic)
                .toList()).isEmpty();
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())))
                .allMatch(method -> method.isAnnotationPresent(PreAuthorize.class));
    }
}
