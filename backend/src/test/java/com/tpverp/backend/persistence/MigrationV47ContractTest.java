package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV47ContractTest {

    private static final String MIGRATION = "db/migration/V47__datos_compra_producto_proveedor.sql";

    @Test
    void addsLastSupplierGrossDiscountNetAndTimestamp() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("ultimo_proveedor boolean not null")
                .contains("precio_compra_bruto numeric(19,2)")
                .contains("descuento_compra numeric(5,2)")
                .contains("rename column ultima_fecha_entrada to ultima_entrada_en")
                .contains("ultima_entrada_en type timestamptz")
                .contains("precio_compra_neto numeric(19,2)")
                .contains("generated always as")
                .contains("ux_producto_proveedor_ultimo");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
