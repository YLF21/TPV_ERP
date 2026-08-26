package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
                        fiscalJrxml(), new byte[] {3, 4}, "a".repeat(64)));
        var storage = new DocumentTemplateArtifactStorage(tempDir);
        var catalog = mock(DocumentTemplateCatalogService.class);
        var service = new DocumentTemplateArtifactService(
                templates, organization, compiler, mock(TicketJrxmlBundleCompiler.class),
                storage, catalog, audit,
                Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC));

        var result = service.uploadAndValidate(
                draft.getId(), java.util.List.of(new MockMultipartFile(
                        "files", "factura.jrxml", "application/xml", fiscalJrxml())));

        assertThat(result.status()).isEqualTo(DocumentTemplateStatus.VALIDATED);
        assertThat(result.sha256()).isEqualTo("a".repeat(64));
        assertThat(storage.readSource(draft.getArtifactReference())).containsExactly(fiscalJrxml());
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
                templates, organization, compiler, mock(TicketJrxmlBundleCompiler.class),
                new DocumentTemplateArtifactStorage(tempDir),
                mock(DocumentTemplateCatalogService.class), mock(AuditService.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.uploadAndValidate(
                templateId, java.util.List.of(new MockMultipartFile(
                        "files", "factura.jrxml", "application/xml", new byte[] {1}))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_not_found");
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void blocksFiscalCustomActivationUntilRenderAndQrDecodeEvidenceExists() throws Exception {
        var templates = mock(DocumentTemplateRepository.class);
        var organization = mock(CurrentOrganization.class);
        var compiler = mock(SafeJrxmlCompiler.class);
        var catalog = mock(DocumentTemplateCatalogService.class);
        var store = DocumentTemplateTest.store();
        var template = DocumentTemplate.storeDraft(
                store, DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_LP", 1, "Factura LP", null,
                Instant.parse("2026-08-10T09:00:00Z"));
        byte[] source = fiscalJrxml();
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
                templates, organization, compiler, mock(TicketJrxmlBundleCompiler.class),
                storage, catalog,
                mock(AuditService.class), Clock.systemUTC());

        assertThatThrownBy(() -> service.activate(template.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "document_template_fiscal_custom_activation_requires_visual_validation");

        verifyNoInteractions(compiler, catalog);
    }

    @Test
    void acceptsAnyJrxmlFilenameAsTheSingleTicketMaster() throws Exception {
        var templates = mock(DocumentTemplateRepository.class);
        var organization = mock(CurrentOrganization.class);
        var ticketCompiler = mock(TicketJrxmlBundleCompiler.class);
        var store = DocumentTemplateTest.store();
        var draft = DocumentTemplate.storeDraft(
                store, DocumentTemplateType.TICKET, DocumentTemplateFormat.TICKET_80,
                "TICKET_80", 4, "Mi ticket", null,
                Instant.parse("2026-08-15T13:00:00Z"));
        byte[] source = fiscalJrxml();
        var reports = Map.of(
                TicketJrxmlBundleCompiler.MASTER_FILENAME,
                new TicketJrxmlBundleCompiler.CompiledReport(source, new byte[] {4, 5}));
        when(organization.currentStore()).thenReturn(store);
        when(templates.findStoreTemplateForUpdate(
                draft.getId(), store.getEmpresa().getId(), store.getId()))
                .thenReturn(Optional.of(draft));
        when(templates.saveAndFlush(any(DocumentTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketCompiler.compileUpload(any())).thenAnswer(invocation -> {
            Map<String, byte[]> sources = invocation.getArgument(0);
            assertThat(sources).containsOnlyKeys(TicketJrxmlBundleCompiler.MASTER_FILENAME);
            assertThat(sources.get(TicketJrxmlBundleCompiler.MASTER_FILENAME))
                    .containsExactly(source);
            return new TicketJrxmlBundleCompiler.CompiledBundle(
                    reports, "b".repeat(64));
        });
        var service = new DocumentTemplateArtifactService(
                templates, organization, mock(SafeJrxmlCompiler.class), ticketCompiler,
                new DocumentTemplateArtifactStorage(tempDir),
                mock(DocumentTemplateCatalogService.class), mock(AuditService.class),
                Clock.systemUTC());

        var result = service.uploadAndValidate(draft.getId(), java.util.List.of(
                new MockMultipartFile("files", "ticekt_v1.jrxml",
                        "application/xml", source)));

        assertThat(result.status()).isEqualTo(DocumentTemplateStatus.VALIDATED);
        assertThat(result.sha256()).isEqualTo("b".repeat(64));
    }

    private static byte[] fiscalJrxml() {
        return """
                <jasperReport name="fiscal" language="java">
                  <query language="sql"><![CDATA[
                    SELECT sif.qr_url, sif.qr_leyenda, sif.aviso_pruebas
                    FROM snapshot_impresion_fiscal sif
                    WHERE sif.documento_id = CAST($P{DOCUMENTO_ID} AS uuid)
                  ]]></query>
                  <textField><textFieldExpression><![CDATA[$F{qr_url}]]></textFieldExpression></textField>
                  <textField><textFieldExpression><![CDATA[$F{qr_leyenda}]]></textFieldExpression></textField>
                  <textField><textFieldExpression><![CDATA[$F{aviso_pruebas}]]></textFieldExpression></textField>
                  <textField><textFieldExpression><![CDATA[$F{qr_prefijo}]]></textFieldExpression></textField>
                  <element kind="component" width="99" height="99">
                    <component kind="barcode4j:QRCode" errorCorrectionLevel="M" margin="4">
                      <codeExpression><![CDATA[$F{qr_url}]]></codeExpression>
                    </component>
                  </element>
                </jasperReport>
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
