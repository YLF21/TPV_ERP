package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV43ContractTest {

    private static final String MIGRATION = "db/migration/V43__uso_precio_producto.sql";

    @Test
    void agregaUsoDePrecioYDescuentoDeOfertaAlProducto() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("alter table producto")
                .contains("add column price_use_mode varchar(32) not null default 'normal'")
                .contains("add column oferta_descuento_porcentaje numeric(5,2)")
                .contains("ck_producto_price_use_mode")
                .contains("'offer_price'")
                .contains("'offer_discount'")
                .contains("ck_producto_oferta_descuento_porcentaje")
                .contains("between 0 and 100");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
