package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV62ContractTest {

    private static final String MIGRATION =
            "db/migration/V65__contador_documento_entrada_almacen.sql";

    @Test
    void allowsWarehouseInputDocumentCounterType() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("drop constraint if exists contador_documento_tipo_check")
                .contains("add constraint contador_documento_tipo_check")
                .contains("'ent'");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
