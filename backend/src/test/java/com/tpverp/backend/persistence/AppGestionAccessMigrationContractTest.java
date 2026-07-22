package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AppGestionAccessMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V72__acceso_app_gestion.sql";

    @Test
    void createsAppAccessPermissionAndMigratesPreviouslyEligibleRoles() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("'app_gestion_access'")
                .contains("'security.permissions.appgestionaccess'")
                .contains("insert into rol_permiso (rol_id, permiso_id)")
                .contains("select distinct rol_permiso.rol_id, app_gestion_access.id")
                .contains("on conflict do nothing")
                .contains(
                        "'gestion_ventas'",
                        "'gestion_producto'",
                        "'gestion_almacen'");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
