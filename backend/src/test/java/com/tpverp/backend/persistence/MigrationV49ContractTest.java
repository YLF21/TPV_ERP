package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV49ContractTest {

    private static final String MIGRATION =
            "db/migration/V49__reglas_precio_y_concurrencia_masiva.sql";

    @Test
    void addsAtomicBulkCodesAndSeparatesPrimaryFromLastSupplier() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table producto_edicion_masiva_secuencia")
                .contains("primary key (tienda_id, fecha)")
                .contains("ultimo_numero between 1 and 999")
                .contains("max(right(codigo, 3)::integer)")
                .contains("add column principal boolean not null default false")
                .contains("where ultimo_proveedor = true")
                .contains("create unique index ux_producto_proveedor_principal")
                .contains("where principal")
                .contains("drop column if exists precio_compra_neto");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
