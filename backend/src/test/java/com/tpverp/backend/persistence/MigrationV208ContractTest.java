package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MigrationV208ContractTest {

    @Test
    void freezesNewIssuerAddressesWithoutInventingHistoricalValues() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V208__freeze_fiscal_obligated_address.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);

            assertThat(sql)
                    .contains("add column obligado_direccion jsonb")
                    .contains("new.obligado_direccion is null")
                    .contains("identidad y direccion congeladas")
                    .doesNotContain("update artefacto_registro_fiscal");
        }
    }
}
