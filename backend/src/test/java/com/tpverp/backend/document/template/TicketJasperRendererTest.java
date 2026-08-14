package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.util.JRLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TicketJasperRendererTest {

    private static final String[] REPORTS = {
        "ticket.jasper",
        "subreport/ticket_cabecera.jasper", "subreport/ticket_cabecera_compacta.jasper",
        "subreport/ticket_cabecera_minimalista.jasper",
        "subreport/ticket_cliente.jasper", "subreport/ticket_cliente_compacta.jasper",
        "subreport/ticket_cliente_minimalista.jasper",
        "subreport/ticket_contenido.jasper", "subreport/ticket_contenido_compacta.jasper",
        "subreport/ticket_contenido_minimalista.jasper",
        "subreport/ticket_impuesto.jasper", "subreport/ticket_impuesto_compacta.jasper",
        "subreport/ticket_impuesto_minimalista.jasper",
        "subreport/ticket_pago.jasper", "subreport/ticket_pago_compacta.jasper",
        "subreport/ticket_pago_minimalista.jasper",
        "subreport/ticket_pie.jasper", "subreport/ticket_pie_compacta.jasper",
        "subreport/ticket_pie_minimalista.jasper"
    };

    @Test
    void packagesEveryCompiledMasterAndSubreport() throws IOException, JRException {
        for (String name : REPORTS) {
            var resource = new ClassPathResource(name);
            assertThat(resource.exists()).as(name).isTrue();
            try (var input = resource.getInputStream()) {
                assertThat(JRLoader.loadObject(input)).as(name).isNotNull();
            }
        }
    }

    @Test
    void packagesAndCompilesEveryEditableReportSource() throws IOException, JRException {
        for (String compiledName : REPORTS) {
            String sourceName = "reports/tickets/"
                    + compiledName.replace(".jasper", ".jrxml");
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
