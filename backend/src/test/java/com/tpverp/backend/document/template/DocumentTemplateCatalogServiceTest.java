package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentTemplateCatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

    private final DocumentTemplateRepository templates =
            org.mockito.Mockito.mock(DocumentTemplateRepository.class);
    private final StoreRepository stores = org.mockito.Mockito.mock(StoreRepository.class);
    private final CurrentOrganization organization =
            org.mockito.Mockito.mock(CurrentOrganization.class);
    private final DocumentTemplateResolver resolver =
            org.mockito.Mockito.mock(DocumentTemplateResolver.class);
    private final AuditService audit = org.mockito.Mockito.mock(AuditService.class);
    private final DocumentTemplateCatalogService service = new DocumentTemplateCatalogService(
            templates, stores, organization, resolver, audit,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void exposesAnEmptyEffectiveTemplateSoTheFirstManualVersionCanBeCreated() {
        var store = DocumentTemplateTest.store();
        when(organization.currentStore()).thenReturn(store);
        when(resolver.findEffective(
                store, DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4))
                .thenReturn(Optional.empty());
        when(templates.findAllForStore(store.getId())).thenReturn(java.util.List.of());

        var catalog = service.currentStoreCatalog(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4);

        assertThat(catalog.effective()).isNull();
        assertThat(catalog.storeTemplates()).isEmpty();
    }

    @Test
    void registersNextStoreVersionUnderCurrentTenant() {
        var store = DocumentTemplateTest.store();
        when(organization.currentStore()).thenReturn(store);
        when(stores.findByIdForUpdate(store.getId())).thenReturn(Optional.of(store));
        when(templates.findMaxVersionForStore(store.getId(), "FACTURA_LP"))
                .thenReturn(2);
        when(templates.saveAndFlush(any(DocumentTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.registerCurrentStoreDraft(
                DocumentTemplateType.FACTURA_VENTA, "factura_lp", "Factura LP");

        assertThat(result.version()).isEqualTo(3);
        assertThat(result.scope()).isEqualTo(DocumentTemplateScope.STORE);
        assertThat(result.status()).isEqualTo(DocumentTemplateStatus.DRAFT);
        verify(audit).record(
                org.mockito.ArgumentMatchers.eq("DOCUMENT_TEMPLATE_DRAFT_REGISTERED"),
                org.mockito.ArgumentMatchers.eq(AuditResult.EXITO),
                any());
    }

    @Test
    void activationRetiresOnlyPreviousTemplateForSameStoreAndType() {
        var store = DocumentTemplateTest.store();
        var previous = validated(DocumentTemplate.storeDraft(
                store, DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_LP", 1, "Anterior", null, NOW.minusSeconds(300)));
        previous.activate(NOW.minusSeconds(200));
        var next = validated(DocumentTemplate.storeDraft(
                store, DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_LP", 2, "Nueva", null, NOW.minusSeconds(100)));
        when(organization.currentStore()).thenReturn(store);
        when(templates.findStoreTemplateForUpdate(
                next.getId(), store.getEmpresa().getId(), store.getId()))
                .thenReturn(Optional.of(next));
        when(templates.findActiveStoreTemplateForUpdate(
                store.getId(), DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4))
                .thenReturn(Optional.of(previous));
        when(templates.saveAndFlush(any(DocumentTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.activateValidatedCurrentStoreTemplate(next.getId());

        assertThat(result.status()).isEqualTo(DocumentTemplateStatus.ACTIVE);
        assertThat(previous.getStatus()).isEqualTo(DocumentTemplateStatus.RETIRED);
        assertThat(previous.getRetiredAt()).isEqualTo(NOW);
    }

    private static DocumentTemplate validated(DocumentTemplate template) {
        template.validateArtifact(
                1, "signed:test", "a".repeat(64), NOW.minusSeconds(50));
        return template;
    }
}
