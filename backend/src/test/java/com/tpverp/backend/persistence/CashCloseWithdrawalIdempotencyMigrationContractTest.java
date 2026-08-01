package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CashCloseWithdrawalIdempotencyMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V115__idempotencia_retirada_cierre_caja.sql";

    @Test
    void persistsOneGloballyUniqueKeyWithPayloadFingerprintAndMovementReference()
            throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("create table idempotencia_retirada_cierre")
                .contains("clave_idempotencia uuid primary key")
                .contains("movimiento_caja_id uuid unique references movimiento_caja(id)")
                .contains("huella_solicitud varchar(64) not null")
                .contains("foreign key (sesion_caja_id, terminal_id, tienda_id)")
                .contains("check (huella_solicitud ~ '^[0-9a-f]{64}$')");
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
