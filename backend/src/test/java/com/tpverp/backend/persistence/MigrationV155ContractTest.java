package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV155ContractTest {

    @Test
    void addsStoreNameVisibilityWithBackwardCompatibleDefault() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V155__visibilidad_nombre_tienda_documentos.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(sql)
                    .contains("add column mostrar_nombre_tienda boolean not null default true");
        }
    }
}
