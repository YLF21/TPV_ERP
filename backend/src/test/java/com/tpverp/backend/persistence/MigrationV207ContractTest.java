package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MigrationV207ContractTest {

    @Test
    void preservesFailedScheduledTransitionAsAppendOnlyHistory() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V207__fiscal_transition_failure_history.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);

            assertThat(sql)
                    .contains("transicion_origen_id")
                    .contains("ultimo_error_codigo")
                    .contains("ultimo_error text")
                    .contains("'fallida'")
                    .contains("ux_transicion_modo_fiscal_fallo_origen")
                    .doesNotContain("update transicion_modo_fiscal");
        }
    }
}
