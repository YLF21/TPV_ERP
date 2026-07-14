package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV63ContractTest {

    private static final String MIGRATION =
            "db/migration/V63__producto_cantidad_por_paquete.sql";

    @Test
    void addsDefaultPackageQuantityToProduct() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("add column package_quantity numeric(19,3) default 1")
                .contains("ck_producto_package_quantity")
                .contains("package_quantity is null or package_quantity >= 0");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
