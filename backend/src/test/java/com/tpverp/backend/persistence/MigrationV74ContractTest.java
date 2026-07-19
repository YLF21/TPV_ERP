package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV74ContractTest {

    private static final String MIGRATION =
            "db/migration/V74__preferencia_dashboard_usuario.sql";

    @Test
    void createsOneJsonDashboardPreferencePerUserWithCascadeDeletion() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table preferencia_dashboard")
                .contains("usuario_id uuid not null references usuario(id) on delete cascade")
                .contains("widgets jsonb not null")
                .contains("jsonb_array_length(widgets) <= 24")
                .contains("unique (usuario_id)");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
