package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV70ContractTest {

    private static final String MIGRATION =
            "db/migration/V70__indices_stock_paginado.sql";

    @Test
    void addsPagedStockIndexesWithoutChangingData() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create extension if not exists pg_trgm")
                .contains("ix_producto_stock_page_tienda_nombre")
                .contains("on producto (tienda_id, lower(nombre), id)")
                .contains("ix_producto_stock_page_tipo")
                .contains("ix_producto_stock_page_uso_precio")
                .contains("ix_producto_stock_page_descuento")
                .contains("ix_producto_stock_page_familia")
                .contains("ix_producto_stock_page_subfamilia")
                .contains("ix_producto_stock_page_impuesto")
                .contains("ix_producto_stock_page_oferta_activa")
                .contains("using gin (lower(nombre) gin_trgm_ops)")
                .contains("using gin (lower(valor) gin_trgm_ops)")
                .contains("ix_producto_identificador_producto")
                .doesNotContain("alter table")
                .doesNotContain("update ")
                .doesNotContain("delete ");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
