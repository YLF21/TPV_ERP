package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
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
        assertController(CommercialReportController.class, "/api/v1/commercial-reports");
        assertController(DocumentReportController.class, "/api/v1/document-reports");
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

    @Test
    void salesManagementCoversGranularSalesDocumentActions() throws NoSuchMethodException {
        assertSalesManagement(DeliveryNoteController.class.getDeclaredMethod(
                "list", org.springframework.security.core.Authentication.class));
        assertSalesManagement(DeliveryNoteController.class.getDeclaredMethod(
                "create", DocumentRequest.class, org.springframework.security.core.Authentication.class));
        assertSalesManagement(DeliveryNoteController.class.getDeclaredMethod(
                "confirm", UUID.class, org.springframework.security.core.Authentication.class));
        assertSalesManagement(TicketController.class.getDeclaredMethod("list"));
        assertSalesManagement(TicketController.class.getDeclaredMethod(
                "create", TicketController.CreateTicketRequest.class,
                org.springframework.security.core.Authentication.class));
        assertSalesManagement(TicketController.class.getDeclaredMethod(
                "cancel", UUID.class, TicketController.CancelRequest.class,
                org.springframework.security.core.Authentication.class));
        assertSalesManagement(InvoiceController.class.getDeclaredMethod(
                "list", org.springframework.security.core.Authentication.class));
        assertSalesManagement(InvoiceController.class.getDeclaredMethod(
                "create", DocumentRequest.class, org.springframework.security.core.Authentication.class));
        assertSalesManagement(InvoiceController.class.getDeclaredMethod(
                "confirm", UUID.class, org.springframework.security.core.Authentication.class));
        assertSalesManagement(InvoiceController.class.getDeclaredMethod(
                "pay", UUID.class, PaymentRequest.class,
                org.springframework.security.core.Authentication.class));
        assertSalesManagement(InvoiceController.class.getDeclaredMethod(
                "relate", UUID.class, InvoiceController.RelationRequest.class));
        assertSalesManagement(DocumentReportController.class.getDeclaredMethod(
                "invoices", Integer.class, String.class,
                org.springframework.security.core.Authentication.class));
        assertSalesManagement(DocumentReportController.class.getDeclaredMethod(
                "deliveryNotes", Integer.class, String.class,
                org.springframework.security.core.Authentication.class));
    }

    @Test
    void productAndWarehouseManagementCanReadAndCreatePurchaseDocuments() throws NoSuchMethodException {
        assertPurchaseRead(DeliveryNoteController.class.getDeclaredMethod(
                "list", org.springframework.security.core.Authentication.class));
        assertPurchaseWrite(DeliveryNoteController.class.getDeclaredMethod(
                "create", DocumentRequest.class, org.springframework.security.core.Authentication.class));
        var confirmedDeliveryNote = DeliveryNoteController.class.getDeclaredMethod(
                "createAndConfirm", DocumentRequest.class, org.springframework.security.core.Authentication.class);
        assertPurchaseWrite(confirmedDeliveryNote);
        assertThat(confirmedDeliveryNote.getAnnotation(PostMapping.class).value())
                .containsExactly("/confirmed");
        assertPurchaseWrite(DeliveryNoteController.class.getDeclaredMethod(
                "confirm", UUID.class, org.springframework.security.core.Authentication.class));
        assertPurchaseRead(InvoiceController.class.getDeclaredMethod(
                "list", org.springframework.security.core.Authentication.class));
        assertPurchaseWrite(InvoiceController.class.getDeclaredMethod(
                "create", DocumentRequest.class, org.springframework.security.core.Authentication.class));
        var confirmedInvoice = InvoiceController.class.getDeclaredMethod(
                "createAndConfirm", DocumentRequest.class, org.springframework.security.core.Authentication.class);
        assertPurchaseWrite(confirmedInvoice);
        assertThat(confirmedInvoice.getAnnotation(PostMapping.class).value())
                .containsExactly("/confirmed");
        assertPurchaseWrite(InvoiceController.class.getDeclaredMethod(
                "confirm", UUID.class, org.springframework.security.core.Authentication.class));
        assertPurchaseRead(DocumentReportController.class.getDeclaredMethod(
                "invoices", Integer.class, String.class,
                org.springframework.security.core.Authentication.class));
        assertPurchaseRead(DocumentReportController.class.getDeclaredMethod(
                "deliveryNotes", Integer.class, String.class,
                org.springframework.security.core.Authentication.class));
    }

    @Test
    void purchaseCreationAndConfirmationRunsInOneTransaction() throws NoSuchMethodException {
        assertThat(DocumentService.class.getDeclaredMethod(
                        "createAndConfirmDeliveryNote", DocumentCommand.class,
                        org.springframework.security.core.Authentication.class)
                .getAnnotation(Transactional.class))
                .isNotNull();
        assertThat(DocumentService.class.getDeclaredMethod(
                        "createAndConfirmInvoice", DocumentCommand.class,
                        org.springframework.security.core.Authentication.class)
                .getAnnotation(Transactional.class))
                .isNotNull();
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

    private void assertSalesManagement(Method method) {
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    private void assertPurchaseRead(Method method) {
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_PRODUCTO", "GESTION_ALMACEN", "GESTION_CUENTAS");
    }

    private void assertPurchaseWrite(Method method) {
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_PRODUCTO", "GESTION_ALMACEN")
                .doesNotContain("GESTION_CUENTAS");
    }
}
