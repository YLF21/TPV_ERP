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
    void completesALoneUploadedMasterWithTheBuiltInSubreports() throws Exception {
        var sources = repositorySources();
        var bundle = compiler.compileUpload(java.util.Map.of(
                TicketJrxmlBundleCompiler.MASTER_FILENAME,
                sources.get(TicketJrxmlBundleCompiler.MASTER_FILENAME)));

        assertThat(bundle.reports()).containsOnlyKeys(
                TicketJrxmlBundleCompiler.REQUIRED_FILENAMES);
        assertThat(bundle.reports().values())
                .allSatisfy(report -> assertThat(report.compiled()).isNotEmpty());
    }

    @Test
    void compilesASelfContainedTicketWithoutAddingBuiltInSubreports() throws Exception {
        byte[] source = standaloneTicket();

        var bundle = compiler.compileUpload(java.util.Map.of(
                TicketJrxmlBundleCompiler.MASTER_FILENAME, source));

        assertThat(bundle.reports()).containsOnlyKeys(
                TicketJrxmlBundleCompiler.MASTER_FILENAME);
        assertThat(bundle.reports().get(TicketJrxmlBundleCompiler.MASTER_FILENAME).source())
                .containsExactly(source);
        assertThat(bundle.reports().get(TicketJrxmlBundleCompiler.MASTER_FILENAME).compiled())
                .isNotEmpty();
    }

    @Test
    void compilesTheEditableStandaloneTicketExample() throws Exception {
        byte[] source = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "..", "plantillas documentos", "ticekt_v1.jrxml"));

        var bundle = compiler.compileUpload(java.util.Map.of(
                TicketJrxmlBundleCompiler.MASTER_FILENAME, source));

        assertThat(bundle.reports()).containsOnlyKeys(
                TicketJrxmlBundleCompiler.MASTER_FILENAME);
        assertThat(bundle.reports().get(TicketJrxmlBundleCompiler.MASTER_FILENAME).compiled())
                .isNotEmpty();
    }

    @Test
    void allowsReadOnlyCommonTableExpressionsInASelfContainedTicket() {
        byte[] source = standaloneTicketWithQuery("""
                WITH cabecera AS (
                    SELECT d.id AS documento_id
                    FROM documento d
                    WHERE d.id = CAST($P{DOCUMENTO_ID} AS uuid)
                ),
                lineas AS (
                    SELECT dl.documento_id, dl.nombre
                    FROM documento_linea dl
                    INNER JOIN cabecera c ON c.documento_id = dl.documento_id
                )
                SELECT c.documento_id, l.nombre
                FROM cabecera c
                LEFT JOIN lineas l ON l.documento_id = c.documento_id
                """);

        var bundle = compiler.compileUpload(java.util.Map.of(
                TicketJrxmlBundleCompiler.MASTER_FILENAME, source));

        assertThat(bundle.reports()).containsOnlyKeys(
                TicketJrxmlBundleCompiler.MASTER_FILENAME);
    }

    @Test
    void allowsPersistedOperatorAndOriginTerminalInATicketQuery() {
        byte[] source = standaloneTicketWithQuery("""
                SELECT 1 AS dummy
                FROM documento d
                LEFT JOIN usuario u
                    ON u.id = COALESCE(d.confirmado_por, d.creado_por)
                   AND u.tienda_id = d.tienda_id
                LEFT JOIN terminal term
                    ON term.id = d.terminal_origen_id
                   AND term.tienda_id = d.tienda_id
                WHERE d.id = CAST($P{DOCUMENTO_ID} AS uuid)
                """);

        var bundle = compiler.compileUpload(java.util.Map.of(
                TicketJrxmlBundleCompiler.MASTER_FILENAME, source));

        assertThat(bundle.reports()).containsOnlyKeys(
                TicketJrxmlBundleCompiler.MASTER_FILENAME);
    }

    @Test
    void stillRejectsAnUnauthorizedPhysicalTableBehindACommonTableExpression() {
        byte[] source = standaloneTicketWithQuery("""
                WITH datos AS (
                    SELECT s.id
                    FROM secretos s
                    WHERE s.documento_id = CAST($P{DOCUMENTO_ID} AS uuid)
                )
                SELECT id FROM datos
                """);

        assertThatThrownBy(() -> compiler.compileUpload(java.util.Map.of(
                TicketJrxmlBundleCompiler.MASTER_FILENAME, source)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_ticket_query_forbidden");
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
            String resourcePath = TicketJrxmlBundleCompiler.builtInResourceName(filename);
            var resource = new ClassPathResource(resourcePath);
            try (var input = resource.getInputStream()) {
                sources.put(filename, input.readAllBytes());
            }
        }
        return sources;
    }

    private static byte[] standaloneTicket() {
        return standaloneTicketWithQuery("""
                SELECT 1 AS dummy
                FROM documento d
                WHERE d.id = CAST($P{DOCUMENTO_ID} AS uuid)
                """);
    }

    private static byte[] standaloneTicketWithQuery(String query) {
        return """
                <jasperReport name="standalone_ticket" language="java"
                    pageWidth="227" pageHeight="842" columnWidth="207"
                    leftMargin="10" rightMargin="10" topMargin="5" bottomMargin="5">
                    <parameter name="TIENDA_ID" class="java.lang.String"/>
                    <parameter name="DOCUMENTO_ID" class="java.lang.String"/>
                    <query language="sql"><![CDATA[%s]]></query>
                    <field name="dummy" class="java.lang.Integer"/>
                    <detail>
                        <band height="20">
                            <element kind="staticText" x="0" y="0" width="207" height="20">
                                <text><![CDATA[MI TICKET PERSONALIZADO]]></text>
                            </element>
                        </band>
                    </detail>
                </jasperReport>
                """.formatted(query).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
