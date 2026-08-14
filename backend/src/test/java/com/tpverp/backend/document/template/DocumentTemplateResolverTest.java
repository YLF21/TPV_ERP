package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DocumentTemplateResolverTest {

    private final DocumentTemplateRepository templates =
            org.mockito.Mockito.mock(DocumentTemplateRepository.class);
    private final CurrentOrganization organization =
            org.mockito.Mockito.mock(CurrentOrganization.class);
    private final DocumentTemplateResolver resolver =
            new DocumentTemplateResolver(templates, organization);

    @Test
    void storeTemplateHasPrecedenceOverCompanyAndSystem() {
        var store = DocumentTemplateTest.store();
        var storeTemplate = active(DocumentTemplate.storeDraft(
                store, DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_TIENDA", 2, "Tienda", null, Instant.EPOCH));
        when(templates.findActiveForStore(store.getId(), DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4))
                .thenReturn(Optional.of(storeTemplate));

        var resolved = resolver.resolve(store, DocumentTemplateType.FACTURA_VENTA);

        assertThat(resolved.code()).isEqualTo("FACTURA_TIENDA");
        assertThat(resolved.scope()).isEqualTo(DocumentTemplateScope.STORE);
        verify(templates, never()).findActiveForCompany(
                store.getEmpresa().getId(), DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4);
        verify(templates, never()).findActiveForSystem(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4);
    }

    @Test
    void fallsBackFromStoreToCompany() {
        var store = DocumentTemplateTest.store();
        var companyTemplate = active(DocumentTemplate.companyDraft(
                store.getEmpresa(), DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_EMPRESA", 4, "Empresa", null, Instant.EPOCH));
        when(templates.findActiveForStore(store.getId(), DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4))
                .thenReturn(Optional.empty());
        when(templates.findActiveForCompany(
                store.getEmpresa().getId(), DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4))
                .thenReturn(Optional.of(companyTemplate));

        var resolved = resolver.resolve(store, DocumentTemplateType.FACTURA_VENTA);

        assertThat(resolved.code()).isEqualTo("FACTURA_EMPRESA");
        assertThat(resolved.scope()).isEqualTo(DocumentTemplateScope.COMPANY);
    }

    @Test
    void requiresAnActiveJrxmlWhenNoCatalogTemplateExists() {
        var store = DocumentTemplateTest.store();
        when(templates.findActiveForStore(store.getId(), DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4))
                .thenReturn(Optional.empty());
        when(templates.findActiveForCompany(
                store.getEmpresa().getId(), DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4))
                .thenReturn(Optional.empty());
        when(templates.findActiveForSystem(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4))
                .thenReturn(Optional.empty());

        assertThat(resolver.findEffective(
                store, DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4))
                .isEmpty();
        assertThatThrownBy(() -> resolver.resolve(store, DocumentTemplateType.FACTURA_VENTA))
                .isInstanceOf(DocumentTemplateRequiredException.class)
                .hasMessage(DocumentTemplateRequiredException.MESSAGE_KEY)
                .satisfies(error -> {
                    var required = (DocumentTemplateRequiredException) error;
                    assertThat(required.documentType())
                            .isEqualTo(DocumentTemplateType.FACTURA_VENTA);
                    assertThat(required.format()).isEqualTo(DocumentTemplateFormat.A4);
                });
    }

    private static DocumentTemplate active(DocumentTemplate template) {
        template.validateArtifact(
                1, "signed:test", "a".repeat(64), Instant.EPOCH.plusSeconds(1));
        template.activate(Instant.EPOCH.plusSeconds(2));
        return template;
    }
}
