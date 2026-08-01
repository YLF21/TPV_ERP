package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CashCurrentBalanceIndexesMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V121__indices_efectivo_actual_terminal.sql");

    @Test
    void indexesSessionAndBetweenSessionMovementLookups() throws Exception {
        var sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql)
                .contains("movimiento_caja_sesion_fecha_idx")
                .contains("movimiento_caja(sesion_caja_id, creado_en)")
                .contains("where sesion_caja_id is not null")
                .contains("movimiento_caja_terminal_entre_sesiones_fecha_idx")
                .contains("movimiento_caja(terminal_id, creado_en)")
                .contains("where sesion_caja_id is null");
    }
}
