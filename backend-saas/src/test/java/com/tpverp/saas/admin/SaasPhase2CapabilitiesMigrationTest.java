package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SaasPhase2CapabilitiesMigrationTest {

    @Test
    void v45DefinesEffectiveQuotasAndOperationalFoundations() throws Exception {
        String sql = migration();
        assertThat(sql).contains("create table saas_plan_policy");
        assertThat(sql).contains("trg_saas_sync_daily_plan_limit");
        assertThat(sql).contains("trg_saas_tenant_user_plan_limit");
        assertThat(sql).contains("trg_saas_customer_plan_limit");
        assertThat(sql).contains("add column fiscal_year");
        assertThat(sql).contains("create table saas_payment_reconciliation");
        assertThat(sql).contains("manual_bank", "manual_gateway");
        assertThat(sql).contains("create table saas_sync_outbox");
    }

    private String migration() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V45__saas_plan_limits_fiscal_and_reconciliation.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
