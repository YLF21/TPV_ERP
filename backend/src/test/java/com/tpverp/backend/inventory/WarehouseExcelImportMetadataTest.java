package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WarehouseExcelImportMetadataTest {

    @Test
    void copiesAndNormalizesImportedFormulaMetadata() {
        var metadata = new WarehouseExcelImportMetadata(
                " productos.xlsx ",
                List.of(new WarehouseExcelImportMetadata.Formula(
                        " I2 ", " E2*2.5 ", " 10.25 ")));

        var copy = WarehouseExcelImportMetadata.copy(metadata);

        assertThat(copy.fileName()).isEqualTo("productos.xlsx");
        assertThat(copy.formulas()).singleElement().satisfies(formula -> {
            assertThat(formula.cell()).isEqualTo("I2");
            assertThat(formula.formula()).isEqualTo("E2*2.5");
            assertThat(formula.calculatedValue()).isEqualTo("10.25");
        });
    }

    @Test
    void preservesFormulaAndCalculatedValueAtExcelCellTextLimit() {
        String formula = "f".repeat(32_767);
        String calculatedValue = "v".repeat(32_767);
        var metadata = new WarehouseExcelImportMetadata("productos.xlsx",
                List.of(new WarehouseExcelImportMetadata.Formula("A1", formula, calculatedValue)));

        assertThat(metadata.formulas()).singleElement().satisfies(value -> {
            assertThat(value.formula()).hasSize(32_767).isEqualTo(formula);
            assertThat(value.calculatedValue()).hasSize(32_767).isEqualTo(calculatedValue);
        });
    }

    @Test
    void rejectsFormulaOrCalculatedValueBeyondExcelCellTextLimitWithoutTruncating() {
        assertThatThrownBy(() -> new WarehouseExcelImportMetadata("productos.xlsx",
                List.of(new WarehouseExcelImportMetadata.Formula("A1", "f".repeat(32_768), "v"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WarehouseExcelImportMetadata("productos.xlsx",
                List.of(new WarehouseExcelImportMetadata.Formula("A1", "f", "v".repeat(32_768)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFormulaMetadataAggregateAndDuplicateCells() {
        var formulas = java.util.stream.IntStream.range(0, 154)
                .mapToObj(index -> new WarehouseExcelImportMetadata.Formula(
                        "A" + (index + 1), "f".repeat(32_767), null))
                .toList();
        assertThatThrownBy(() -> new WarehouseExcelImportMetadata("productos.xlsx", formulas))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5.000.000");

        assertThatThrownBy(() -> new WarehouseExcelImportMetadata("productos.xlsx", List.of(
                new WarehouseExcelImportMetadata.Formula("A1", "f", null),
                new WarehouseExcelImportMetadata.Formula(" a1 ", "g", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("celda formula duplicada");
    }

    @Test
    void doesNotExposeFormulaMetadataInWarehouseApiResponses() throws Exception {
        var input = new WarehouseInput(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 7, 31), UUID.randomUUID());
        input.replace(
                null,
                null,
                null,
                List.of(new WarehouseInputLineCommand(
                        UUID.randomUUID(), java.math.BigDecimal.ONE, null,
                        java.math.BigDecimal.ZERO, false, "Producto")),
                new WarehouseExcelImportMetadata(
                        "productos.xlsx",
                        List.of(new WarehouseExcelImportMetadata.Formula(
                                "I2", "E2*2.5", "10.25"))));

        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(input))
                .doesNotContain("excelImport", "E2*2.5");
    }

    @Test
    void keepsNewSupplierMetadataAndReadsLegacyJsonSafely() throws Exception {
        UUID productId = UUID.randomUUID();
        var metadata = new WarehouseExcelImportMetadata(
                "productos.xlsx", List.of(), "a".repeat(64), "Hoja1", true, true,
                List.of(new WarehouseExcelImportMetadata.Line(productId, List.of(2, 5),
                        "REF-1", new BigDecimal("4.20"), new BigDecimal("10"))));
        var copy = WarehouseExcelImportMetadata.copy(metadata);
        assertThat(copy.updateSupplier()).isTrue();
        assertThat(copy.skipZeroPriceUpdate()).isTrue();
        assertThat(copy.lines()).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(productId);
            assertThat(line.rowNumbers()).containsExactly(2, 5);
            assertThat(line.grossPurchasePrice()).isEqualByComparingTo("4.20");
        });

        var legacy = new ObjectMapper().findAndRegisterModules().readValue(
                "{\"fileName\":\"old.xlsx\",\"formulas\":[]}",
                WarehouseExcelImportMetadata.class);
        assertThat(legacy.updateSupplier()).isFalse();
        assertThat(legacy.skipZeroPriceUpdate()).isFalse();
        assertThat(legacy.lines()).isEmpty();
    }

    @Test
    void replacingARecordWithoutExcelMetadataClearsThePreviousSnapshot() {
        UUID storeId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var input = new WarehouseInput(storeId, warehouseId, LocalDate.of(2026, 7, 8), UUID.randomUUID());
        var line = new WarehouseInputLineCommand(productId, java.math.BigDecimal.ONE,
                java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, false, "Producto");
        input.replace(null, null, "Compra", List.of(line),
                new WarehouseExcelImportMetadata("import.xlsx", List.of()));
        assertThat(input.getExcelImport()).isNotNull();
        input.replace(null, null, "Manual", List.of(line), null);
        assertThat(input.getExcelImport()).isNull();

        var output = new WarehouseOutput(storeId, warehouseId, LocalDate.of(2026, 7, 8), UUID.randomUUID());
        output.replace("Destino", "Salida", List.of(new WarehouseOutputLineCommand(productId, 1)),
                new WarehouseExcelImportMetadata("import.xlsx", List.of()));
        output.replace("Destino", "Manual", List.of(new WarehouseOutputLineCommand(productId, 1)), null);
        assertThat(output.getExcelImport()).isNull();
    }

    @Test
    void rejectsMoreThanFiveThousandOriginalRowsOrRowsAssignedToTwoProducts() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var tooMany = java.util.stream.IntStream.rangeClosed(1, 5_001).boxed().toList();
        assertThatThrownBy(() -> new WarehouseExcelImportMetadata("import.xlsx", List.of(), null, null,
                false, false, List.of(new WarehouseExcelImportMetadata.Line(first, tooMany, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5.000 filas");

        assertThatThrownBy(() -> new WarehouseExcelImportMetadata("import.xlsx", List.of(), null, null,
                false, false, List.of(
                        new WarehouseExcelImportMetadata.Line(first, List.of(2), null, null, null),
                        new WarehouseExcelImportMetadata.Line(second, List.of(2), null, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fila asignada");
    }

    @Test
    void validatesSupplierPriceScaleAndPrecision() {
        UUID productId = UUID.randomUUID();
        assertThatThrownBy(() -> new WarehouseExcelImportMetadata.Line(productId, List.of(2), "R",
                new BigDecimal("1.2345"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 decimales");
        assertThatThrownBy(() -> new WarehouseExcelImportMetadata.Line(productId, List.of(2), "R",
                new BigDecimal("100000000000000000.00"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("17 digitos");
        assertThatThrownBy(() -> new WarehouseExcelImportMetadata.Line(productId, List.of(2), "R",
                BigDecimal.ONE, new BigDecimal("10.123")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 decimales");
    }
}
