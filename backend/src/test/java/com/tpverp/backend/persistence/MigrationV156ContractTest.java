package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV156ContractTest {

    @Test
    void addsVoucherExpiryWithoutChangingExistingVouchersRetroactively() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V156__gestion_y_caducidad_vales.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql)
                    .contains("create table configuracion_vale_tienda")
                    .contains("vigencia_dias integer not null default 365")
                    .contains("add column caduca_el date")
                    .contains("create table vale_gestion_evento")
                    .contains("'reactivated', 'reprinted', 'reprint_failed'")
                    .doesNotContain("update vale set caduca_el");
        }
    }
}
