package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV151ContractTest {

    @Test
    void createsGenericPerFormatOriginsAndPreservesExistingSelections() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V151__origen_plantilla_documento_por_formato.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("primary key (tienda_id, tipo, formato)")
                    .contains("origen in ('INTEGRATED', 'IMPORTED')")
                    .contains("coalesce(configuracion.origen_plantilla_ticket, 'INTEGRATED')")
                    .contains("cross join (values ('A4'), ('TICKET_80'))")
                    .contains("plantilla.tipo = 'FACTURA_VENTA'")
                    .contains("plantilla.ambito = 'STORE'")
                    .contains("plantilla.ambito = 'COMPANY'")
                    .contains("plantilla.ambito = 'SYSTEM'")
                    .contains("then 'IMPORTED' else 'INTEGRATED' end");
        }
    }
}
