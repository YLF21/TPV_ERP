package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WarehouseDisplayOrderMigrationContractTest {
    @Test
    void initializesIndependentCompleteStoreOrdersAndKeepsOperationalFieldsUntouched() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V270__warehouse_display_order.sql"));
        assertThat(sql).contains("partition by tienda_id order by predeterminado desc, nombre, id",
                "alter column display_order set not null", "check (display_order >= 0)",
                "on almacen(tienda_id, display_order, id)");
        assertThat(sql).doesNotContain("where activo", "set predeterminado", "set activo",
                "update stock", "update configuracion_stock_almacen");
    }
}
