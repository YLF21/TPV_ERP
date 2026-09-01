package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV233RetentionContractTest {
    @Test
    void addsDurableRevisionFingerprintAndRecoveryMetrics() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V233__retencion_saldo_devoluciones_local.sql");
        String sql = Files.readString(migration)
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        assertThat(sql).contains(
                "retention_revision BIGINT NOT NULL DEFAULT 0",
                "retention_fingerprint VARCHAR(64) NOT NULL DEFAULT ''",
                "retention_attributed_amount NUMERIC(19, 2) NOT NULL DEFAULT 0",
                "retention_held_known NUMERIC(19, 2) NOT NULL DEFAULT 0",
                "retention_pending_missing NUMERIC(19, 2) NOT NULL DEFAULT 0",
                "retention_spent_shortfall NUMERIC(19, 2) NOT NULL DEFAULT 0",
                "retention_spendable NUMERIC(19, 2) NOT NULL DEFAULT 0",
                "retention_recovered_known NUMERIC(19, 2) NOT NULL DEFAULT 0",
                "retention_reserved_lots JSONB NOT NULL DEFAULT '[]'::jsonb");
        assertThat(sql).contains(
                "retention_held_known + retention_pending_missing\n"
                        + "                + retention_spent_shortfall + retention_recovered_known\n"
                        + "                = retention_attributed_amount");
    }
}
