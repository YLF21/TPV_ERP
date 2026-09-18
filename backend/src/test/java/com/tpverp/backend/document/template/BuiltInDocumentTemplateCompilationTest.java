package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BuiltInDocumentTemplateCompilationTest {

    @Test
    void compilesEveryPredeterminedDocumentTemplate() {
        var compiler = new SafeJrxmlCompiler();
        var resources = List.of(
                "reports/documents/v1/FACTURA_VENTA_A4.jrxml",
                "reports/documents/v1/FACTURA_VENTA_TICKET_80.jrxml",
                "reports/documents/v1/ALBARAN_VENTA_A4.jrxml",
                "reports/documents/v1/VALE_TICKET_80.jrxml",
                "reports/documents/v1/TICKET_REGALO_TICKET_80.jrxml",
                "reports/documents/v1/RETIRADA_CAJA_TICKET_80.jrxml",
                "reports/documents/v1/RECTIFICATIVA_VENTA_A4.jrxml",
                "reports/documents/v1/RECTIFICATIVA_VENTA_TICKET_80.jrxml",
                "reports/documents/v1/SALIDA_ALMACEN_A4.jrxml",
                "reports/documents/v1/ENTRADA_ALMACEN_A4.jrxml",
                "reports/documents/v1/ALBARAN_ENTRADA_A4.jrxml",
                "reports/documents/v1/FACTURA_ENTRADA_A4.jrxml",
                "reports/documents/v1/HISTORIAL_VENTAS_PRODUCTO_A4.jrxml");

        for (String resource : resources) {
            assertThatCode(() -> {
                try (var input = new ClassPathResource(resource).getInputStream()) {
                    compiler.compile(input.readAllBytes());
                }
            }).as(resource).doesNotThrowAnyException();
        }
    }

    @Test
    void compilesTheLegacyTicketInvoiceVariantKeptAsAnEditableSource() {
        assertThatCode(() -> {
            var source = Files.readAllBytes(Path.of(
                    "..", "plantillas documentos", "FACTURA_VENTA_TICKET_80_v2.jrxml"));
            try (var input = new ByteArrayInputStream(source);
                    var output = new ByteArrayOutputStream()) {
                net.sf.jasperreports.engine.JasperCompileManager
                        .getInstance(SafeJrxmlCompiler.secureContext())
                        .compileToStream(input, output);
            }
        }).doesNotThrowAnyException();
    }
}
