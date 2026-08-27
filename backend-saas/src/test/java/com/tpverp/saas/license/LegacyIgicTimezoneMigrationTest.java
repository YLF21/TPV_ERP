package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LegacyIgicTimezoneMigrationTest {

    @Test
    void corrigeSoloTiendasLegacyIgicSinDireccionConfigurada() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V35__legacy_igic_store_timezone.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("company.tax_regime = 'igic'")
                    .contains("store.store_address is null")
                    .contains("store.time_zone_id = 'europe/madrid'")
                    .contains("set time_zone_id = 'atlantic/canary'");
        }
    }
}
