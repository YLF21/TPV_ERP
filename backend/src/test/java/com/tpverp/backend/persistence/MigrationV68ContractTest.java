package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV68ContractTest {

    private static final String MIGRATION =
            "db/migration/V68__proveedor_principal_manual.sql";

    @Test
    void clearsHistoricalPrincipalFlagsAndIncrementsOnlyChangedVersions() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("update producto_proveedor")
                .contains("set principal = false")
                .contains("version = version + 1")
                .contains("where principal = true")
                .doesNotContain("ultimo_proveedor")
                .doesNotContain("referencia_proveedor")
                .doesNotContain("precio_compra_bruto")
                .doesNotContain("descuento_compra")
                .doesNotContain("ultima_entrada_en");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
