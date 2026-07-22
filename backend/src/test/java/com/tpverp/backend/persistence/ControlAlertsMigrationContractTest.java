package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ControlAlertsMigrationContractTest {

    private static final String MIGRATION = "db/migration/V75__alertas_control.sql";

    @Test
    void createsPermissionsVersionedRulesImmutableEventsAndAlertHistory() throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("'control_alerts_read'", "'control_alerts_manage'", "'control_rules_manage'")
                .contains("create table control_regla (")
                .contains("ux_control_regla_tienda_nombre")
                .contains("create table control_regla_version (")
                .contains("unique (regla_id, numero_version)")
                .contains("create table control_evento (")
                .contains("regla_nombre varchar(160) not null")
                .contains("documento_numero varchar(32)")
                .contains("unique (regla_id, origen_tipo, origen_id)")
                .contains("create table control_alerta (")
                .contains("create table control_alerta_historial (")
                .contains("trg_control_evento_append_only")
                .contains("trg_control_alerta_historial_append_only")
                .doesNotContain("insert into control_regla");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
