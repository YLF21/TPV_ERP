package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV137ContractTest {

    private static final String MIGRATION =
            "db/migration/V137__codigo_barras_historico_documento_linea.sql";

    @Test
    void addsNullableHistoricalBarcodeWithoutBackfillOrIndex() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("alter table documento_linea")
                .contains("add column codigo_barras varchar(128)")
                .doesNotContain("not null")
                .doesNotContain("update documento_linea")
                .doesNotContain("create index");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
