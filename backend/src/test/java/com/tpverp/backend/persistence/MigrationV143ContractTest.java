package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV143ContractTest {

    @Test
    void addsNonNullableDraftEditingMetadataWithSafeDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V143__metadatos_edicion_borrador_venta.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("alter table documento_linea")
                    .contains("nombre_temporal_override boolean not null default false")
                    .contains("precio_temporal_override boolean not null default false")
                    .doesNotContain("drop ")
                    .doesNotContain("delete ")
                    .doesNotContain("update documento_linea");
        }
    }
}
