package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV152ContractTest {

    @Test
    void preservesActiveDeliveryNoteAndVoucherTemplatesAsImported() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V152__origen_plantilla_albaran_y_vale.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("('ALBARAN_VENTA', 'A4')")
                    .contains("('VALE', 'TICKET_80')")
                    .contains("plantilla.estado = 'ACTIVE'")
                    .contains("then 'IMPORTED' else 'INTEGRATED' end")
                    .contains("on conflict (tienda_id, tipo, formato) do nothing");
        }
    }
}
