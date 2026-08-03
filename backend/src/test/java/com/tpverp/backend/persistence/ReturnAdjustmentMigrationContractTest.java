package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReturnAdjustmentMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V128__ajuste_fiscal_devolucion.sql";

    @Test
    void addsDedicatedSignedReturnAdjustmentLine() throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("'return_adjustment'")
                .contains("ck_documento_linea_return_adjustment_integrity")
                .contains("producto_id is null")
                .doesNotContain("total >= 0");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();
        }
    }
}
