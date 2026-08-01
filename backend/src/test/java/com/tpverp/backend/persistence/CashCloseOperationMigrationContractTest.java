package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CashCloseOperationMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V118__operacion_durable_cierre_caja.sql";

    @Test
    void persistsOneCloseOperationPerSessionAndIndependentIdempotentAttempts()
            throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("create table operacion_cierre_caja")
                .contains("sesion_caja_id uuid not null unique")
                .contains("movimiento_retirada_id uuid unique references movimiento_caja(id)")
                .contains("huella_retirada varchar(64) not null")
                .contains("add column operacion_cierre_id uuid references operacion_cierre_caja(id)")
                .contains("add column clave_idempotencia uuid")
                .contains("add column huella_solicitud varchar(64)")
                .contains("create unique index intento_arqueo_clave_idempotencia_uq")
                .contains("on intento_arqueo_caja(operacion_cierre_id, clave_idempotencia)");
    }

    @Test
    void migratesExistingV113ReservationsWithoutDuplicatingTheirIdentity()
            throws IOException {
        assertThat(migrationSql())
                .contains("insert into operacion_cierre_caja")
                .contains("from idempotencia_retirada_cierre idempotencia")
                .contains("on conflict (id) do nothing")
                .contains("update intento_arqueo_caja intento");
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
