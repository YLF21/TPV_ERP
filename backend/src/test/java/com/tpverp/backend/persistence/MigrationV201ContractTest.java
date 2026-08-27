package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV201ContractTest {

    @Test
    void validaHashSinReescribirLaIdentidadFiscalInmutable() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V201__fiscal_declaration_hash_check.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);

            assertThat(sql)
                    .contains("add constraint ck_version_sistema_fiscal_declaracion_hash")
                    .contains("declaracion_hash ~ '^[0-9a-fa-f]{64}$'")
                    .doesNotContain("update version_sistema_fiscal")
                    .doesNotContain("drop trigger")
                    .doesNotContain("disable trigger");
        }
    }
}
