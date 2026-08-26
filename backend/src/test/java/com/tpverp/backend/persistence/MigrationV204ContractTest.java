package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV204ContractTest {

    @Test
    void impideDegradarUnaLicenciaSaasYaAutenticada() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V204__autenticidad_cache_licencia_saas.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("old.format_version = 5")
                    .contains("new.format_version <> 5")
                    .contains("before update of format_version on licencia")
                    .contains("no puede abandonar el formato 5");
        }
    }
}
