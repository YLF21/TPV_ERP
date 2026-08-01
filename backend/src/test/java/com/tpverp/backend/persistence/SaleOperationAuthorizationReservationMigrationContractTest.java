package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SaleOperationAuthorizationReservationMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V119__reserva_intentos_autorizacion_operativa.sql";

    @Test
    void addsAtomicAuthorizationReservationWithoutPersistingCredentials() throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("alter table intento_autorizacion_operacion_venta")
                .contains("add column reserva_id uuid")
                .contains("add column reserva_hasta timestamptz")
                .contains("intento_autorizacion_operacion_venta_reserva_ck")
                .contains("intento_autorizacion_operacion_venta_reserva_idx")
                .contains("where reserva_hasta is not null")
                .doesNotContain("password")
                .doesNotContain("contrasena");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();
        }
    }
}
