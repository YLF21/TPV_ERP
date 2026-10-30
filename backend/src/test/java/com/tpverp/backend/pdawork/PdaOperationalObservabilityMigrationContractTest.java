package com.tpverp.backend.pdawork;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PdaOperationalObservabilityMigrationContractTest {
    @Test
    void migrationEnforcesTenantIdempotencyTypesAndBoundedDurations() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V283__pda_operational_observability.sql"));
        assertThat(sql).contains("UNIQUE (tienda_id, terminal_id, client_event_id)",
                "REFERENCES terminal(id)", "BETWEEN 0 AND 120000", "SYNC_FAILURE", "SYNC_CONFLICT",
                "TASK_BLOCKED", "REQUEST_LATENCY", "RECOVERED");
        assertThat(sql).doesNotContain("mensaje", "payload", "stacktrace", "ip_address");
    }
}
