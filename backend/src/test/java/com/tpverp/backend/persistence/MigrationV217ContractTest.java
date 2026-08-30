package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV217ContractTest {
    @Test
    void ampliaIdentidadFiscalYBackfilleaSinDejarLaTablaMutable() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V217__fiscal_release_capability.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql).contains("add column release_id")
                    .contains("add column artifact_hash")
                    .contains("add column commit_hash")
                    .contains("add column capacidad_producto")
                    .contains("add column esquema_version")
                    .contains("drop trigger tr_version_sistema_fiscal_inmutable")
                    .contains("create trigger tr_version_sistema_fiscal_inmutable")
                    .contains("capacidad_producto in ('verifactu_only', 'dual')");
        }
    }
}
