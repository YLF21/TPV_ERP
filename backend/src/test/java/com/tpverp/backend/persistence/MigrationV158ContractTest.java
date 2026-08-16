package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV158ContractTest {

    @Test
    void createsVoucherFamiliesAndBackfillsHistoricalChains() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V158__familias_vales_multitienda.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql)
                    .contains("create table vale_familia")
                    .contains("create table vale_familia_contador")
                    .contains("identificador ~ '^[0-9]{3}-[0-9]{6}$'")
                    .contains("tickets_origen ->> 0")
                    .contains("alter column familia_id set not null")
                    .contains("unique index vale_codigo_global_uk")
                    .contains("on vale(lower(codigo))");
        }
    }
}
