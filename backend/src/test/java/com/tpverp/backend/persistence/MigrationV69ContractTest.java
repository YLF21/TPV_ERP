package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV69ContractTest {

    @Test
    void legacyErrorsBecomeUncertainBecauseTheirPhysicalEffectWasNotClassified() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V69__classify_legacy_terminal_errors_as_uncertain.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();
            assertThat(sql).contains("update payment_terminal_operation")
                    .contains("set completed_at = null")
                    .contains("where status = 'error'");
        }
    }
}
