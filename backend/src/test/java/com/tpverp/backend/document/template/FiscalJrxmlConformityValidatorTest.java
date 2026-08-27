package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FiscalJrxmlConformityValidatorTest {

    @Test
    void builtInInvoiceFormatsContainThePersistedTaxQrContract() throws Exception {
        for (var fixture : Map.of(
                DocumentTemplateType.FACTURA_VENTA,
                new String[] {"FACTURA_VENTA_A4.jrxml", "FACTURA_VENTA_TICKET_80.jrxml"},
                DocumentTemplateType.RECTIFICATIVA_VENTA,
                new String[] {"RECTIFICATIVA_VENTA_A4.jrxml",
                        "RECTIFICATIVA_VENTA_TICKET_80.jrxml"}).entrySet()) {
            for (var filename : fixture.getValue()) {
                var resource = new ClassPathResource("reports/documents/v1/" + filename);
                assertThat(resource.exists()).isTrue();
                FiscalJrxmlConformityValidator.require(
                        Map.of(filename, resource.getInputStream().readAllBytes()),
                        fixture.getKey());
            }
        }
    }

    @Test
    void builtInTicketBundleContainsThePersistedTaxQrContract() throws Exception {
        var sources = new java.util.LinkedHashMap<String, byte[]>();
        for (var filename : TicketJrxmlBundleCompiler.REQUIRED_FILENAMES) {
            var resource = new ClassPathResource(TicketJrxmlBundleCompiler.builtInResourceName(filename));
            sources.put(filename, resource.getInputStream().readAllBytes());
        }

        FiscalJrxmlConformityValidator.require(sources, DocumentTemplateType.TICKET);
    }

    @Test
    void editableStandaloneTicketContainsACompleteFrozenFiscalRoute() throws Exception {
        byte[] source = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "..", "plantillas documentos", "ticekt_v1.jrxml"));

        FiscalJrxmlConformityValidator.require(
                Map.of(TicketJrxmlBundleCompiler.MASTER_FILENAME, source),
                DocumentTemplateType.TICKET);
    }

    @Test
    void rejectsAStaticQrOrAUrlCalculatedOutsideTheSnapshot() {
        var source = "<jasperReport><text><![CDATA[QR tributario:]]></text>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> FiscalJrxmlConformityValidator.require(
                Map.of("template.jrxml", source), DocumentTemplateType.FACTURA_VENTA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_fiscal_snapshot_required");
    }

    @Test
    void rejectsAFiscalQrThatIsTooSmall() {
        var source = """
                <jasperReport>
                  <property name="snapshot_impresion_fiscal" value="qr_url"/>
                  <property name="qr_leyenda" value="fiscalLegend"/>
                  <property name="aviso_pruebas" value="fiscalTestNotice"/>
                  <element kind="textField"><expression><![CDATA[$F{qr_prefijo}]]></expression></element>
                  <element kind="textField"><expression><![CDATA[$F{qr_leyenda}]]></expression></element>
                  <element kind="textField"><expression><![CDATA[$F{aviso_pruebas}]]></expression></element>
                  <element kind="component" width="80" height="80">
                    <component kind="barcode4j:QRCode" margin="4" errorCorrectionLevel="M">
                      <codeExpression><![CDATA[$F{qr_url}]]></codeExpression>
                    </component>
                  </element>
                </jasperReport>
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> FiscalJrxmlConformityValidator.require(
                Map.of("template.jrxml", source), DocumentTemplateType.RECTIFICATIVA_VENTA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_fiscal_qr_size_required");
    }

    @Test
    void rejectsAJsonFiscalTemplateThatDoesNotRenderTheFrozenPrefix() {
        var source = """
                <jasperReport>
                  <property name="fiscalVerificationUrl" value="snapshot_impresion_fiscal"/>
                  <property name="fiscalLegend" value="$.fiscal.legend"/>
                  <property name="fiscalTestNotice" value="$.fiscal.testNotice"/>
                  <element kind="component" width="100" height="100">
                    <component kind="barcode4j:QRCode" margin="4" errorCorrectionLevel="M">
                      <codeExpression><![CDATA[$F{fiscalVerificationUrl}]]></codeExpression>
                    </component>
                  </element>
                </jasperReport>
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> FiscalJrxmlConformityValidator.require(
                Map.of("template.jrxml", source), DocumentTemplateType.FACTURA_VENTA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_fiscal_qr_label_required");
    }

    @Test
    void rejectsOneBrokenTicketVariantEvenWhenTheOtherRoutesAreConforming() throws Exception {
        var sources = new java.util.LinkedHashMap<String, byte[]>();
        for (var filename : TicketJrxmlBundleCompiler.REQUIRED_FILENAMES) {
            var resource = new ClassPathResource(TicketJrxmlBundleCompiler.builtInResourceName(filename));
            byte[] source = resource.getInputStream().readAllBytes();
            if ("ticket_pie_compacta.jrxml".equals(filename)) {
                source = new String(source, StandardCharsets.UTF_8)
                        .replace("$F{qr_leyenda}", "$F{observacion_ticket}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            sources.put(filename, source);
        }

        assertThatThrownBy(() -> FiscalJrxmlConformityValidator.require(
                sources, DocumentTemplateType.TICKET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_fiscal_legend_required");
    }
}
