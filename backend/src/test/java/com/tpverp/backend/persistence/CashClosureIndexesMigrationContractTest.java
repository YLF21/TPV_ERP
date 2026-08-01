package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CashClosureIndexesMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V120__indices_consulta_cierres_caja.sql");

    @Test
    void indexesClosedSessionsByTerminalAndClosingUser() throws Exception {
        var sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql)
                .contains("sesion_caja_tienda_terminal_cierre_idx")
                .contains("sesion_caja(tienda_id, terminal_id, cerrada_en desc, id)")
                .contains("sesion_caja_tienda_usuario_cierre_idx")
                .contains("sesion_caja(tienda_id, usuario_cierre_id, cerrada_en desc, id)")
                .contains("where estado = 'cerrada'");
    }
}
