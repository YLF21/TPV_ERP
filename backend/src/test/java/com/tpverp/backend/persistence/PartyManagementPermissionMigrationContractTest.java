package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PartyManagementPermissionMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V73__gestion_clientes_proveedores.sql";

    @Test
    void createsCustomerSupplierManagementPermissionWithoutExpandingExistingRoles() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("'gestion_cliente_proveedor'")
                .contains("'party.permissions.management'")
                .doesNotContain("insert into rol_permiso");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
