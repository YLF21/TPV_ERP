package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SaasOperationalIntegrityMigrationTest {

    @Test
    void v44EnforcesBillingAndStoreOwnershipInTheDatabase() throws Exception {
        String sql = migration();

        assertThat(sql).contains("foreign key (store_id, company_id)");
        assertThat(sql).contains("uq_saas_billing_payment_invoice_reference");
        assertThat(sql).contains("prevent_saas_billing_overpayment");
        assertThat(sql).contains("for update");
        assertThat(sql).contains("el pago supera el saldo pendiente de la factura");
        assertThat(sql).contains("ck_saas_billing_invoice_status");
    }

    @Test
    void v44CreatesAuditableIdempotentLocalIntegrationRuns() throws Exception {
        String sql = migration();

        assertThat(sql).contains("create table saas_integration_run");
        assertThat(sql).contains("idempotency_key varchar(120) not null");
        assertThat(sql).contains("unique(integration_id, idempotency_key, attempt)");
        assertThat(sql).contains("status in ('running', 'succeeded', 'failed')");
        assertThat(sql).contains("delivery_mode in ('local_outbox')");
        assertThat(sql).contains("error_code", "error_message");
    }

    private String migration() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V44__saas_operational_integrity.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
