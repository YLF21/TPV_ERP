package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class SalesDocumentCheckoutControllerContractTest {

    @Test
    void exposesDedicatedDocumentCheckoutEndpoints() throws Exception {
        assertThat(SalesDocumentCheckoutController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/pos/sales-document-checkouts");

        assertEndpoint("quote", CustomerPendingSaleController.CreateRequest.class, "/quote");
        assertEndpoint("chargeCard", CustomerPendingSaleController.CardChargeRequest.class,
                "/card-charges");
        assertEndpoint("create", CustomerPendingSaleController.CreateRequest.class, "");

        var create = SalesDocumentCheckoutController.class.getDeclaredMethod(
                "create", CustomerPendingSaleController.CreateRequest.class,
                org.springframework.security.core.Authentication.class);
        assertThat(create.getReturnType())
                .isEqualTo(SalesDocumentCheckoutController.Result.class);
    }

    @Test
    void requiresAnExplicitCompletionMode() {
        var controller = controller();
        var request = CustomerPendingSaleControllerContractTestRequest.request();

        assertThatThrownBy(() -> controller.quote(request, authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sales_document_completion_mode_required");
    }

    @Test
    void cardChargeIsOnlyAvailableForConfirmAndPay() {
        var controller = controller();
        var base = CustomerPendingSaleControllerContractTestRequest.request();
        var request = new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(),
                base.customerId(), base.dueDate(), base.globalDiscount(), base.lines(),
                base.payments(), base.quotedTotal(), base.creditOverride(),
                CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_PENDING);

        assertThatThrownBy(() -> controller.chargeCard(
                new CustomerPendingSaleController.CardChargeRequest(
                        request, java.math.BigDecimal.TEN),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sales_document_card_requires_confirm_and_pay");
    }

    @Test
    void documentSpecificPermissionCannotCreateTheOtherDocumentType() {
        var controller = controller();
        var base = CustomerPendingSaleControllerContractTestRequest.request();
        var invoice = withMode(
                base, CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT);
        var deliveryOnly = org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken.authenticated(
                        "user", "credentials",
                        java.util.List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("DELIVERY_NOTES_WRITE")));

        assertThatThrownBy(() -> controller.quote(invoice, deliveryOnly))
                .isInstanceOf(
                        org.springframework.security.access.AccessDeniedException.class)
                .hasMessage("sales_document_checkout_permission_required");
    }

    @Test
    void confirmedDocumentRemainsSuccessfulWhenPrintPreparationFails() {
        var service = org.mockito.Mockito.mock(CustomerPendingSaleService.class);
        var printing = org.mockito.Mockito.mock(CustomerReceivablePrintService.class);
        var views = org.mockito.Mockito.mock(DocumentViewAssembler.class);
        var document = org.mockito.Mockito.mock(CommercialDocument.class);
        var view = org.mockito.Mockito.mock(DocumentView.class);
        var documentId = java.util.UUID.randomUUID();
        var request = withMode(
                CustomerPendingSaleControllerContractTestRequest.request(),
                CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_PENDING);
        var authentication = org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken.authenticated(
                        "admin", "credentials",
                        java.util.List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_ADMIN")));
        org.mockito.Mockito.when(service.createDocument(request, authentication))
                .thenReturn(document);
        org.mockito.Mockito.when(document.getId()).thenReturn(documentId);
        org.mockito.Mockito.when(views.documentView(document)).thenReturn(view);
        org.mockito.Mockito.when(printing.document(documentId))
                .thenThrow(new IllegalStateException("invoice_jasper_render_failed"));

        var result = new SalesDocumentCheckoutController(service, printing, views)
                .create(request, authentication);

        assertThat(result.document()).isSameAs(view);
        assertThat(result.printDocument()).isNull();
        assertThat(result.printPreparationError()).isEqualTo("document_print_preparation_failed");
    }

    private static void assertEndpoint(String name, Class<?> requestType, String path)
            throws Exception {
        Method method = SalesDocumentCheckoutController.class.getDeclaredMethod(
                name, requestType, org.springframework.security.core.Authentication.class);
        if (path.isEmpty()) {
            assertThat(method.getAnnotation(PostMapping.class).value()).isEmpty();
        } else {
            assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        }
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("VENTA", "GESTION_VENTAS", "INVOICES_WRITE",
                        "DELIVERY_NOTES_WRITE")
                .doesNotContain("CUSTOMER_RECEIVABLES_CREATE");
    }

    private static SalesDocumentCheckoutController controller() {
        return new SalesDocumentCheckoutController(
                org.mockito.Mockito.mock(CustomerPendingSaleService.class),
                org.mockito.Mockito.mock(CustomerReceivablePrintService.class),
                org.mockito.Mockito.mock(DocumentViewAssembler.class));
    }

    private static org.springframework.security.core.Authentication authentication() {
        return org.mockito.Mockito.mock(
                org.springframework.security.core.Authentication.class);
    }

    private static CustomerPendingSaleController.CreateRequest withMode(
            CustomerPendingSaleController.CreateRequest base,
            CustomerPendingSaleController.SalesDocumentCompletionMode mode) {
        return new CustomerPendingSaleController.CreateRequest(
                base.checkoutId(), base.warehouseId(), base.type(), base.date(),
                base.customerId(), base.dueDate(), base.globalDiscount(), base.lines(),
                base.payments(), base.quotedTotal(), base.creditOverride(), mode);
    }

    private static final class CustomerPendingSaleControllerContractTestRequest {
        private static CustomerPendingSaleController.CreateRequest request() {
            return new CustomerPendingSaleController.CreateRequest(
                    java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                    CommercialDocumentType.FACTURA_VENTA,
                    java.time.LocalDate.of(2026, 7, 24), java.util.UUID.randomUUID(),
                    java.time.LocalDate.of(2026, 8, 24), java.math.BigDecimal.ZERO,
                    java.util.List.of(new DocumentRequest.LineRequest(
                            java.util.UUID.randomUUID(), java.math.BigDecimal.ONE,
                            "P", "Producto", null, java.math.BigDecimal.TEN,
                            java.math.BigDecimal.ZERO, true, "IVA",
                            new java.math.BigDecimal("21"), null, null, null, null)),
                    java.util.List.of(), java.math.BigDecimal.TEN);
        }
    }
}
