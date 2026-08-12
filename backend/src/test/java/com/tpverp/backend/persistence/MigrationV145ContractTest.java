package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV145ContractTest {

    @Test
    void addsParkedSaleDeletionToTheControlRuleCatalogWithoutMutatingData() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V145__alerta_eliminacion_venta_guardada.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("drop constraint if exists control_regla_tipo_ck")
                    .contains("drop constraint if exists control_regla_configuracion_tipo_ck")
                    .contains("'parked_sale_deleted'")
                    .doesNotContain("drop table")
                    .doesNotContain("delete from")
                    .doesNotContain("update control_regla");
        }
    }
}
