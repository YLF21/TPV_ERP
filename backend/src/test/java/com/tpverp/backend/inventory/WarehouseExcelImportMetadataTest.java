package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
}
