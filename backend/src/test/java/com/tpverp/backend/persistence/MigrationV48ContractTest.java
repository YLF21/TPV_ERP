package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV48ContractTest {

    private static final String MIGRATION =
            "db/migration/V48__stock_configuracion_historial.sql";

    @Test
    void createsStoreSettingsMinimumOverridesAndHistoryIndexes() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("add constraint ux_producto_tienda_id unique (tienda_id, id)")
                .contains("add constraint ux_almacen_tienda_id unique (tienda_id, id)")
                .contains("create table configuracion_stock")
                .contains("tienda_id uuid primary key references tienda(id) on delete cascade")
                .contains("almacen_predeterminado_id uuid not null")
                .contains("permitir_stock_negativo boolean not null default true")
                .contains("stock_minimo_predeterminado numeric(19, 3) not null default 5.000")
                .contains("alertas_habilitadas boolean not null default true")
                .contains("insert into configuracion_stock")
                .contains("warehouse.predeterminado")
                .contains("foreign key (tienda_id, almacen_predeterminado_id)")
                .contains("references almacen(tienda_id, id)")
                .contains("create table stock_minimo_almacen")
                .contains("cantidad_minima numeric(19, 3) not null")
                .contains("unique (producto_id, almacen_id)")
                .contains("foreign key (tienda_id, producto_id)")
                .contains("references producto(tienda_id, id) on delete cascade")
                .contains("foreign key (tienda_id, almacen_id)")
                .contains("check (cantidad_minima >= 0)")
                .contains("ix_documento_linea_producto_documento")
                .contains("ix_documento_tienda_almacen_fecha");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
