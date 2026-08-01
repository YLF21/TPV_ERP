package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SaleOperationAuthorizationThrottleMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V116__limite_intentos_autorizacion_operativa.sql";

    @Test
    void persistsOnlyScopedFailureStateWithoutCredentials() throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("create table intento_autorizacion_operacion_venta")
                .contains("tienda_id uuid not null")
                .contains("operador_id uuid not null")
                .contains("terminal_id uuid not null")
                .contains("codigo_operacion varchar(64) not null")
                .contains("fallos_consecutivos integer not null")
                .contains("bloqueado_hasta timestamptz")
                .contains("ultimo_fallo_en timestamptz")
                .contains("unique (tienda_id, operador_id, terminal_id, codigo_operacion)")
                .contains("foreign key (operador_id) references usuario (id)")
                .doesNotContain("reserva_id")
                .doesNotContain("reserva_hasta")
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
