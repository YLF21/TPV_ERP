package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
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
            try (var input = resource.getInputStream();
                    var output = new ByteArrayOutputStream()) {
                JasperCompileManager.getInstance(SafeJrxmlCompiler.secureContext())
                        .compileToStream(input, output);
                assertThat(output.size()).as(filename).isPositive();
            }
        }
    }
}
