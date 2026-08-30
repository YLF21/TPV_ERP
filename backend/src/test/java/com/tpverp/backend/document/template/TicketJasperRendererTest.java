package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import com.tpverp.backend.organization.TicketTemplateOrigin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.util.JRLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class TicketJasperRendererTest {

    private static final String[] REPORTS = {
        "ticket.jasper",
        "ticket_cabecera.jasper", "ticket_cabecera_compacta.jasper",
        "ticket_cabecera_minimalista.jasper",
        "ticket_cliente.jasper", "ticket_cliente_compacta.jasper",
        "ticket_cliente_minimalista.jasper",
        "ticket_contenido.jasper", "ticket_contenido_compacta.jasper",
        "ticket_contenido_minimalista.jasper",
        "ticket_impuesto.jasper", "ticket_impuesto_compacta.jasper",
        "ticket_impuesto_minimalista.jasper",
        "ticket_pago.jasper", "ticket_pago_compacta.jasper",
        "ticket_pago_minimalista.jasper",
        "ticket_pie.jasper", "ticket_pie_compacta.jasper",
        "ticket_pie_minimalista.jasper"
    };

    @Test
    void materializesEveryCompiledMasterAndSubreport(@TempDir Path temporaryDirectory)
            throws JRException {
        var bundle = new BuiltInTicketJasperBundle(
                new TicketJrxmlBundleCompiler(),
                new DocumentTemplateArtifactStorage(temporaryDirectory));
        Path master = bundle.compiledMaster();
        for (String name : REPORTS) {
            Path report = master.getParent().resolve(Path.of(name).getFileName());
            assertThat(Files.isRegularFile(report)).as(name).isTrue();
            assertThat(JRLoader.loadObject(report.toFile())).as(name).isNotNull();
        }
        assertThat(bundle.compiledMaster()).isEqualTo(master);
    }

    @Test
    void packagesAndCompilesEveryEditableReportSource() throws IOException, JRException {
        for (String compiledName : REPORTS) {
            String sourceName = TicketJrxmlBundleCompiler.builtInResourceName(
                    compiledName.replace(".jasper", ".jrxml"));
            var resource = new ClassPathResource(sourceName);
            assertThat(resource.exists()).as(sourceName).isTrue();
            try (var input = resource.getInputStream()) {
                assertThat(JasperCompileManager.compileReport(input))
                        .as(sourceName).isNotNull();
            }
        }
    }

    @Test
    void acceptsSupportedTemplateNamesAndLegacyCompactAlias() {
        assertThat(TicketJasperRenderer.Template.parse(null))
                .isEqualTo(TicketJasperRenderer.Template.PRINCIPAL);
        assertThat(TicketJasperRenderer.Template.parse(" compacta "))
                .isEqualTo(TicketJasperRenderer.Template.COMPACTA);
        assertThat(TicketJasperRenderer.Template.parse("COMPACTO"))
                .isEqualTo(TicketJasperRenderer.Template.COMPACTA);
        assertThat(TicketJasperRenderer.Template.parse("minimalista"))
                .isEqualTo(TicketJasperRenderer.Template.MINIMALISTA);
    }

    @Test
    void rejectsUnknownTemplateNames() {
        assertThatThrownBy(() -> TicketJasperRenderer.Template.parse("otro"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ticket_jasper_template_invalid");
    }

    @Test
    void builtInPaymentSubreportsFormatMethodIdentifiersConsistently() throws IOException {
        for (String filename : new String[] {
                "ticket_pago.jrxml",
                "ticket_pago_compacta.jrxml",
                "ticket_pago_minimalista.jrxml"}) {
            var resource = new ClassPathResource(
                    TicketJrxmlBundleCompiler.builtInResourceName(filename));
            String source;
            try (var input = resource.getInputStream()) {
                source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(source).as(filename)
                    .contains("UPPER(REPLACE(COALESCE(NULLIF(TRIM(mp.nombre), ''), 'PAGO'), '_', ' '))")
                    .doesNotContain("Tarjeta bancaria")
                    .doesNotContain("<![CDATA[Efectivo]]>");
        }
    }

    @Test
    void integratedOriginUsesTheBundledReportsWithoutResolvingAnImport()
            throws IOException {
        var resolver = mock(DocumentTemplateResolver.class);
        var storage = mock(DocumentTemplateArtifactStorage.class);
        var configuration = mock(StoreDocumentPrintConfigurationService.class);
        var builtIn = mock(BuiltInTicketJasperBundle.class);
        var master = Path.of("integrated", "ticket.jasper");
        when(configuration.ticketTemplateOrigin()).thenReturn(TicketTemplateOrigin.INTEGRATED);
        when(builtIn.compiledMaster()).thenReturn(master);
        var renderer = new TicketJasperRenderer(
                mock(DataSource.class), resolver, storage, configuration, builtIn);

        assertThat(renderer.effectiveMaster()).isEqualTo(master);
        verifyNoInteractions(resolver, storage);
    }

    @Test
    void importedOriginUsesOnlyTheActiveIntegrityCheckedBundle() throws IOException {
        var resolver = mock(DocumentTemplateResolver.class);
        var storage = mock(DocumentTemplateArtifactStorage.class);
        var configuration = mock(StoreDocumentPrintConfigurationService.class);
        var reports = Map.of("ticket.jrxml", "source".getBytes());
        var sha256 = TicketJrxmlBundleCompiler.bundleSha256(reports);
        var resolved = new ResolvedDocumentTemplate(
                UUID.randomUUID(), DocumentTemplateType.TICKET,
                DocumentTemplateFormat.TICKET_80, DocumentTemplateScope.STORE,
                "TICKET_80", 2, 1, "artifact", sha256, false);
        var master = Path.of("imported", "ticket.jasper");
        when(configuration.ticketTemplateOrigin()).thenReturn(TicketTemplateOrigin.IMPORTED);
        when(resolver.resolve(DocumentTemplateType.TICKET)).thenReturn(resolved);
        when(storage.isBundle("artifact")).thenReturn(true);
        when(storage.readBundleSources("artifact")).thenReturn(reports);
        when(storage.compiledBundleMaster(
                "artifact", TicketJrxmlBundleCompiler.MASTER_FILENAME)).thenReturn(master);
        var renderer = new TicketJasperRenderer(
                mock(DataSource.class), resolver, storage, configuration,
                mock(BuiltInTicketJasperBundle.class));

        assertThat(renderer.effectiveMaster()).isEqualTo(master);
    }

    @Test
    void importedOriginNeverFallsBackToTheIntegratedBundle() {
        var resolver = mock(DocumentTemplateResolver.class);
        var storage = mock(DocumentTemplateArtifactStorage.class);
        var configuration = mock(StoreDocumentPrintConfigurationService.class);
        var resolved = new ResolvedDocumentTemplate(
                UUID.randomUUID(), DocumentTemplateType.TICKET,
                DocumentTemplateFormat.TICKET_80, DocumentTemplateScope.STORE,
                "TICKET_80", 2, 1, "artifact", "a".repeat(64), false);
        when(configuration.ticketTemplateOrigin()).thenReturn(TicketTemplateOrigin.IMPORTED);
        when(resolver.resolve(DocumentTemplateType.TICKET)).thenReturn(resolved);
        when(storage.isBundle("artifact")).thenReturn(false);
        var renderer = new TicketJasperRenderer(
                mock(DataSource.class), resolver, storage, configuration,
                mock(BuiltInTicketJasperBundle.class));

        assertThatThrownBy(renderer::effectiveMaster)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ticket_imported_template_bundle_required");
    }
}
