package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV221ContractTest {
    @Test
    void añadeNumeroDeSerieConValorSeguroYRestriccionDeUnidad() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V221__producto_numero_serie.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql).contains("add column if not exists requires_serial_number boolean not null default false")
                    .contains("check (requires_serial_number = false or product_type = 'unit')");
        }
    }
}
