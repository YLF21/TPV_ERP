package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SafeJrxmlCompilerTest {

    private final SafeJrxmlCompiler compiler = new SafeJrxmlCompiler();

    @Test
    void compilesARestrictedJasperSevenReport() {
        var result = compiler.compile(report("$F{name}"));

        assertThat(result.compiled()).isNotEmpty();
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsSqlAndScriptlets() {
        String sql = validReport("<query language=\"sql\">select current_user</query>");
        String scriptlet = validReport("<scriptlet name=\"unsafe\" class=\"java.lang.Object\"/>");

        assertThatThrownBy(() -> compiler.compile(bytes(sql)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_jrxml_query_forbidden");
        assertThatThrownBy(() -> compiler.compile(bytes(scriptlet)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_jrxml_element_forbidden");
    }

    @Test
    void rejectsDangerousJavaExpressionAndExternalImage() {
        assertThatThrownBy(() -> compiler.compile(
                report("java.lang.Runtime.getRuntime().exec(\"cmd\")")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_jrxml_expression_forbidden");
        String externalImage = validReport("""
                <detail><band height="20">
                  <element kind="image" x="0" y="0" width="10" height="10">
                    <expression><![CDATA["https://example.invalid/logo.png"]]></expression>
                  </element>
                </band></detail>
                """);
        assertThatThrownBy(() -> compiler.compile(bytes(externalImage)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_jrxml_external_resource_forbidden");
    }

    @Test
    void allowsOnlyTheBackendOwnedIssuerLogoImageParameter() {
        String trustedLogo = validReport("""
                <parameter name="TPV_ISSUER_LOGO_STREAM" class="java.io.InputStream"/>
                <detail><band height="20">
                  <element kind="image" x="0" y="0" width="10" height="10">
                    <expression><![CDATA[$P{TPV_ISSUER_LOGO_STREAM}]]></expression>
                  </element>
                </band></detail>
                """);
        String arbitraryParameter = validReport("""
                <parameter name="OTHER_STREAM" class="java.io.InputStream"/>
                <detail><band height="20">
                  <element kind="image" x="0" y="0" width="10" height="10">
                    <expression><![CDATA[$P{OTHER_STREAM}]]></expression>
                  </element>
                </band></detail>
                """);

        assertThat(compiler.compile(bytes(trustedLogo)).compiled()).isNotEmpty();
        assertThatThrownBy(() -> compiler.compile(bytes(arbitraryParameter)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_jrxml_external_resource_forbidden");
    }

    @Test
    void rejectsMethodsOutsideTheInvoiceExpressionProfile() {
        assertThatThrownBy(() -> compiler.compile(report("$F{name}.matches(\".*\")")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_jrxml_expression_forbidden");
    }

    @Test
    void allowsLengthForBoundedTemplateText() {
        var expression = "$F{name} == null ? \"\" : "
                + "($F{name}.length() <= 64 ? $F{name} : $F{name}.substring(0, 64))";

        assertThat(compiler.compile(report(expression)).compiled()).isNotEmpty();
    }

    @Test
    void compilesVoucherTicketWithCode128Barcode() throws Exception {
        byte[] source = Files.readAllBytes(Path.of(
                "..", "plantillas documentos", "VALE_TICKET_80.jrxml"));

        assertThat(compiler.compile(source).compiled()).isNotEmpty();
    }

    private static byte[] report(String expression) {
        return bytes(validReport("""
                <field name="name" class="java.lang.String"/>
                <detail><band height="20">
                  <element kind="textField" x="0" y="0" width="100" height="20">
                    <expression><![CDATA[%s]]></expression>
                  </element>
                </band></detail>
                """.formatted(expression)));
    }

    private static String validReport(String body) {
        return """
                <jasperReport name="safe_report" language="java"
                    pageWidth="595" pageHeight="842" columnWidth="555"
                    leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                  %s
                </jasperReport>
                """.formatted(body);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
