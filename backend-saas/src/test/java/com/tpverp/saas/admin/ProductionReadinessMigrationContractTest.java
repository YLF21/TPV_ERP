package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProductionReadinessMigrationContractTest {

    @Test
    void v47ClosesProductionIntegrityGapsWithValidDollarQuotes() throws IOException {
        String sql = migration("V47__production_readiness_hardening.sql");

        assertThat(sql).contains("'PRO'", "pg_advisory_xact_lock", "active = true");
        assertThat(sql).contains("uq_saas_reconciliation_matched_payment", "prevent_duplicate_saas_password_reset");
        assertThat(sql).contains("Planes SaaS sin politica configurada", "MATCHED duplicadas");
        assertThat(sql).contains("returns trigger language plpgsql as $$");
        assertThat(sql).doesNotContain("returns trigger language plpgsql as $\nbegin");
        assertThat(sql).contains("PENDING_TAX_DATA", "idx_saas_integration_run_pending");
    }

    @Test
    void v48AddsShortClaimsIdempotencyAndDeliveryRetryIndexes() throws IOException {
        String sql = migration("V48__outbox_claims_and_delivery_safety.sql");

        assertThat(sql).contains("idempotency_key", "claim_token", "claimed_at",
                "delivery_attempt_count", "idx_saas_security_outbox_delivery",
                "idx_saas_integration_run_delivery");
    }

    private String migration(String filename) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("db/migration/" + filename)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
