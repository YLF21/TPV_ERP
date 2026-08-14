package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TicketJrxmlBundleCompilerTest {

    private final TicketJrxmlBundleCompiler compiler = new TicketJrxmlBundleCompiler();

    @Test
    void compilesTheRepositoryMasterAndEveryRequiredSubreportAsOneBundle() throws Exception {
        var bundle = compiler.compile(repositorySources());

        assertThat(bundle.reports()).containsOnlyKeys(
                TicketJrxmlBundleCompiler.REQUIRED_FILENAMES);
        assertThat(bundle.reports().get(TicketJrxmlBundleCompiler.MASTER_FILENAME).compiled())
                .isNotEmpty();
        var master = (net.sf.jasperreports.engine.JasperReport)
                net.sf.jasperreports.engine.util.JRLoader.loadObject(
                        new java.io.ByteArrayInputStream(bundle.reports()
                                .get(TicketJrxmlBundleCompiler.MASTER_FILENAME).compiled()));
        var firstSubreport = (net.sf.jasperreports.engine.JRSubreport)
                master.getTitle().getElements()[0];
        assertThat(firstSubreport.getExpression().getText())
                .startsWith("$P{" + TicketJrxmlBundleCompiler.SUBREPORT_DIRECTORY_PARAMETER + "} +");
        assertThat(bundle.sha256()).hasSize(64);
    }

    @Test
    void rejectsAnIncompleteBundleBeforeCompilation() throws Exception {
        var sources = repositorySources();
        sources.remove("ticket_pie_minimalista.jrxml");

        assertThatThrownBy(() -> compiler.compile(sources))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_ticket_bundle_incomplete");
    }

    @Test
    void rejectsMutationQueriesEvenForAnOtherwiseCompleteBundle() throws Exception {
        var sources = repositorySources();
        sources.put("ticket.jrxml", new String(sources.get("ticket.jrxml"),
                java.nio.charset.StandardCharsets.UTF_8)
                .replaceFirst("SELECT\\R\\s+1 AS dummy",
                        "DELETE FROM documento RETURNING 1 AS dummy")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> compiler.compile(sources))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_ticket_query_forbidden");
    }

    private static LinkedHashMap<String, byte[]> repositorySources() throws Exception {
        var sources = new LinkedHashMap<String, byte[]>();
        for (String filename : TicketJrxmlBundleCompiler.REQUIRED_FILENAMES) {
            String resourcePath = TicketJrxmlBundleCompiler.MASTER_FILENAME.equals(filename)
                    ? "reports/tickets/" + filename
                    : "reports/tickets/subreport/" + filename;
            var resource = new ClassPathResource(resourcePath);
            try (var input = resource.getInputStream()) {
                sources.put(filename, input.readAllBytes());
            }
        }
        return sources;
    }
}
