package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV31ContractTest {

    private static final String MIGRATION = "db/migration/V31__datos_cliente_y_canales_comerciales.sql";

    @Test
    void agregaDatosPersonalesYCanalesComercialesConfigurables() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table commercial_contact_channel")
                .contains("empresa_id uuid not null references empresa(id)")
                .contains("insert into commercial_contact_channel")
                .contains("'email'")
                .contains("'whatsapp'")
                .contains("alter table cliente")
                .contains("add column birthday date")
                .contains("add column gender varchar(16)")
                .contains("add column commercial_consent boolean not null default false")
                .contains("add column preferred_commercial_channel_id uuid")
                .contains("check (gender is null or gender in ('masculino', 'femenino', 'otro'))")
                .contains("commercial_consent = false or preferred_commercial_channel_id is not null");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
