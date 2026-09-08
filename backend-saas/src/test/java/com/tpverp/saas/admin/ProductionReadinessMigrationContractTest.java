package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

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

    @Test
    void v49RequiresFiscalEvidenceAndCountsOnlyActiveLicenses() throws IOException {
        String sql = migration("V49__production_delivery_and_fiscal_evidence.sql");

        assertThat(sql).contains(
                "fiscal_reason",
                "fiscal_legal_basis",
                "fiscal_evidence_reference",
                "saas_invoice_fiscal_decision_audit",
                "not valid",
                "status = 'VALIDA'",
                "valid_until > current_timestamp",
                "update of status, valid_until, company_id");
        assertThat(sql).doesNotContain("set fiscal_status = 'PENDING_TAX_DATA'");
    }

    @Test
    void v50AddsAuditableRecoveryStatesAndFailedIndexesWithoutDroppingLiveIndexes() throws IOException {
        String sql = migration("V50__outbox_operational_recovery.sql");

        assertThat(sql).contains(
                "ACKNOWLEDGED",
                "RECOVERED_ORPHAN_CLAIM",
                "idx_saas_security_outbox_failed",
                "idx_saas_integration_run_failed_delivery",
                "delivery_attempt_count = 0");
        assertThat(sql).doesNotContain(
                "drop index idx_saas_security_outbox_delivery",
                "drop index idx_saas_integration_run_delivery");
    }

    @Test
    void v51BuildsTerminalRetentionIndexConcurrently() throws IOException {
        String sql = migration("V51__security_payload_retention_index.sql");
        assertThat(sql).contains(
                "create index concurrently",
                "ACKNOWLEDGED",
                "encrypted_payload <> '__PURGED__'");
        assertThat(sql).doesNotContain("alter table", "drop index");
    }

    @Test
    void v52BuildsIntegrationRetentionIndexConcurrently() throws IOException {
        String sql = migration("V52__integration_payload_retention_index.sql");
        assertThat(sql).contains(
                "create index concurrently",
                "saas_integration_run",
                "payload <> '__PURGED__'");
        assertThat(sql).doesNotContain("alter table", "drop index");
    }

    @Test
    void flywayUsesSessionLockForConcurrentPostgresqlIndexes() throws IOException {
        var application = new ClassPathResource("application.yml");
        String yaml = application.getContentAsString(StandardCharsets.UTF_8);

        assertThat(yaml).contains(
                "flyway:",
                "postgresql:",
                "transactional-lock: false");
    }

    private String migration(String filename) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("db/migration/" + filename)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
