package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SaasAdminQueryIndexesMigrationTest {

    @Test
    void mantieneV35V36IntactasYConcentraLosIndicesEnV37() throws Exception {
        String v35 = migration("V35__legacy_igic_store_timezone.sql");
        String v36 = migration("V36__global_username_uniqueness.sql");
        String v37 = migration("V37__saas_admin_query_indexes.sql");

        assertThat(v35).doesNotContain("ix_saas_sync_event", "ix_saas_fiscal_status");
        assertThat(v36).doesNotContain("ix_saas_sync_event", "ix_saas_fiscal_status");
        assertThat(v37).contains(
                "ix_saas_sync_event_company_store_received_event",
                "ix_saas_sync_event_entity_company_store_received",
                "ix_saas_sync_event_entity_id_received",
                "ix_saas_fiscal_status_company_store_received");
    }

    private String migration(String name) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/" + name)) {
            assertThat(stream).as(name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
