package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV61ContractTest {

    private static final String MIGRATION =
            "db/migration/V61__stock_min_max_producto.sql";

    @Test
    void addsMinimumAndMaximumStockToProduct() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("add column stock_min numeric(19,3)")
                .contains("add column stock_max numeric(19,3)")
                .contains("ck_producto_stock_min_max")
                .contains("stock_max >= stock_min");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
