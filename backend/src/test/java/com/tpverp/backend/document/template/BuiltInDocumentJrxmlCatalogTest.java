package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.document.InvoicePresentationSnapshot;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
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
        assertThat(reference.version()).isEqualTo(
                type == DocumentTemplateType.FACTURA_VENTA || format == DocumentTemplateFormat.TICKET_80 ? 2 : 1);
        assertThat(reference.sha256()).matches("[0-9a-f]{64}");
        assertThat(catalog.compiled(reference, type, format)).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("historicalTemplates")
    void preservesExactHistoricalSourceAndResolvesBothFrozenVersions(
            DocumentTemplateType type, DocumentTemplateFormat format, String filename, String historicalSha256) throws IOException {
        var catalog = new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler());
        var current = catalog.reference(type, format);
        var historical = new InvoicePresentationSnapshot.TemplateReference(null,
                current.code(), 1, SafeJrxmlCompiler.DATA_SCHEMA_VERSION, historicalSha256, true);
        var v1 = new org.springframework.core.io.ClassPathResource("reports/documents/v1/" + filename);
        var v2 = new org.springframework.core.io.ClassPathResource("reports/documents/v2/" + filename);
        try (var input = v1.getInputStream()) {
            assertThat(SafeJrxmlCompiler.sha256(input.readAllBytes())).isEqualTo(historicalSha256);
        }
        try (var input = v2.getInputStream()) {
            assertThat(current.sha256()).isEqualTo(SafeJrxmlCompiler.sha256(input.readAllBytes()));
        }
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.sha256()).isNotEqualTo(historical.sha256());
        assertThat(catalog.compiled(historical, type, format)).isNotEmpty();
        assertThat(catalog.compiled(current, type, format)).isNotEmpty();
        var wrongHash = new InvoicePresentationSnapshot.TemplateReference(null,
                current.code(), 1, SafeJrxmlCompiler.DATA_SCHEMA_VERSION, current.sha256(), true);
        assertThatThrownBy(() -> catalog.compiled(wrongHash, type, format))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("document_builtin_template_reference_mismatch");
    }

    @Test
    void rejectsUnknownInvoiceVersionAndUnchangedTypesWithVersionTwo() {
        var catalog = new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler());
        var future = new InvoicePresentationSnapshot.TemplateReference(null,
                "INTEGRATED_FACTURA_VENTA_A4", 3, SafeJrxmlCompiler.DATA_SCHEMA_VERSION, null, true);
        assertThatThrownBy(() -> catalog.compiled(future,
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("document_builtin_template_reference_mismatch");
        var unrelated = new InvoicePresentationSnapshot.TemplateReference(null,
                "INTEGRATED_ALBARAN_VENTA_A4", 2, SafeJrxmlCompiler.DATA_SCHEMA_VERSION, null, true);
        assertThatThrownBy(() -> catalog.compiled(unrelated,
                DocumentTemplateType.ALBARAN_VENTA, DocumentTemplateFormat.A4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("document_builtin_template_reference_mismatch");
    }

    private static Stream<Arguments> historicalTemplates() {
        return Stream.of(
                Arguments.of(DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4, "FACTURA_VENTA_A4.jrxml",
                        "cac56648bbc71bad3eb2934271bedbc2e9e60374131bc19aca5023904819e43c"),
                Arguments.of(DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.TICKET_80, "FACTURA_VENTA_TICKET_80.jrxml",
                        "17bdf9888c0c332e217ed70e480c41d9de9d58e4f146093f83a746f96755edad"),
                Arguments.of(DocumentTemplateType.ALBARAN_VENTA, DocumentTemplateFormat.TICKET_80,
                        "ALBARAN_VENTA_TICKET_80.jrxml", "c56a089742b1cac9cc8517dee60c9c2f4a3ab552e00b65b315c19215170d47e2"),
                Arguments.of(DocumentTemplateType.VALE, DocumentTemplateFormat.TICKET_80,
                        "VALE_TICKET_80.jrxml", "9a5b072b0ab5580b1ce87bf0e2b12a07ef279aef5b0f8d6ee2d882c4048971ec"),
                Arguments.of(DocumentTemplateType.TICKET_REGALO, DocumentTemplateFormat.TICKET_80,
                        "TICKET_REGALO_TICKET_80.jrxml", "c7045fa0c82a2b95fbcab95062a292bdb3873a107b0129dbb50699e2067b30da"),
                Arguments.of(DocumentTemplateType.ENTRADA_CAJA, DocumentTemplateFormat.TICKET_80,
                        "ENTRADA_CAJA_TICKET_80.jrxml", "5014a04f33662d364cbba84bb9ecadc1691d6ce7babe8ecf6e5a4c7bb44b886f"),
                Arguments.of(DocumentTemplateType.RETIRADA_CAJA, DocumentTemplateFormat.TICKET_80,
                        "RETIRADA_CAJA_TICKET_80.jrxml", "62f71f3edbd6e680c923bf906ea6b0e8a364107ee5b025cc0ff21e6799145770"),
                Arguments.of(DocumentTemplateType.RECTIFICATIVA_VENTA, DocumentTemplateFormat.TICKET_80,
                        "RECTIFICATIVA_VENTA_TICKET_80.jrxml", "128283942325935b3bc858235639099a631278ea9254a617729e7c072fc3eed9"));
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
                Arguments.of(DocumentTemplateType.ENTRADA_CAJA, DocumentTemplateFormat.TICKET_80),
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
