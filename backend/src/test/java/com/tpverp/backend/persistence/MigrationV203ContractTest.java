package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MigrationV203ContractTest {

    @Test
    void anadeIdentidadCongeladaSinInventarDatosHistoricos() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V203__freeze_fiscal_obligated_identity.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);

            assertThat(sql)
                    .contains("add column obligado_nombre")
                    .contains("add column obligado_nif")
                    .contains("obligado_nombre is null and obligado_nif is null")
                    .doesNotContain("update artefacto_registro_fiscal");
        }
    }
}
