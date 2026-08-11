package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ResolvedDocumentTemplateTest {

    @Test
    void providesStableBuiltInFallbackForEverySupportedSalesDocument() {
        var expected = Map.of(
                DocumentTemplateType.FACTURA_VENTA, "FACTURA_A4",
                DocumentTemplateType.ALBARAN_VENTA, "ALBARAN_A4",
                DocumentTemplateType.TICKET, "TICKET_80");

        expected.forEach((type, code) -> {
            var resolved = ResolvedDocumentTemplate.builtIn(type);
            assertThat(resolved.code()).isEqualTo(code);
            assertThat(resolved.type()).isEqualTo(type);
            assertThat(resolved.scope()).isEqualTo(DocumentTemplateScope.SYSTEM);
            assertThat(resolved.builtIn()).isTrue();
            assertThat(resolved.version()).isEqualTo(1);
        });
    }
}
