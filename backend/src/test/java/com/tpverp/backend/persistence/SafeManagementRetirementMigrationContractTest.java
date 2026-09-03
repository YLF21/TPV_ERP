package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SafeManagementRetirementMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V240__safe_management_retirement_foundations.sql";

    @Test
    void addsRepresentativeLifecycleIndexesAndRemovesAssignableDeletePermissions()
            throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql)
                    .contains("alter table comercial")
                    .contains("activo boolean not null default true")
                    .contains("ix_producto_management_page")
                    .contains("ix_cliente_management_page")
                    .contains("ix_proveedor_management_page")
                    .contains("ix_comercial_management_page")
                    .contains("ix_proveedor_comercial_comercial")
                    .contains("delete from rol_permiso")
                    .contains("delete from permiso")
                    .contains("'products_delete'")
                    .contains("'customers_delete'")
                    .contains("'suppliers_delete'")
                    .doesNotContain("delete from producto")
                    .doesNotContain("delete from cliente")
                    .doesNotContain("delete from proveedor")
                    .doesNotContain("delete from comercial\n");
        }
    }
}
