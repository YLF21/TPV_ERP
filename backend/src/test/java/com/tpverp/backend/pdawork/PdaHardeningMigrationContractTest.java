package com.tpverp.backend.pdawork;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PdaHardeningMigrationContractTest {
    @Test
    void migrationAddsIdempotencyFingerprintAndRetentionIndex() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V284__pda_work_fingerprint_and_event_retention.sql"));
        assertThat(sql).contains("client_request_fingerprint", "recibido_en");
    }
}
