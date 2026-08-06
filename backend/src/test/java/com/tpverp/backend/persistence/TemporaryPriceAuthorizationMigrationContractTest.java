package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TemporaryPriceAuthorizationMigrationContractTest {

    @Test
    void migrationPersistsOnlyHashedScopedAndSingleUseProofs() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V130__autorizacion_previa_cambio_precio.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("create table autorizacion_cambio_precio_venta")
                    .contains("token_hash varchar(64) not null unique")
                    .contains("linea_carrito_id varchar(128) not null")
                    .contains("precio_unitario numeric(19,2) not null")
                    .contains("version_politica bigint not null")
                    .contains("expira_en timestamptz not null")
                    .contains("origen_reserva_id uuid")
                    .contains("consumida_en timestamptz")
                    .doesNotContain("password")
                    .doesNotContain("contrasena");
        }
    }
}
