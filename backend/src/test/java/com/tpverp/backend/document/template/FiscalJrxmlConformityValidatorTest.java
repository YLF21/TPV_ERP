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
        for (var filename : new String[] {
                "FACTURA_VENTA_A4.jrxml", "FACTURA_VENTA_TICKET_80.jrxml"}) {
            var resource = new ClassPathResource("reports/documents/v1/" + filename);
            assertThat(resource.exists()).isTrue();
            FiscalJrxmlConformityValidator.require(
                    Map.of(filename, resource.getInputStream().readAllBytes()),
                    DocumentTemplateType.FACTURA_VENTA);
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
    void rejectsAStaticQrOrAUrlCalculatedOutsideTheSnapshot() {
        var source = "<jasperReport><text><![CDATA[QR tributario:]]></text>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> FiscalJrxmlConformityValidator.require(
                Map.of("template.jrxml", source), DocumentTemplateType.FACTURA_VENTA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_fiscal_snapshot_required");
    }
}
