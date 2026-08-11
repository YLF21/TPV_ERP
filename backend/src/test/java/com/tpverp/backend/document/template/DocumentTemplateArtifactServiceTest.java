package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class DocumentTemplateArtifactServiceTest {

    @TempDir Path tempDir;

    @Test
    void validatesStoresAndAuditsTheCurrentStoreDraft() throws Exception {
        var templates = mock(DocumentTemplateRepository.class);
        var organization = mock(CurrentOrganization.class);
        var compiler = mock(SafeJrxmlCompiler.class);
        var audit = mock(AuditService.class);
        var store = DocumentTemplateTest.store();
        var draft = DocumentTemplate.storeDraft(
                store, DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_LP", 1, "Factura LP", null,
                Instant.parse("2026-08-10T09:00:00Z"));
        when(organization.currentStore()).thenReturn(store);
        when(templates.findStoreTemplateForUpdate(
                draft.getId(), store.getEmpresa().getId(), store.getId()))
                .thenReturn(Optional.of(draft));
        when(templates.saveAndFlush(any(DocumentTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(compiler.compile(any(byte[].class))).thenReturn(
                new SafeJrxmlCompiler.CompiledTemplate(
                        new byte[] {1, 2}, new byte[] {3, 4}, "a".repeat(64)));
        var storage = new DocumentTemplateArtifactStorage(tempDir);
        var catalog = mock(DocumentTemplateCatalogService.class);
        var service = new DocumentTemplateArtifactService(
                templates, organization, compiler, storage, catalog, audit,
                Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC));

        var result = service.uploadAndValidate(
                draft.getId(), new MockMultipartFile(
                        "file", "factura.jrxml", "application/xml", new byte[] {1, 2}));

        assertThat(result.status()).isEqualTo(DocumentTemplateStatus.VALIDATED);
        assertThat(result.sha256()).isEqualTo("a".repeat(64));
        assertThat(storage.readSource(draft.getArtifactReference())).containsExactly(1, 2);
        assertThat(storage.readCompiled(draft.getArtifactReference())).containsExactly(3, 4);
        verify(audit).record(
                org.mockito.ArgumentMatchers.eq("DOCUMENT_TEMPLATE_VALIDATED"),
                org.mockito.ArgumentMatchers.eq(AuditResult.EXITO), any());
    }

    @Test
    void rejectsAnotherStoreBeforeCompilingOrWriting() {
        var templates = mock(DocumentTemplateRepository.class);
        var organization = mock(CurrentOrganization.class);
        var compiler = mock(SafeJrxmlCompiler.class);
        var store = DocumentTemplateTest.store();
        var templateId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(templates.findStoreTemplateForUpdate(
                templateId, store.getEmpresa().getId(), store.getId()))
                .thenReturn(Optional.empty());
        var service = new DocumentTemplateArtifactService(
                templates, organization, compiler,
                new DocumentTemplateArtifactStorage(tempDir),
                mock(DocumentTemplateCatalogService.class), mock(AuditService.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.uploadAndValidate(
                templateId, new MockMultipartFile(
                        "file", "factura.jrxml", "application/xml", new byte[] {1})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_not_found");
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void verifiesStoredHashAndRecompilesBeforeActivation() throws Exception {
        var templates = mock(DocumentTemplateRepository.class);
        var organization = mock(CurrentOrganization.class);
        var compiler = mock(SafeJrxmlCompiler.class);
        var catalog = mock(DocumentTemplateCatalogService.class);
        var store = DocumentTemplateTest.store();
        var template = DocumentTemplate.storeDraft(
                store, DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_LP", 1, "Factura LP", null,
                Instant.parse("2026-08-10T09:00:00Z"));
        byte[] source = new byte[] {1, 2};
        String hash = SafeJrxmlCompiler.sha256(source);
        template.validateArtifact(1, template.getId().toString(), hash,
                Instant.parse("2026-08-10T09:30:00Z"));
        var storage = new DocumentTemplateArtifactStorage(tempDir);
        storage.write(template.getId(), source, new byte[] {3, 4});
        when(organization.currentStore()).thenReturn(store);
        when(templates.findStoreTemplate(
                template.getId(), store.getEmpresa().getId(), store.getId()))
                .thenReturn(Optional.of(template));
        when(compiler.compile(any(byte[].class))).thenReturn(
                new SafeJrxmlCompiler.CompiledTemplate(source, new byte[] {3, 4}, hash));
        var expected = DocumentTemplateCatalogService.TemplateView.from(template);
        when(catalog.activateValidatedCurrentStoreTemplate(template.getId()))
                .thenReturn(expected);
        var service = new DocumentTemplateArtifactService(
                templates, organization, compiler, storage, catalog,
                mock(AuditService.class), Clock.systemUTC());

        var result = service.activate(template.getId());

        assertThat(result).isSameAs(expected);
        verify(compiler).compile(any(byte[].class));
        verify(catalog).activateValidatedCurrentStoreTemplate(template.getId());
    }
}
