package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV149ContractTest {

    @Test
    void addsVoucherObservationsAndImmutablePrintSnapshotStorage() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V149__impresion_jasper_vales.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("observaciones_vale varchar(2000)")
                    .contains("impresion_snapshot jsonb")
                    .contains("jsonb_typeof(impresion_snapshot) = 'object'");
        }
    }
}
