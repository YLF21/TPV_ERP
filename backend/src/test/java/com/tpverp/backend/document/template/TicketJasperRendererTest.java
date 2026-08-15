package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
