package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV45ContractTest {

    private static final String MIGRATION = "db/migration/V45__producto_codigo_barra2_descuento_compra.sql";

    @Test
    void agregaDescuentoCompraPorcentajeAlProducto() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("alter table producto")
                .contains("add column if not exists descuento_compra_porcentaje numeric(5,2)")
                .contains("ck_producto_descuento_compra_porcentaje")
                .contains("between 0 and 100")
                .contains("producto_identificador_tipo_check")
                .contains("'codigo_barras_2'");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
