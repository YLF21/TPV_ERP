package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV26ContractTest {

    private static final String MIGRATION = "db/migration/V26__estado_saas_licencia.sql";

    @Test
    void anadeEstadoSaasDeLicenciaParaBloqueoManualCentral() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("add column estado_saas varchar(32) not null default 'valida'")
                .contains("check (estado_saas in ('valida', 'bloqueada_manual'))");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
