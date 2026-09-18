package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.sf.jasperreports.engine.JasperCompileManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class OperationalReceiptJasperRendererTest {

    @Test
    void builtInOperationalReceiptSourcesCompile() throws Exception {
        for (String filename : List.of("ticket_anulado.jrxml", "cobro_pendiente.jrxml")) {
            var resource = new ClassPathResource(
                    "reports/operational-receipts/" + filename);
            assertThat(resource.exists()).as(filename).isTrue();
            byte[] source;
            try (var input = resource.getInputStream()) {
                source = input.readAllBytes();
            }
            var sourceText = new String(source, StandardCharsets.UTF_8);
            assertThat(sourceText)
                    .as(filename)
                    .contains("UPPER(REPLACE(COALESCE(NULLIF(TRIM(mp.nombre), ''), 'PAGO'), '_', ' '))");
            if ("ticket_anulado.jrxml".equals(filename)) {
                assertThat(sourceText)
                        .contains("da.tipo = 'MEMBER_PERCENT'")
                        .contains("'Descuento miembro '")
                        .doesNotContain("Descuento de miembro");
            }
            try (var input = new java.io.ByteArrayInputStream(source);
                    var output = new ByteArrayOutputStream()) {
                JasperCompileManager.getInstance(SafeJrxmlCompiler.secureContext())
                        .compileToStream(input, output);
                assertThat(output.size()).as(filename).isPositive();
            }
        }
    }
}
