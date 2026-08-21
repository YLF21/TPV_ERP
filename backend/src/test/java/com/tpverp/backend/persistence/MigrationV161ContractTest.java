package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV161ContractTest {

    @Test
    void addsAccrualActivationAndBaseAmountsWithCompatibleDefaults() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V161__activacion_acumulacion_fidelizacion.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql)
                    .contains("points_accrual_enabled boolean not null default true")
                    .contains("points_accrual_base_amount numeric(19, 2) not null default 1.00")
                    .contains("balance_accrual_enabled boolean not null default false")
                    .contains("balance_accrual_base_amount numeric(19, 2) not null default 1.00")
                    .contains("balance_accrual_enabled = balance_accrual_percent > 0");
        }
    }
}
