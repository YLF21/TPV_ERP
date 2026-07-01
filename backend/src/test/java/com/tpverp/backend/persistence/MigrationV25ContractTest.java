package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV25ContractTest {

    private static final String MIGRATION =
            "db/migration/V25__ultima_validacion_saas_licencia.sql";

    @Test
    void anadeUltimaValidacionSaasALicenciaConValorInicialSeguro() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("alter table licencia")
                .contains("add column ultima_validacion_saas timestamptz")
                .contains("set ultima_validacion_saas = importada_en")
                .contains("alter column ultima_validacion_saas set not null");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
