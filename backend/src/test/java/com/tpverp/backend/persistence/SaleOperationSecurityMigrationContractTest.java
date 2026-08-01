package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SaleOperationSecurityMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V112__seguridad_operaciones_venta.sql";

    @Test
    void createsVersionedPerStoreConfigurationAndValidatedOverrides()
            throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("create table configuracion_seguridad_operacion_venta")
                .contains("tienda_id uuid primary key")
                .contains("config_version bigint not null")
                .contains("row_version bigint not null")
                .contains("check (config_version >= 0)")
                .contains("create table configuracion_seguridad_operacion_venta_override")
                .contains("unique (tienda_id, codigo_operacion)")
                .contains("check (codigo_operacion in (")
                .contains("'payment_compensation_ack'");
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
