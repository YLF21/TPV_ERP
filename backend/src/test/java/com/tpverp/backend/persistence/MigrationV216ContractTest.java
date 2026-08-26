package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV216ContractTest {
    @Test
    void congelaPeriodoCompletoYOrdenadoDelRequerimientoFiscal() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V216__fiscal_required_submission_period.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql).contains("alter table requerimiento_fiscal")
                    .contains("add column periodo_inicio timestamptz")
                    .contains("add column periodo_fin timestamptz")
                    .contains("ck_requerimiento_fiscal_periodo")
                    .contains("periodo_fin >= periodo_inicio");
        }
    }
}
