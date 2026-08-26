package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV212ContractTest {
    @Test
    void creaTrabajoFiscalDurableConEstadosYRetencion() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V212__trabajos_exportacion_fiscal.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql)
                    .contains("create table trabajo_exportacion_fiscal")
                    .contains("scope varchar(16) not null")
                    .contains("record_ids jsonb not null")
                    .contains("modo_ejecucion varchar(16) not null")
                    .contains("token_ejecucion uuid")
                    .contains("secuencia_corte bigint not null")
                    .contains("check (estado in ('queued', 'running', 'completed', 'failed', 'expired'))")
                    .contains("foreign key (tienda_id, empresa_id) references tienda(id, empresa_id)")
                    .contains("ix_trabajo_exportacion_fiscal_scope")
                    .contains("tr_exportacion_fiscal_inmutable")
                    .contains("tr_exportacion_fiscal_inmutable")
                    .contains("on exportacion_fiscal")
                    .contains("impedir_mutacion_fiscal")
                    .contains("estado = 'running' and token_ejecucion is not null")
                    .doesNotContain("concurrently");
        }
        var lifecycle = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/tpverp/backend/verifactu/FiscalExportJobLifecycle.java"));
        var prod = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application-prod.yml"));
        assertThat(lifecycle).contains("tpv.verifactu.export-job-single-instance");
        assertThat(prod).contains("export-job-single-instance");
    }
}
