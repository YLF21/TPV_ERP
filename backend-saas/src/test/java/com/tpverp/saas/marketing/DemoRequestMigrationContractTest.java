package com.tpverp.saas.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class DemoRequestMigrationContractTest {

    @Test
    void migrationDefinesAValidatedAndIndexedLeadInbox() throws Exception {
        String sql;
        try (var input = new ClassPathResource("db/migration/V75__marketing_demo_requests.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql)
                .contains("create table saas_marketing_demo_request")
                .contains("privacy_accepted_at timestamptz not null")
                .contains("check (product in")
                .contains("check (locale in")
                .contains("idx_saas_marketing_demo_request_inbox");
    }

    @Test
    void attributionMigrationUsesBoundedColumnsAndAnOptionalCampaignIndex() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V76__marketing_demo_request_attribution.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql)
                .contains("landing_path varchar(500)")
                .contains("referrer varchar(1000)")
                .contains("utm_source varchar(160)")
                .contains("utm_medium varchar(160)")
                .contains("utm_campaign varchar(200)")
                .contains("idx_saas_marketing_demo_request_campaign")
                .contains("where utm_campaign is not null");
    }

    @Test
    void productsMigrationBackfillsLegacyRequestsAndConstrainsTheEntireSelection() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V77__marketing_demo_request_products.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql)
                .contains("add column products varchar(24)[]")
                .contains("set products = array[product]")
                .contains("alter column products set not null")
                .contains("array_ndims(products) = 1")
                .contains("cardinality(products) between 1 and 4")
                .contains("array_position(products, null) is null")
                .contains("products <@ array['app_venta', 'app_gestion', 'app_pda', 'app_saas']")
                .contains("cardinality(products) =")
                .contains("product = products[1]");
    }
}
