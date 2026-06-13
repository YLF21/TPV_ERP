package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class DocumentControllerContractTest {

    @Test
    void exposesExpectedApiRootsWithMethodSecurity() {
        assertController(PaymentMethodController.class, "/api/v1/payment-methods");
        assertController(DeliveryNoteController.class, "/api/v1/delivery-notes");
        assertController(TicketController.class, "/api/v1/tickets");
        assertController(InvoiceController.class, "/api/v1/invoices");
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
