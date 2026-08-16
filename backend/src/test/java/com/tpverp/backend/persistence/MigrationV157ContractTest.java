package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV157ContractTest {

    @Test
    void addsFutureVoucherExpirationModeWithoutRewritingExistingVouchers() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V157__modo_caducidad_vales.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql)
                    .contains("alter table configuracion_vale_tienda")
                    .contains("modo_caducidad varchar(16) not null default 'days'")
                    .contains("check (modo_caducidad in ('days', 'never'))")
                    .doesNotContain("update vale");
        }
    }
}
