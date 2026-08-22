package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class GenericSalesApiServiceTest {

    @Mock DocumentService documents;
    @Mock SaleDocumentMutationAuthorizationService authorizations;
    @Mock CustomerPendingSaleService pendingSales;
    @Mock SaleDocumentAuthorizationManifestService manifests;

    private final Authentication authentication =
            new UsernamePasswordAuthenticationToken("operator", "unused");

    @Test
    void authorizesSaleInvoiceImmediatelyBeforeCreation() {
        var request = request(CommercialDocumentType.FACTURA_VENTA);
        var expected = document(CommercialDocumentType.FACTURA_VENTA);
        var proof = SaleDocumentMutationAuthorizationService.AuthorizationProof.empty();
        when(authorizations.authorize(
                any(DocumentCommand.class),
                eq(request.operationAuthorizations()),
                same(authentication),
                eq("GENERIC_INVOICE_CREATE"),
                eq(null))).thenReturn(proof);
        when(documents.createInvoice(any(), same(authentication))).thenReturn(expected);
        var service = service();

        var result = service.createInvoice(request, authentication);

        assertThat(result).isSameAs(expected);
        var order = inOrder(authorizations, documents);
        order.verify(authorizations).authorize(
                any(DocumentCommand.class),
                eq(request.operationAuthorizations()),
                same(authentication),
                eq("GENERIC_INVOICE_CREATE"),
                eq(null));
        order.verify(documents).createInvoice(
                any(DocumentCommand.class), same(authentication));
        verify(manifests).record(
                same(expected),
                same(proof));
    }

    @Test
    void createAndConfirmAuthorizesReceivableAndCreditOverrideBeforeConfirmation() {
        var base = request(CommercialDocumentType.FACTURA_VENTA);
        var pendingCredentials =
                new OperationAuthorizationRequest("credit-manager", "pending-secret");
        var overrideCredentials =
                new OperationAuthorizationRequest("admin-manager", "override-secret");
        var credentials = new java.util.EnumMap<SaleOperationCode,
                OperationAuthorizationRequest>(SaleOperationCode.class);
        credentials.putAll(base.operationAuthorizations());
        credentials.put(SaleOperationCode.CREATE_PENDING_RECEIVABLE, pendingCredentials);
        credentials.put(SaleOperationCode.CREDIT_OVERRIDE, overrideCredentials);
        var request = new DocumentRequest(
                base.almacenId(),
                base.tipo(),
                base.fecha(),
                base.clienteId(),
                base.proveedorId(),
                base.numeroExterno(),
                base.descuentoGlobal(),
                base.directo(),
                base.lineas(),
                base.comentarioInterno(),
                credentials,
                "Límite autorizado por pedido firmado");
        var draft = document(CommercialDocumentType.FACTURA_VENTA);
        var confirmed = document(CommercialDocumentType.FACTURA_VENTA);
        var pendingAuthorization =
                new CustomerPendingSaleService.PendingCreditAuthorization(
                        mock(CustomerPendingSaleService.CreditAssessment.class),
                        null,
                        null);
        when(documents.createInvoice(any(), same(authentication))).thenReturn(draft);
        when(pendingSales.authorizePendingTicket(
                eq(draft.getClienteId()),
                eq(draft.getFecha()),
                eq(draft.getPendingTotal()),
                eq(request.creditOverrideReason()),
                eq("credit-manager"),
                eq("pending-secret"),
                eq("admin-manager"),
                eq("override-secret"),
                same(authentication))).thenReturn(pendingAuthorization);
        when(documents.confirm(draft.getId(), authentication)).thenReturn(confirmed);
        var service = service();

        assertThat(service.createAndConfirmInvoice(request, authentication))
                .isSameAs(confirmed);

        var order = inOrder(authorizations, documents, pendingSales);
        order.verify(authorizations).authorize(
                any(DocumentCommand.class),
                eq(request.operationAuthorizations()),
                same(authentication),
                eq("GENERIC_INVOICE_CREATE_CONFIRMED"),
                eq(null));
        order.verify(documents).createInvoice(
                any(DocumentCommand.class), same(authentication));
        order.verify(pendingSales).authorizePendingTicket(
                eq(draft.getClienteId()),
                eq(draft.getFecha()),
                eq(draft.getPendingTotal()),
                eq(request.creditOverrideReason()),
                eq("credit-manager"),
                eq("pending-secret"),
                eq("admin-manager"),
                eq("override-secret"),
                same(authentication));
        order.verify(documents).confirm(draft.getId(), authentication);
        order.verify(pendingSales).recordPendingTicketAuthorization(
                draft.getId(),
                confirmed,
                draft.getClienteId(),
                request.creditOverrideReason(),
                pendingAuthorization);
    }

    @Test
    void blocksSeparateGenericSaleConfirmationWithoutPersistedManifest() {
        var id = UUID.randomUUID();
        var draft = document(CommercialDocumentType.ALBARAN_VENTA);
        var credentials = new SaleOperationAuthorizationsRequest(Map.of(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                new OperationAuthorizationRequest("manager", "secret")));
        when(documents.find(id)).thenReturn(draft);
        when(manifests.validate(draft))
                .thenThrow(new GenericSaleConfirmationBlockedException());
        var service = service();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.confirm(id, credentials, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "generic_sale_confirmation_requires_persisted_authorization_manifest");

        verify(authorizations, never()).authorize(
                any(), any(), any(), any(), any());
        verify(documents, never()).confirm(any(), any());
    }

    @Test
    void confirmsUnchangedDraftWithCurrentPersistedManifest() {
        var draft = document(CommercialDocumentType.ALBARAN_VENTA);
        var id = draft.getId();
        var confirmed = document(CommercialDocumentType.ALBARAN_VENTA);
        var request = SaleOperationAuthorizationsRequest.empty();
        var pendingAuthorization = pendingAuthorization();
        when(documents.find(id)).thenReturn(draft);
        when(manifests.validate(draft)).thenReturn(
                new SaleDocumentAuthorizationManifestService.Validation(
                        java.util.Set.of(SaleOperationCode.APPLY_SALE_DISCOUNT),
                        false));
        when(pendingSales.authorizePendingTicket(
                eq(draft.getClienteId()),
                eq(draft.getFecha()),
                eq(draft.getPendingTotal()),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                same(authentication))).thenReturn(pendingAuthorization);
        when(documents.confirm(id, authentication)).thenReturn(confirmed);

        assertThat(service().confirm(id, request, authentication))
                .isSameAs(confirmed);

        verify(authorizations, never()).reauthorize(
                any(), any(), any(), any(), any(), any());
        verify(manifests, never()).refresh(any(), any());
        verify(pendingSales).recordPendingTicketAuthorization(
                id, confirmed, draft.getClienteId(), null, pendingAuthorization);
    }

    @Test
    void reauthorizesStoredOperationsWhenPolicyVersionChanged() {
        var draft = document(CommercialDocumentType.FACTURA_VENTA);
        var id = draft.getId();
        var confirmed = document(CommercialDocumentType.FACTURA_VENTA);
        var credential = new OperationAuthorizationRequest("manager", "secret");
        var request = new SaleOperationAuthorizationsRequest(Map.of(
                SaleOperationCode.TEMPORARY_PRICE_CHANGE, credential));
        var operations = java.util.Set.of(
                SaleOperationCode.TEMPORARY_PRICE_CHANGE);
        var proof = new SaleDocumentMutationAuthorizationService.AuthorizationProof(
                Map.of(SaleOperationCode.TEMPORARY_PRICE_CHANGE, 2L));
        var pendingAuthorization = pendingAuthorization();
        when(documents.find(id)).thenReturn(draft);
        when(manifests.validate(draft)).thenReturn(
                new SaleDocumentAuthorizationManifestService.Validation(
                        operations, true));
        when(authorizations.reauthorize(
                draft,
                operations,
                request.operationAuthorizations(),
                authentication,
                "GENERIC_SALE_CONFIRMATION_REAUTHORIZATION",
                draft.getId())).thenReturn(proof);
        when(pendingSales.authorizePendingTicket(
                eq(draft.getClienteId()),
                eq(draft.getFecha()),
                eq(draft.getPendingTotal()),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                same(authentication))).thenReturn(pendingAuthorization);
        when(documents.confirm(id, authentication)).thenReturn(confirmed);

        assertThat(service().confirm(id, request, authentication))
                .isSameAs(confirmed);

        var order = inOrder(authorizations, manifests, pendingSales, documents);
        order.verify(manifests).validate(draft);
        order.verify(authorizations).reauthorize(
                draft,
                operations,
                request.operationAuthorizations(),
                authentication,
                "GENERIC_SALE_CONFIRMATION_REAUTHORIZATION",
                draft.getId());
        order.verify(manifests).refresh(draft, proof);
        order.verify(pendingSales).authorizePendingTicket(
                eq(draft.getClienteId()),
                eq(draft.getFecha()),
                eq(draft.getPendingTotal()),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                same(authentication));
        order.verify(documents).confirm(id, authentication);
    }

    @Test
    void authorizesManualPaymentBeforeMutatingSaleReceivable() {
        var id = UUID.randomUUID();
        var invoice = document(CommercialDocumentType.FACTURA_VENTA);
        var request = new PaymentRequest(List.of(new PaymentRequest.Item(
                UUID.randomUUID(), new BigDecimal("10.00"), true,
                null, null, null)));
        when(documents.find(id)).thenReturn(invoice);
        when(documents.payInvoice(eq(id), any(), same(authentication)))
                .thenReturn(invoice);
        var service = service();

        assertThat(service.payInvoice(id, request, authentication)).isSameAs(invoice);

        var order = inOrder(authorizations, documents);
        order.verify(authorizations).authorizePayments(
                same(request),
                eq(request.operationAuthorizations()),
                eq(SaleDocumentMutationAuthorizationService.IntegratedPaymentPolicy
                        .REJECT_UNPROVEN),
                same(authentication),
                eq("GENERIC_INVOICE_PAYMENT"),
                eq(invoice.getId()));
        order.verify(documents).payInvoice(eq(id), any(), same(authentication));
    }

    @Test
    void legacyTicketUsesOnlyTheFailClosedGenericGuard() {
        var document = request(CommercialDocumentType.TICKET);
        var payment = new PaymentRequest(List.of(new PaymentRequest.Item(
                UUID.randomUUID(), new BigDecimal("10.00"), true,
                null, null, null)));
        var request = new TicketController.CreateTicketRequest(
                document,
                payment,
                document.operationAuthorizations());
        var expected = document(CommercialDocumentType.TICKET);
        when(documents.createTicket(any(), any(), same(authentication)))
                .thenReturn(expected);
        var service = service();

        assertThat(service.createTicket(request, authentication)).isSameAs(expected);

        var order = inOrder(authorizations, documents);
        order.verify(authorizations).authorize(
                any(), eq(document.operationAuthorizations()), same(authentication),
                eq("LEGACY_TICKET"), eq(null));
        order.verify(authorizations).authorizePayments(
                same(payment), eq(document.operationAuthorizations()),
                eq(SaleDocumentMutationAuthorizationService.IntegratedPaymentPolicy
                        .REJECT_UNPROVEN),
                same(authentication), eq("LEGACY_TICKET"), eq(null));
        order.verify(documents).createTicket(any(), any(), same(authentication));
    }

    private GenericSalesApiService service() {
        return new GenericSalesApiService(
                documents, authorizations, pendingSales, manifests);
    }

    private static CustomerPendingSaleService.PendingCreditAuthorization
            pendingAuthorization() {
        return new CustomerPendingSaleService.PendingCreditAuthorization(
                mock(CustomerPendingSaleService.CreditAssessment.class),
                null,
                null);
    }

    private static DocumentRequest request(CommercialDocumentType type) {
        var credentials = Map.of(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                new OperationAuthorizationRequest("manager", "secret"));
        return new DocumentRequest(
                UUID.randomUUID(),
                type,
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
                        new BigDecimal("10.00"),
                        BigDecimal.ZERO,
                        true,
                        "IVA",
                        new BigDecimal("21"),
                        DocumentLineType.PRODUCT,
                        null,
                        null,
                        null,
                        List.of(),
                        false,
                        false)),
                null,
                credentials);
    }

    private static CommercialDocument document(CommercialDocumentType type) {
        var document = new CommercialDocument(
                UUID.randomUUID(),
                UUID.randomUUID(),
                type,
                LocalDate.of(2026, 7, 31),
                UUID.randomUUID(),
                BigDecimal.ZERO);
        if (type == CommercialDocumentType.FACTURA_VENTA
                || type == CommercialDocumentType.ALBARAN_VENTA) {
            document.setParties(UUID.randomUUID(), null, null);
        }
        document.addLine(new DocumentLine(
                document,
                UUID.randomUUID(),
                1,
                1,
                "P1",
                "Producto",
                "VENTA",
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                true,
                "IVA",
                new BigDecimal("21")));
        return document;
    }
}
