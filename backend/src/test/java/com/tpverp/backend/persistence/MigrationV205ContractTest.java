package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV205ContractTest {

    @Test
    void permiteSubirDeCincoASeisPeroImpideCualquierDowngrade() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V205__upgrade_authenticated_saas_cache.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("old.format_version >= 5")
                    .contains("new.format_version < old.format_version")
                    .contains("no puede reducir su formato")
                    .contains("permite actualizar de la mac legacy v5 a v6");
        }
    }
}
