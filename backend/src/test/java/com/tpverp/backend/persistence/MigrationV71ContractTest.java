package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV71ContractTest {

    private static final String MIGRATION =
            "db/migration/V71__gestion_almacen_unificada.sql";

    @Test
    void createsUnifiedPermissionMigratesRolesAndRemovesLegacyPermissions() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("'gestion_almacen'")
                .contains("insert into rol_permiso (rol_id, permiso_id)")
                .contains("select distinct rol_permiso.rol_id, gestion_almacen.id")
                .contains("on conflict do nothing")
                .contains("delete from rol_permiso")
                .contains("delete from permiso")
                .contains(
                        "'warehouse_inputs_read'",
                        "'warehouse_inputs_write'",
                        "'warehouse_inputs_delete'",
                        "'warehouse_inputs_confirm'",
                        "'warehouse_outputs_read'",
                        "'warehouse_outputs_edit'",
                        "'warehouse_outputs_delete'",
                        "'warehouse_outputs_confirm'");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
