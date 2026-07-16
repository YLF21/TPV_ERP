package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV69ContractTest {

    private static final String MIGRATION =
            "db/migration/V69__producto_activo_y_venta_productos_desactivados.sql";

    @Test
    void addsProductLifecycleAndInactiveSalePolicyWithConservativeDefaults() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("alter table producto")
                .contains("activo boolean not null default true")
                .contains("alter table configuracion_stock")
                .contains("permitir_venta_producto_desactivado boolean not null default false");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
