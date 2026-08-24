package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BuiltInDocumentJrxmlCatalogTest {

    @ParameterizedTest
    @MethodSource("models")
    void packagesCompilesAndFreezesEveryIntegratedModel(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        var catalog = new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler());

        var reference = catalog.reference(type, format);

        assertThat(reference.builtIn()).isTrue();
        assertThat(reference.sha256()).matches("[0-9a-f]{64}");
        assertThat(catalog.compiled(reference, type, format)).isNotEmpty();
    }

    private static Stream<Arguments> models() {
        return Stream.of(
                Arguments.of(DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4),
                Arguments.of(DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.TICKET_80),
                Arguments.of(DocumentTemplateType.ALBARAN_VENTA, DocumentTemplateFormat.A4),
                Arguments.of(DocumentTemplateType.ALBARAN_VENTA, DocumentTemplateFormat.TICKET_80),
                Arguments.of(DocumentTemplateType.VALE, DocumentTemplateFormat.TICKET_80),
                Arguments.of(DocumentTemplateType.TICKET_REGALO, DocumentTemplateFormat.TICKET_80),
                Arguments.of(DocumentTemplateType.RETIRADA_CAJA, DocumentTemplateFormat.TICKET_80),
                Arguments.of(DocumentTemplateType.RECTIFICATIVA_VENTA, DocumentTemplateFormat.A4),
                Arguments.of(DocumentTemplateType.RECTIFICATIVA_VENTA, DocumentTemplateFormat.TICKET_80),
                Arguments.of(DocumentTemplateType.SALIDA_ALMACEN, DocumentTemplateFormat.A4),
                Arguments.of(DocumentTemplateType.ENTRADA_ALMACEN, DocumentTemplateFormat.A4),
                Arguments.of(DocumentTemplateType.ALBARAN_ENTRADA, DocumentTemplateFormat.A4),
                Arguments.of(DocumentTemplateType.FACTURA_ENTRADA, DocumentTemplateFormat.A4),
                Arguments.of(DocumentTemplateType.HISTORIAL_VENTAS_PRODUCTO, DocumentTemplateFormat.A4));
    }
}
