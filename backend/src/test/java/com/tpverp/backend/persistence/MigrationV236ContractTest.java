package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV236ContractTest {

    @Test
    void removesManualOrderAndAddsProductClassificationIntegrity() throws IOException {
        String sql;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V236__remove_manual_catalog_order_and_reinforce_product_classification.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("drop column if exists orden")
                .contains("drop index if exists ix_familia_tienda_orden")
                .contains("drop index if exists ix_subfamilia_familia_orden")
                .contains("set familia_id = sf.familia_id")
                .contains("uq_subfamilia_familia_id")
                .contains("uq_familia_tienda_id")
                .contains("fk_producto_tienda_familia")
                .contains("fk_producto_familia_subfamilia")
                .contains("general no admite subfamilias")
                .contains("resuelvelas antes de v236");
        assertThat(sql).doesNotContain("new.orden").doesNotContain("old.orden");
    }
}
