package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV28ContractTest {

    @Test
    void createsGoodsCheckTablesAndOpenDocumentGuard() throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V28__comprobacion_mercancia.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql)
                    .contains("create table comprobacion_mercancia")
                    .contains("create unique index ux_comprobacion_mercancia_documento_abierta")
                    .contains("where estado = 'abierta'")
                    .contains("create table comprobacion_mercancia_linea")
                    .contains("cantidad_registrada >= 0");
        }
    }
}
