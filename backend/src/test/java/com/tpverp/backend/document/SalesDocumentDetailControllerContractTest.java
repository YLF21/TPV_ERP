package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class SalesDocumentDetailControllerContractTest {

    @Test
    void exposesDocumentContentsToSalesAndManagementReaders() throws Exception {
        assertThat(SalesDocumentDetailController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/documents");
        var method = SalesDocumentDetailController.class.getDeclaredMethod("detail", UUID.class);

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/{documentId}/detail");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("VENTA", "GESTION_VENTAS", "INVOICES_READ", "DELIVERY_NOTES_READ");
    }

    @Test
    void exposesAnAuthoritativePrintableCopyOfTheConsultedDocument() throws Exception {
        var method = SalesDocumentDetailController.class.getDeclaredMethod("printCopy", UUID.class);

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/{documentId}/print-copy");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("VENTA", "GESTION_VENTAS", "INVOICES_READ", "DELIVERY_NOTES_READ");
    }
}
