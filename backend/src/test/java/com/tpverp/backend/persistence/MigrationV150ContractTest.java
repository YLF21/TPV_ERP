package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV150ContractTest {

    @Test
    void addsTicketOriginAndPreservesEveryEffectiveImportedScope() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V150__origen_plantilla_ticket_por_tienda.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("origen_plantilla_ticket varchar(16)")
                    .contains("plantilla.ambito = 'STORE'")
                    .contains("plantilla.ambito = 'COMPANY'")
                    .contains("plantilla.ambito = 'SYSTEM'")
                    .contains("then 'IMPORTED'")
                    .contains("else 'INTEGRATED'")
                    .contains("alter column origen_plantilla_ticket set default 'INTEGRATED'")
                    .contains("check (origen_plantilla_ticket in ('INTEGRATED', 'IMPORTED'))");
        }
    }
}
