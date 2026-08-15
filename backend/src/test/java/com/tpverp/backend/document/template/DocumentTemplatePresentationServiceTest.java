package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentTemplatePresentationServiceTest {

    @Test
    void defaultsToIntegratedAndStoresTheSelectedInvoiceFormat() {
        var organization = mock(CurrentOrganization.class);
        var settings = mock(DocumentTemplatePresentationSettingRepository.class);
        var resolver = mock(DocumentTemplateResolver.class);
        var audit = mock(AuditService.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(settings.findByStoreIdAndTypeAndFormat(
                storeId, DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4)).thenReturn(Optional.empty());
        when(settings.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(resolver.findEffective(
                store, DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4)).thenReturn(Optional.of(
                new ResolvedDocumentTemplate(
                        UUID.randomUUID(), DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.A4, DocumentTemplateScope.STORE,
                        "FACTURA_TIENDA", 1, 1, "artifact", "a".repeat(64), false)));
        var service = new DocumentTemplatePresentationService(
                organization, settings, resolver, audit);

        assertThat(service.origin(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4))
                .isEqualTo(DocumentTemplateOrigin.INTEGRATED);
        assertThat(service.update(
                DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4,
                DocumentTemplateOrigin.IMPORTED).origin())
                .isEqualTo(DocumentTemplateOrigin.IMPORTED);

        verify(settings).save(any(DocumentTemplatePresentationSetting.class));
        verify(audit).record(
                org.mockito.ArgumentMatchers.eq("DOCUMENT_TEMPLATE_PRESENTATION_UPDATED"),
                any(), any());
    }

    @Test
    void rejectsImportedOriginWithoutAnEffectiveTemplate() {
        var organization = mock(CurrentOrganization.class);
        var resolver = mock(DocumentTemplateResolver.class);
        var store = mock(Store.class);
        when(organization.currentStore()).thenReturn(store);
        when(resolver.findEffective(
                store, DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.TICKET_80)).thenReturn(Optional.empty());
        var service = new DocumentTemplatePresentationService(
                organization,
                mock(DocumentTemplatePresentationSettingRepository.class),
                resolver,
                mock(AuditService.class));

        assertThatThrownBy(() -> service.update(
                DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.TICKET_80,
                DocumentTemplateOrigin.IMPORTED))
                .isInstanceOf(DocumentTemplateRequiredException.class);
    }

    @Test
    void exposesIntegratedOriginsForDeliveryNotesAndVouchers() {
        var organization = mock(CurrentOrganization.class);
        var settings = mock(DocumentTemplatePresentationSettingRepository.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(settings.findByStoreIdAndTypeAndFormat(
                storeId, DocumentTemplateType.ALBARAN_VENTA,
                DocumentTemplateFormat.A4)).thenReturn(Optional.empty());
        when(settings.findByStoreIdAndTypeAndFormat(
                storeId, DocumentTemplateType.VALE,
                DocumentTemplateFormat.TICKET_80)).thenReturn(Optional.empty());
        var service = new DocumentTemplatePresentationService(
                organization,
                settings,
                mock(DocumentTemplateResolver.class),
                mock(AuditService.class));

        assertThat(service.presentation(
                DocumentTemplateType.ALBARAN_VENTA, DocumentTemplateFormat.A4).origin())
                .isEqualTo(DocumentTemplateOrigin.INTEGRATED);
        assertThat(service.presentation(
                DocumentTemplateType.VALE, DocumentTemplateFormat.TICKET_80).origin())
                .isEqualTo(DocumentTemplateOrigin.INTEGRATED);
    }
}
