package com.tpverp.saas.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommercialDocumentLinesTest {
    @Test
    void preservesExactCodesSignsAndDecimalPrecisionWithoutCentralIdentityGuessing() {
        var line = line("000Ab ");
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        line.put("productoId", product.toString());
        line.put("tarifa", "DIFERENCIA");
        line.put("cantidad", "-2.1250000001");
        line.put("precioUnitario", new BigDecimal("0.000000000000001"));
        line.put("descuento", "7.123456789");
        line.put("total", "-9007199254740993.123456");
        var result = CommercialDocumentLines.parse(Map.of("lineas", List.of(line), "almacenId", warehouse.toString()));
        assertThat(result.status()).isEqualTo(CommercialDocumentLines.Status.READY);
        assertThat(result.warehouseLocalId()).isEqualTo(warehouse);
        assertThat(result.lines()).singleElement().satisfies(value -> {
            assertThat(value.code()).isEqualTo("000Ab ");
            assertThat(value.productLocalId()).isEqualTo(product);
            assertThat(value.priceTariff()).isEqualTo("DIFERENCIA");
            assertThat(value.quantity()).isEqualByComparingTo("-2.1250000001");
            assertThat(value.unitPrice()).isEqualByComparingTo("0.000000000000001");
            assertThat(value.discountPercent()).isEqualByComparingTo("7.123456789");
            assertThat(value.total()).isEqualByComparingTo("-9007199254740993.123456");
        });
    }

    @Test
    void distinguishesMissingEmptyAndMalformedHistoryWithoutThrowing() {
        assertThat(CommercialDocumentLines.parse(Map.of()).status()).isEqualTo(CommercialDocumentLines.Status.MISSING);
        assertThat(CommercialDocumentLines.parse(Map.of("lineas", List.of())).status()).isEqualTo(CommercialDocumentLines.Status.READY);
        assertThat(CommercialDocumentLines.parse(Map.of("lineas", "invalid")).status()).isEqualTo(CommercialDocumentLines.Status.INVALID);
        assertThat(CommercialDocumentLines.parse(Map.of("lineas", List.of(line("001"), line("002")))).status())
                .isEqualTo(CommercialDocumentLines.Status.INVALID);
        assertThat(CommercialDocumentLines.parse(Map.of("lineas", List.of(line("001")), "almacenId", "bad")).status())
                .isEqualTo(CommercialDocumentLines.Status.INVALID);
    }

    @Test
    void keepsNonProductLinesWithoutInventingAProductCode() {
        var line = line("001");
        line.put("tipoLinea", "DOCUMENT_DISCOUNT");
        line.remove("codigo");
        var result = CommercialDocumentLines.parse(Map.of("lineas", List.of(line)));
        assertThat(result.status()).isEqualTo(CommercialDocumentLines.Status.READY);
        assertThat(result.lines().getFirst().code()).isNull();
        assertThat(result.lines().getFirst().priceTariff()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void marksMalformedLinesInvalidWithoutReturningPartialRows(String field, Object value) {
        var invalid = line("002");
        invalid.put("posicion", 2);
        invalid.put(field, value);
        var result = CommercialDocumentLines.parse(Map.of("lineas", List.of(line("001"), invalid)));
        assertThat(result.status()).isEqualTo(CommercialDocumentLines.Status.INVALID);
        assertThat(result.lines()).isEmpty();
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(Arguments.of("codigo", null), Arguments.of("codigo", " "),
                Arguments.of("codigo", "x".repeat(129)), Arguments.of("codigo", "bad\0code"),
                Arguments.of("nombre", "\uD800"), Arguments.of("productoId", "not-uuid"),
                Arguments.of("tarifa", 123),
                Arguments.of("tipoLinea", "UNKNOWN"), Arguments.of("posicion", 0),
                Arguments.of("posicion", "1.5"), Arguments.of("cantidad", Double.NaN),
                Arguments.of("precioUnitario", "1,25"), Arguments.of("descuento", null),
                Arguments.of("total", "1e1000000"), Arguments.of("total", "9".repeat(1025)));
    }

    static Map<String, Object> line(String code) {
        var line = new LinkedHashMap<String, Object>();
        line.put("posicion", 1);
        line.put("tipoLinea", "PRODUCT");
        line.put("codigo", code);
        line.put("nombre", "Producto ficticio");
        line.put("cantidad", "2.000");
        line.put("precioUnitario", "1.235");
        line.put("descuento", "0.000");
        line.put("total", "2.47");
        return line;
    }
}
