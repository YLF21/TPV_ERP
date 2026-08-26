package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV215ContractTest {
    @Test
    void integrityJobsHaveTenantIsolationBoundedEvidenceAndExclusiveClaims() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V215__trabajos_integridad_fiscal.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql)
                    .contains("create table trabajo_integridad_fiscal")
                    .contains("foreign key (tienda_id, empresa_id) references tienda(id, empresa_id)")
                    .contains("jsonb_array_length(evidencia_codigos) <= 1000")
                    .contains("token_ejecucion uuid")
                    .contains("estado = 'running' and token_ejecucion is not null")
                    .contains("unique index ux_trabajo_integridad_fiscal_activo")
                    .contains("on trabajo_integridad_fiscal(empresa_id, instalacion_id)")
                    .doesNotContain("concurrently");
        }
    }
}
