package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InternalEanMigrationContractTest {

    @Test
    void migrationCreatesDurableAllocationAndExtendsSecurityCatalog() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V124__ean_interno_y_seguridad.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("create table configuracion_ean_interno")
                    .contains("create table secuencia_ean_interno")
                    .contains("create table asignacion_ean_interno")
                    .contains("unique (empresa_id, codigo)")
                    .contains("'GENERATE_PRODUCT_EAN'");
        }
    }
}
